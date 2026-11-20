package com.jarves.mh.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** TCP port of the app-owned adb server inside the Linux environment (adb's default). */
internal const val ADB_SERVER_PORT = 5037

/** mDNS service Android advertises while its "Pair device with pairing code" dialog is open. */
internal const val ADB_PAIRING_SERVICE = "_adb-tls-pairing._tcp"

/** mDNS service Android advertises while Wireless debugging is on. */
internal const val ADB_CONNECT_SERVICE = "_adb-tls-connect._tcp"

/**
 * The `adb` agents see. It refuses to run while phone access is off, never lets an agent
 * start its own adb server (a daemon inside an agent's PRoot would keep that session from
 * ending), and selects this phone so `adb shell` works without `-s`.
 */
internal fun adbWrapperScript(): String {
    val state = RuntimeInstaller.ADB_GUEST_STATE_DIR
    val adb = RuntimeInstaller.ADB_GUEST_BINARY
    return """
        |#!/bin/bash
        |# Installed by Mobile Harness: adb for this phone over Android's Wireless debugging.
        |# The app owns the adb server, the pairing and the connection.
        |state=$state
        |if [ ! -e "${'$'}state/agent-access" ]; then
        |  echo "adb: phone access for agents is off. Turn it on in Mobile Harness: Settings > Wireless ADB." >&2
        |  exit 1
        |fi
        |if ! (exec 3<>/dev/tcp/127.0.0.1/$ADB_SERVER_PORT) 2>/dev/null; then
        |  echo "adb: not connected to this phone. In Mobile Harness, open Settings > Wireless ADB and tap Connect." >&2
        |  exit 1
        |fi
        |if [ -z "${'$'}ANDROID_SERIAL" ] && [ -s "${'$'}state/serial" ]; then
        |  ANDROID_SERIAL="${'$'}(cat "${'$'}state/serial")"
        |  export ANDROID_SERIAL
        |fi
        |exec $adb "${'$'}@"
        |""".trimMargin()
}

/** What agents are told when the user lets them use adb on this phone. */
internal val ADB_AGENT_GUIDANCE = """
    |<phone_adb>
    |`adb` is connected to this phone itself through Android's Wireless debugging and runs as the Android shell user. The phone is already selected: do not pass -s, and do not run adb pair, connect, disconnect, start-server or kill-server.
    |Use it to install and open the apps you build (`adb install -r app.apk`, `adb shell am start -n package/.MainActivity`), read logs (`adb logcat -d`), take screenshots (`adb exec-out screencap -p > screen.png`), inspect the screen (`adb shell uiautomator dump /sdcard/ui.xml` then `adb pull /sdcard/ui.xml`) and tap or type (`adb shell input tap X Y`, `adb shell input text hello`).
    |Before uninstalling apps, changing system settings, granting or revoking permissions, or deleting anything outside the project, ask the user and wait for a yes.
    |If adb says it is not connected, tell the user to open Mobile Harness, Settings, Wireless ADB and tap Connect.
    |</phone_adb>
""".trimMargin()

/** The guidance block when agents may use adb, or null when phone access is off. */
internal fun adbAgentGuidance(context: Context): String? =
    ADB_AGENT_GUIDANCE.takeIf { WirelessAdbManager.get(context).isAgentAccessEnabled() }

/**
 * The device-side command that runs [com.jarves.mh.adb.ScreenPowerMain] from this app's own APK
 * as Android's shell user. Only "off", "on" and "hold" are accepted.
 */
internal fun screenPowerShellCommand(apkPath: String, mode: String): String {
    require(mode in setOf("off", "on", "hold")) { "Unknown screen power mode" }
    require(apkPath.startsWith("/") && '\'' !in apkPath && apkPath.none(Char::isWhitespace)) { "Unexpected APK path" }
    return "CLASSPATH='$apkPath' app_process /system/bin com.jarves.mh.adb.ScreenPowerMain $mode"
}

/** The serial adb gives a wireless-debugging connection to this phone itself. */
internal fun localAdbSerial(port: Int): String = "127.0.0.1:$port"

internal fun isValidAdbPort(port: Int?): Boolean = port != null && port in 1..65_535

/** Android's pairing dialog shows a six-digit code. */
internal fun normalizePairingCode(raw: String): String? =
    raw.filter(Char::isDigit).takeIf { it.length == 6 }

internal fun isAdbPairSuccess(output: String): Boolean =
    output.contains("Successfully paired", ignoreCase = true)

/** True for "connected to HOST:PORT" and "already connected to HOST:PORT". */
internal fun isAdbConnectSuccess(output: String): Boolean {
    val value = output.lowercase()
    return "connected to" in value && listOf("failed", "unable", "cannot", "refused").none { it in value }
}

/** `adb devices` rows as serial to state ("device", "offline", "unauthorized", ...). */
internal fun parseAdbDevices(output: String): Map<String, String> =
    output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) parts[0] to parts[1] else null
        }
        .toMap()

