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
    fun pulseScreenOpensWithItsTokenAndOutcome() {
        assertEquals(
            "am start -n com.jarves.mh/com.jarves.mh.ui.RunFinishedPulseActivity " +
                "--es token 3f0c9d3e-1b7a-4c55-9a51-7f3c2b8e6d10 --ez failed true",
            pulseActivityShellCommand("com.jarves.mh", "3f0c9d3e-1b7a-4c55-9a51-7f3c2b8e6d10", failed = true),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pulseScreenRejectsATokenThatCouldInjectCommands() {
        pulseActivityShellCommand("com.jarves.mh", "abc; input keyevent 26", failed = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pulseScreenRejectsAnOddPackageName() {
        pulseActivityShellCommand("com.jarves.mh;rm", "3f0c9d3e-1b7a-4c55", failed = false)
    }

    @Test
    fun lockUsesThePowerKey() {
        assertEquals("input keyevent KEYCODE_POWER", LOCK_PHONE_COMMAND)
    }

    @Test
    fun helperFailureSkipsTheHeartbeat() {
        assertEquals(
            "screen power failed: java.lang.NoSuchMethodException",
            screenHelperFailureDetail("holding\nholding\nscreen power failed: java.lang.NoSuchMethodException\n", 1),
        )
        assertEquals("the screen-off helper stopped (exit 143)", screenHelperFailureDetail("holding\nholding\n", 143))
        assertEquals("no response", screenHelperFailureDetail("", null))
    }

    @Test
    fun helperFailureIgnoresDisplayEventLines() {
        // The helper now logs display events; those are not the reason it stopped.
        val log = "0.0s panel off on Google Pixel 8, Android 15 (API 35)\nholding\n" +
            "2.1s display 0 changed state=2 mode=2 refresh=60.0\n2.4s panel forced off again after a display change\nholding\n"
        assertEquals("the screen-off helper stopped (exit 0)", screenHelperFailureDetail(log, 0))
        val withWarning = "0.1s cannot watch display changes: java.lang.NoSuchMethodException: registerDisplayListener\n"
        assertEquals("the screen-off helper stopped (exit 0)", screenHelperFailureDetail(withWarning + log, 0))
        assertEquals("error: closed", screenHelperFailureDetail(log + "error: closed\n", 1))
        assertEquals(
            "screen power failed: java.lang.UnsatisfiedLinkError: Shared library already opened",
            screenHelperFailureDetail(log + "screen power failed: java.lang.UnsatisfiedLinkError: Shared library already opened\n", 1),
        )
    }

    @Test
    fun screenOffReportCollapsesTheHeartbeat() {
        val report = screenOffReportText(
            listOf("Mobile Harness screen-off report", "App 1.0.10, Google Pixel 8, Android 15 (API 35)", ""),
            "0.0s panel off on Google Pixel 8, Android 15 (API 35)\nholding\nholding\nholding\n" +
                "3.2s display 0 changed state=2 refresh=60.0\nholding\n",
        )
        assertEquals(
            listOf(
                "Mobile Harness screen-off report",
                "App 1.0.10, Google Pixel 8, Android 15 (API 35)",
                "",
                "0.0s panel off on Google Pixel 8, Android 15 (API 35)",
                "(holding x3)",
                "3.2s display 0 changed state=2 refresh=60.0",
                "(holding x1)",
            ).joinToString("\n"),
            report,
        )
    }

    @Test
    fun runEndWaitsLongerThanAnyAutomaticResume() {
        // Turn-limit resumes start after 2 s; network retries after 5 s, then 15 s.
        assertTrue(WirelessAdbManager.RUN_END_GRACE_MS > 15_000L)
    }

    @Test
    fun agentGuidanceForbidsTouchingTheConnectionAndAsksBeforeRiskyChanges() {
        assertTrue(ADB_AGENT_GUIDANCE.startsWith("<phone_adb>"))
        assertTrue(ADB_AGENT_GUIDANCE.endsWith("</phone_adb>"))
        assertTrue("kill-server" in ADB_AGENT_GUIDANCE)
        assertTrue("ask the user" in ADB_AGENT_GUIDANCE)
    }
}
