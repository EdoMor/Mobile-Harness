package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for Wireless ADB parsing and the agent-facing adb wrapper (adb 35.0.2 output). */
class WirelessAdbTest {

    @Test
    fun pairingCodeIsSixDigits() {
        assertEquals("123456", normalizePairingCode("123456"))
        assertEquals("123456", normalizePairingCode(" 123 456 "))
        assertNull(normalizePairingCode("12345"))
        assertNull(normalizePairingCode("1234567"))
        assertNull(normalizePairingCode(""))
    }

    @Test
    fun portsMustBeInRange() {
        assertTrue(isValidAdbPort(37_123))
        assertFalse(isValidAdbPort(0))
        assertFalse(isValidAdbPort(70_000))
        assertFalse(isValidAdbPort(null))
    }

    @Test
    fun recognisesPairingResults() {
        assertTrue(isAdbPairSuccess("Successfully paired to 127.0.0.1:37123 [guid=adb-R5CT-XYZ]"))
        assertFalse(isAdbPairSuccess("Failed: Wrong password or connection was dropped."))
    }

    @Test
    fun recognisesConnectResults() {
        assertTrue(isAdbConnectSuccess("connected to 127.0.0.1:41235"))
        assertTrue(isAdbConnectSuccess("already connected to 127.0.0.1:41235"))
        assertFalse(isAdbConnectSuccess("failed to connect to '127.0.0.1:41235': Connection refused"))
        assertFalse(isAdbConnectSuccess("failed to authenticate to 127.0.0.1:41235"))
        assertFalse(isAdbConnectSuccess("cannot connect to daemon"))
    }

    @Test
    fun parsesDeviceList() {
        val devices = parseAdbDevices(
            """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            127.0.0.1:41235	device
            emulator-5554	offline
            """.trimIndent(),
        )
        assertEquals(mapOf("127.0.0.1:41235" to "device", "emulator-5554" to "offline"), devices)
        assertEquals(emptyMap<String, String>(), parseAdbDevices("List of devices attached\n"))
    }

    @Test
    fun failureDetailIsTheLastLine() {
        assertEquals("Connection refused", adbFailureDetail("trying\n\nConnection refused\n  \n"))
        assertEquals("", adbFailureDetail(""))
    }

    @Test
    fun serialPointsAtThisPhone() {
        assertEquals("127.0.0.1:41235", localAdbSerial(41_235))
    }

    @Test
    fun wrapperGatesAccessNeverStartsAServerAndSelectsThisPhone() {
        val expected = """
            #!/bin/bash
            # Installed by Mobile Harness: adb for this phone over Android's Wireless debugging.
            # The app owns the adb server, the pairing and the connection.
            state=/root/.pocket-adb
            if [ ! -e "${'$'}state/agent-access" ]; then
              echo "adb: phone access for agents is off. Turn it on in Mobile Harness: Settings > Wireless ADB." >&2
              exit 1
            fi
            if ! (exec 3<>/dev/tcp/127.0.0.1/5037) 2>/dev/null; then
              echo "adb: not connected to this phone. In Mobile Harness, open Settings > Wireless ADB and tap Connect." >&2
              exit 1
            fi
            if [ -z "${'$'}ANDROID_SERIAL" ] && [ -s "${'$'}state/serial" ]; then
              ANDROID_SERIAL="${'$'}(cat "${'$'}state/serial")"
              export ANDROID_SERIAL
            fi
            exec /opt/pocket-adb/adb "${'$'}@"

        """.trimIndent()
        assertEquals(expected, adbWrapperScript())
    }

    @Test
    fun screenPowerRunsThisApksEntryPointAsTheShellUser() {
        val apk = "/data/app/~~kQ1Zr7==/com.jarves.mh-Xy9_A==/base.apk"
        assertEquals(
            "CLASSPATH='$apk' app_process /system/bin com.jarves.mh.adb.ScreenPowerMain hold",
            screenPowerShellCommand(apk, "hold"),
        )
        assertTrue(screenPowerShellCommand(apk, "on").endsWith("ScreenPowerMain on"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun screenPowerRejectsUnknownModes() {
        screenPowerShellCommand("/data/app/base.apk", "off; reboot")
    }

    @Test(expected = IllegalArgumentException::class)
    fun screenPowerRejectsPathsThatCouldBreakQuoting() {
        screenPowerShellCommand("/data/app/it's/base.apk", "on")
    }

    @Test
    fun agentGuidanceForbidsTouchingTheConnectionAndAsksBeforeRiskyChanges() {
        assertTrue(ADB_AGENT_GUIDANCE.startsWith("<phone_adb>"))
        assertTrue(ADB_AGENT_GUIDANCE.endsWith("</phone_adb>"))
        assertTrue("kill-server" in ADB_AGENT_GUIDANCE)
        assertTrue("ask the user" in ADB_AGENT_GUIDANCE)
    }
}