/** The last meaningful line of adb output, for error messages. */
internal fun adbFailureDetail(output: String): String =
    output.lineSequence().map(String::trim).lastOrNull { it.isNotEmpty() }?.take(200).orEmpty()

data class WirelessAdbState(
    /** The Linux environment adb runs in; adb cannot be installed before it. */
    val runtimeInstalled: Boolean = false,
    val installed: Boolean = false,
    val busy: Boolean = false,
    val installFraction: Float? = null,
    val connectedSerial: String? = null,
    val shellUid: String? = null,
    val agentAccess: Boolean = false,
    /** The display panel is powered off while the phone stays awake (see [ScreenPowerMain]). */
    val screenOff: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class WirelessAdbResult(val success: Boolean, val message: String)

/**
 * Connects this phone's own `adb` (inside the Linux environment) to Android's Wireless
 * debugging. The app keeps one `adb nodaemon server` running and drives pairing and
 * connecting through it; agents reach the same server through the [adbWrapperScript].
 */
class WirelessAdbManager private constructor(private val context: Context) {
    private val installer = RuntimeInstaller(context)
    private val prefs = context.getSharedPreferences("wireless_adb", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val serverLog = File(context.filesDir, "wireless-adb/server.log")
    private val workspace = File(context.filesDir, "workspaces/wireless-adb")
    @Volatile private var server: Process? = null
    @Volatile private var screenHold: Process? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var sleepReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WirelessAdbState())
    val state: StateFlow<WirelessAdbState> = _state.asStateFlow()

    private fun str(id: Int, vararg args: Any): String = AppStrings.get(context, id, *args)

    private fun stateDir(): File = File(installer.installedRuntime().rootfs, RuntimeInstaller.ADB_GUEST_STATE_DIR.removePrefix("/"))

    fun isAgentAccessEnabled(): Boolean = runCatching {
        installer.isWirelessAdbInstalled() && File(stateDir(), "agent-access").exists()
    }.getOrDefault(false)

    /** Reads the current state without starting anything. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val installed = installer.isWirelessAdbInstalled()
        var serial: String? = null
        if (installed && isServerListening()) {
            val saved = savedSerial()
            val devices = parseAdbDevices(runAdb(listOf("devices"), 10_000L).second)
            serial = saved?.takeIf { devices[it] == "device" }
        }
        val runtimeInstalled = runCatching { installer.isInstalled() }.getOrDefault(false)
        _state.update {
            it.copy(
                runtimeInstalled = runtimeInstalled,
                installed = installed,
                connectedSerial = serial,
                shellUid = it.shellUid.takeIf { serial != null },
                agentAccess = installed && isAgentAccessEnabled(),
            )
        }
    }

    suspend fun install(): WirelessAdbResult = exclusive {
        installer.ensureWirelessAdbInstalled { progress ->
            _state.update { it.copy(installFraction = progress.fraction, message = progress.message, isError = false) }
        }
        _state.update { it.copy(installed = true, installFraction = null) }
        WirelessAdbResult(true, str(R.string.rt_install_ready, "ADB"))
    }

    /**
     * Pairs with the code from Android's pairing dialog. Without a [port], the pairing
     * service is found over mDNS, which only works while that dialog is still open.
     * A successful pairing connects right away.
     */
    suspend fun pair(rawCode: String, port: Int? = null): WirelessAdbResult = exclusive {
        val code = normalizePairingCode(rawCode) ?: error(str(R.string.adb_invalid_code))
        requireInstalled()
        ensureServer()
        val pairingPort = port?.takeIf(::isValidAdbPort)
            ?: discoverOwnPort(ADB_PAIRING_SERVICE, 8_000L)
            ?: error(str(R.string.adb_pairing_port_not_found))
        val (_, output) = runAdb(listOf("pair", localAdbSerial(pairingPort), code), 30_000L)
        check(isAdbPairSuccess(output)) { str(R.string.adb_pair_failed, adbFailureDetail(output)) }
        connectLocked(null)
    }

    /** Connects to Wireless debugging, finding its port over mDNS when none is given. */
    suspend fun connect(port: Int? = null): WirelessAdbResult = exclusive {
        requireInstalled()
        ensureServer()
        connectLocked(port)
    }

    suspend fun disconnect(): WirelessAdbResult = exclusive {
        if (installer.isWirelessAdbInstalled() && isServerListening()) {
            runAdb(listOf("disconnect"), 10_000L)
        }
        runCatching { File(stateDir(), "serial").delete() }
        _state.update { it.copy(connectedSerial = null, shellUid = null) }
        WirelessAdbResult(true, str(R.string.adb_disconnected))
    }

    fun setAgentAccess(enabled: Boolean) {
        runCatching {
            val flag = File(stateDir().apply { mkdirs() }, "agent-access")
            if (enabled) flag.writeText("1\n") else flag.delete()
        }
        _state.update { it.copy(agentAccess = enabled && isAgentAccessEnabled()) }
    }

    /**
     * Best effort before an agent session: when agents may use adb, make sure the server is
     * up and this phone is connected, so `adb` works from the first command. Never throws.
     */
    suspend fun ensureReadyForAgents() {
        if (!isAgentAccessEnabled()) return
        withTimeoutOrNull(12_000L) {
            runCatching {
                val serial = savedSerial()
                val connected = isServerListening() && serial != null &&
                    parseAdbDevices(runAdb(listOf("devices"), 5_000L).second)[serial] == "device"
                if (connected) _state.update { it.copy(connectedSerial = serial) } else connect()
            }
        }
    }

    /**
     * Powers the display panel off for a long run, to protect an OLED screen from burn-in,
     * while the phone stays awake and unlocked so the agent can keep using adb, screenshots
     * and taps. A screen wake lock stops the screen timeout from sleeping (and locking) the
     * phone, and [ScreenPowerMain] `hold` keeps the panel off through rotations.
     */
    suspend fun turnScreenOff(): WirelessAdbResult = exclusive {
        requireInstalled()
        ensureServer()
        val serial = connectedSerialOrConnect()
        stopScreenHoldLocked(restore = false)
        val log = File(context.filesDir, "wireless-adb/screen.log").apply { parentFile?.mkdirs(); delete() }
        acquireScreenWakeLock()
        val hold = installer.process(
            installer.installedRuntime().proot,
            installer.installedRuntime().rootfs,
            workspace,
            adbEnvironment(),
            listOf(RuntimeInstaller.ADB_GUEST_BINARY, "-s", serial, "shell", screenPowerCommand("hold")),
            guestWorkspacePath = GUEST_WORKSPACE,
            outputFile = log,
        )
        screenHold = hold
        // Starting app_process with this APK on its class path takes a moment. Wait for the
        // first "holding" line, which comes only after the panel really turned off.
        var started = false
        for (attempt in 0 until 75) {
            if (runCatching { log.readTailText(4_096) }.getOrDefault("").contains("holding")) {
                started = true
                break
            }
            if (!hold.isAlive) break
            delay(200)
        }
        if (!started) {
            val detail = adbFailureDetail(runCatching { log.readTailText(4_096) }.getOrDefault(""))
            stopScreenHoldLocked(restore = true)
            error(str(R.string.adb_screen_off_failed, detail.ifBlank { "no response" }))
        }
        watchForRealSleep()
        scope.launch {
            // If the hold ends on its own (adb dropped, phone rebooted adbd), stop claiming it is off.
            runCatching { hold.waitFor() }
            if (screenHold === hold) endScreenOffAfterRun()
        }
        _state.update { it.copy(screenOff = true) }
        WirelessAdbResult(true, str(R.string.adb_screen_off_done))
    }

    /** Powers the display panel back on and lets the screen time out normally again. */
    suspend fun turnScreenOn(): WirelessAdbResult = exclusive {
        stopScreenHoldLocked(restore = true)
        WirelessAdbResult(true, str(R.string.adb_screen_on_done))
    }

    /** Fire-and-forget [turnScreenOff], for the running-task notification. */
    fun turnScreenOffInBackground() {
        scope.launch { turnScreenOff() }
    }

    /** Called when a run ends, is stopped, or its service goes away: never leave the panel dark. */
    fun endScreenOffAfterRun() {
        if (screenHold == null && screenWakeLock == null && !_state.value.screenOff) return
        scope.launch { turnScreenOn() }
    }

    private suspend fun connectedSerialOrConnect(): String {
        val saved = savedSerial()
        if (saved != null && parseAdbDevices(runAdb(listOf("devices"), 10_000L).second)[saved] == "device") return saved
        connectLocked(null)
        return savedSerial() ?: error(str(R.string.adb_connect_port_not_found))
    }

    private fun screenPowerCommand(mode: String): String =
        screenPowerShellCommand(context.applicationInfo.sourceDir, mode)

    /** Must run inside [exclusive]. */
    private fun stopScreenHoldLocked(restore: Boolean) {
        val hold = screenHold
        screenHold = null
        if (hold != null && hold.isAlive) hold.destroy()
        if (restore && installer.isWirelessAdbInstalled() && isServerListening()) {
            savedSerial()?.let { serial ->
                runCatching { runAdb(listOf("-s", serial, "shell", screenPowerCommand("on")), 20_000L) }
            }
        }
        sleepReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
        sleepReceiver = null
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
        _state.update { it.copy(screenOff = false) }
    }

    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        // A screen-level wake lock (not just a partial one) is what stops the screen timeout
        // from sleeping and locking the phone; the panel itself is off, so nothing is lit.
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
        screenWakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(flags, "com.jarves.mh:adb-screen-off")
            .apply {
                setReferenceCounted(false)
                acquire(SCREEN_OFF_MAX_MS)
            }
    }

    /**
     * Only a real sleep sends ACTION_SCREEN_OFF (powering the panel off does not). If the phone
     * goes to sleep, for example because you pressed the power button, end screen-off mode so
     * the next wake shows the screen normally instead of being turned dark again.
     */
    private fun watchForRealSleep() {
        if (sleepReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    scope.launch {
                        exclusive {
                            stopScreenHoldLocked(restore = false)
                            WirelessAdbResult(true, str(R.string.adb_screen_on_done))
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sleepReceiver = receiver
    }

    private suspend fun connectLocked(port: Int?): WirelessAdbResult {
        val connectPort = port?.takeIf(::isValidAdbPort)
            ?: discoverOwnPort(ADB_CONNECT_SERVICE, 6_000L)
            ?: prefs.getInt(KEY_LAST_PORT, 0).takeIf(::isValidAdbPort)
            ?: error(str(R.string.adb_connect_port_not_found))
        val serial = localAdbSerial(connectPort)
        val (_, output) = runAdb(listOf("connect", serial), 20_000L)
        check(isAdbConnectSuccess(output)) { str(R.string.adb_connect_failed, adbFailureDetail(output)) }
        val (exit, uidOutput) = runAdb(listOf("-s", serial, "shell", "id", "-u"), 15_000L)
        val uid = uidOutput.lineSequence().map(String::trim).lastOrNull { it.isNotEmpty() }
        check(exit == 0 && uid != null && uid.all(Char::isDigit)) { str(R.string.adb_connect_failed, adbFailureDetail(uidOutput)) }
        prefs.edit().putInt(KEY_LAST_PORT, connectPort).apply()
        File(stateDir().apply { mkdirs() }, "serial").writeText("$serial\n")
        _state.update { it.copy(connectedSerial = serial, shellUid = uid) }
        return WirelessAdbResult(true, str(R.string.adb_connected_as, serial, uid))
    }

    private fun savedSerial(): String? = runCatching {
        File(stateDir(), "serial").readText().trim().takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun requireInstalled() {
        check(installer.isWirelessAdbInstalled()) { str(R.string.adb_install_first) }
    }

    /** Runs one operation at a time and reports its outcome in [state]; failures become results. */
    private suspend fun exclusive(block: suspend () -> WirelessAdbResult): WirelessAdbResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(busy = true, message = null, isError = false) }
                val result = runCatching { block() }.getOrElse { error ->
                    WirelessAdbResult(false, error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
                }
                _state.update {
                    it.copy(busy = false, installFraction = null, message = result.message, isError = !result.success)
                }
                result
            }
        }

    private fun isServerListening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", ADB_SERVER_PORT), 400) }
        true
    }.getOrDefault(false)

    /** Starts the app-owned `adb nodaemon server` unless one is already listening. */
    private suspend fun ensureServer() {
        if (isServerListening()) return
        server?.takeIf { it.isAlive }?.destroy()
        val runtime = installer.installedRuntime()
        serverLog.parentFile?.mkdirs()
        server = installer.process(
            runtime.proot,
            runtime.rootfs,
            workspace,
            adbEnvironment(),
            listOf(RuntimeInstaller.ADB_GUEST_BINARY, "nodaemon", "server"),
            guestWorkspacePath = GUEST_WORKSPACE,
            outputFile = serverLog,
        )
        for (attempt in 0 until 40) {
            if (isServerListening()) return
            if (server?.isAlive == false) break
            delay(250)
        }
        val detail = runCatching { serverLog.readTailText(600) }.getOrDefault("").trim()
        error(str(R.string.adb_server_failed, adbFailureDetail(detail)))
    }

    /** Runs the real adb client against the app-owned server and returns (exit, output). */
    private fun runAdb(arguments: List<String>, timeoutMs: Long): Pair<Int, String> {
        val runtime = installer.installedRuntime()
        val outputFile = File(context.cacheDir, "wireless-adb-${System.nanoTime()}.log")
        return try {
            val process = installer.process(
                runtime.proot,
                runtime.rootfs,
                workspace,
                adbEnvironment(),
                listOf(RuntimeInstaller.ADB_GUEST_BINARY) + arguments,
                guestWorkspacePath = GUEST_WORKSPACE,
                outputFile = outputFile,
            )
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                -1 to "timed out after ${timeoutMs / 1_000} s"
            } else {
                process.exitValue() to outputFile.readTailText(16 * 1024).trim()
            }
        } finally {
            outputFile.delete()
        }
    }

    /** The app finds ports with Android's own mDNS, so adb's built-in discovery stays off. */
    private fun adbEnvironment(): Map<String, String> = mapOf("ADB_MDNS" to "0")

    /** Finds a wireless-debugging service advertised by this phone (not another device on the Wi-Fi). */
    private suspend fun discoverOwnPort(serviceType: String, timeoutMs: Long): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeoutMs) {
            runCatching { discover(serviceType) }.getOrNull()
        }
    }

    private suspend fun discover(serviceType: String): Int = suspendCancellableCoroutine { continuation ->
        val nsd = context.getSystemService(NsdManager::class.java)
        val own = ownAddressKeys()
        val resolving = AtomicBoolean(false)
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        val stopped = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener
        fun stop() {
            if (stopped.compareAndSet(false, true)) runCatching { nsd.stopServiceDiscovery(listener) }
        }
        fun resolveNext() {
            if (!continuation.isActive || !resolving.compareAndSet(false, true)) return
            val next = pending.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolving.set(false)
                    @Suppress("DEPRECATION")
                    val host = serviceInfo.host
                    if (host != null && addressKey(host) in own && isValidAdbPort(serviceInfo.port)) {
                        stop()
                        if (continuation.isActive) continuation.resume(serviceInfo.port)
                    } else {
                        resolveNext()
                    }
                }
            })
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopped.set(true)
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("mDNS discovery failed ($errorCode)"))
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                pending.add(serviceInfo)
                resolveNext()
            }
        }
        continuation.invokeOnCancellation { stop() }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun ownAddressKeys(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .map(::addressKey)
            .toSet()
    }.getOrDefault(emptySet())

    private fun addressKey(address: InetAddress): String = address.address.joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_LAST_PORT = "last_connect_port"
        /** Upper bound so a forgotten screen-off cannot keep the phone awake indefinitely. */
        private const val SCREEN_OFF_MAX_MS = 24L * 60 * 60 * 1_000
        private const val GUEST_WORKSPACE = "/workspace/wireless-adb"

        @Volatile private var instance: WirelessAdbManager? = null

        fun get(context: Context): WirelessAdbManager =
            instance ?: synchronized(this) {
                instance ?: WirelessAdbManager(context.applicationContext).also { instance = it }
            }
    }
}
