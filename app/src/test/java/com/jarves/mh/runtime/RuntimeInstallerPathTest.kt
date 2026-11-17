package com.jarves.mh.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInstallerPathTest {
    @Test
    fun githubCliDirectoryIsAvailableInEveryGuestSession() {
        assertTrue(guestRuntimePath(androidReady = false).split(':').contains("/root/.local/bin"))
    }

    @Test
    fun androidToolPathsKeepGithubCliAvailable() {
        val path = guestRuntimePath(androidReady = true).split(':')

        assertTrue(path.contains("/root/.local/bin"))
        assertTrue(path.contains("/root/android-sdk/cmdline-tools/latest/bin"))
        assertTrue(path.contains("/opt/gradle/gradle-8.14.3/bin"))
    }
}
