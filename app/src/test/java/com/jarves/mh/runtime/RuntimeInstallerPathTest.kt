package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInstallerPathTest {
    @Test
    fun githubCliDirectoryIsAvailableInEveryGuestSession() {
        val path = guestRuntimePath(androidReady = false).split(':')

        assertEquals("/root/.local/bin", path.first())
        assertTrue(path.contains("/usr/local/bin"))
        assertTrue(path.contains("/usr/bin"))
    }

    @Test
    fun androidToolPathsKeepGithubCliAvailable() {
        val path = guestRuntimePath(androidReady = true).split(':')

        assertEquals("/opt/gradle/gradle-8.14.3/bin", path.first())
        assertTrue(path.contains("/root/.local/bin"))
        assertTrue(path.contains("/root/android-sdk/cmdline-tools/latest/bin"))
        assertTrue(path.contains("/usr/local/bin"))
    }

    @Test
    fun androidToolsAreNotInjectedIntoBasicGuestSessions() {
        val path = guestRuntimePath(androidReady = false)

        assertFalse(path.contains("/root/android-sdk"))
        assertFalse(path.contains("/opt/gradle"))
    }
}
