package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the helpers behind Claude's error reporting and automatic resume.
 * The result payloads mirror what the bundled Claude Code 2.1.316 prints with
 * `--output-format stream-json`.
 */
class ClaudeAutoResumeTest {

    @Test
    fun maxTurnsResultShowsTheReasonFromErrors() {
        val result = JSONObject(
            """{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":101,
               "errors":["Reached maximum number of turns (100)"]}""",
        )
        assertEquals("Reached maximum number of turns (100)", claudeResultErrorMessage(result))
    }

    @Test
    fun executionErrorJoinsEveryReason() {
        val result = JSONObject(
            """{"type":"result","subtype":"error_during_execution","is_error":true,
               "errors":["API Error: 529 overloaded", "  ", "Request aborted"]}""",
        )
        assertEquals("API Error: 529 overloaded; Request aborted", claudeResultErrorMessage(result))
    }

    @Test
    fun resultTextWinsWhenPresent() {
        val result = JSONObject("""{"type":"result","subtype":"success","is_error":true,"result":"API Error: 401"}""")
        assertEquals("API Error: 401", claudeResultErrorMessage(result))
    }

    @Test
    fun subtypeIsTheFallbackWithoutErrors() {
        val maxTurns = JSONObject("""{"type":"result","subtype":"error_max_turns","is_error":true}""")
        assertEquals("Reached maximum number of turns", claudeResultErrorMessage(maxTurns))
        val unknown = JSONObject("""{"type":"result","subtype":"error_something_new","is_error":true}""")
        assertEquals("Claude Code reported an error", claudeResultErrorMessage(unknown))
    }

    @Test
    fun freshTaskNamesTheConversationAndResumeReloadsIt() {
        val id = "3f0c9d3e-1b7a-4c55-9a51-7f3c2b8e6d10"
        assertEquals(listOf("--session-id", id), claudeConversationArgs(id, resuming = false))
        assertEquals(listOf("--resume", id), claudeConversationArgs(id, resuming = true))
    }

    @Test
    fun perRunTurnLimitIsWellAboveTheOldLimit() {
        assertTrue(CLAUDE_MAX_TURNS_PER_RUN >= 100)
    }
}
