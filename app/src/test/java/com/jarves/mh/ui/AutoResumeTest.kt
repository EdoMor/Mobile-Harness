package com.jarves.mh.ui

import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RecoveryFailureInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumeTest {

    @Test
    fun turnLimitIsRecognisedFromClaudeCodesReason() {
        assertTrue(isTurnLimitFailure("Reached maximum number of turns (100)"))
        assertTrue(isTurnLimitFailure("reached MAXIMUM number of turns"))
        assertFalse(isTurnLimitFailure("Claude Code reported an error"))
        assertEquals(AutoResumeKind.TURN_LIMIT, autoResumeKind("Reached maximum number of turns (100)"))
    }

    @Test
    fun temporaryProviderFailuresResumeLikeTransportDrops() {
        listOf(
            "Connection error.",
            "API Error: 529 {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
            "API Error: 503 Service Unavailable",
            "API Error: 500 Internal server error",
            "Request timed out.",
            "socket hang up",
            "TypeError: fetch failed",
        ).forEach { assertEquals(it, AutoResumeKind.TRANSPORT, autoResumeKind(it)) }
    }

    @Test
    fun credentialQuotaAndRateLimitFailuresNeverAutoResume() {
        listOf(
            "The provider rejected the saved API key.",
            "API Error: 401 authentication_error",
            "API Error: 429 rate limit exceeded",
            "API Error: 529 overloaded, quota exceeded",
            "Claude Code reported an error",
            "Stopped by user",
        ).forEach { assertNull(it, autoResumeKind(it)) }
    }

    @Test
    fun turnLimitHandlingStillRequiresRecoveryEvidence() {
        val evidence = RecoveryFailureInfo(retryAvailable = true, sideEffectsPossible = true, toolsExecuted = true)
        assertTrue(shouldHandleTransportFailure("Reached maximum number of turns (100)", evidence))
        assertFalse(shouldHandleTransportFailure("Reached maximum number of turns (100)", null))
    }

    @Test
    fun turnLimitContinuationsAreBudgeted() {
        (0 until TURN_LIMIT_MAX_CONTINUATIONS).forEach { assertEquals(2_000L, turnLimitContinuationDelayMillis(it)) }
        assertNull(turnLimitContinuationDelayMillis(TURN_LIMIT_MAX_CONTINUATIONS))
        assertNull(turnLimitContinuationDelayMillis(-1))
    }

    @Test
    fun continuationBannerCountsAgainstTheContinuationBudget() {
        val info = RecoveryFailureInfo(retryAvailable = false, sideEffectsPossible = true, toolsExecuted = true)
        val state = recoveryUiStateForFallbackAttempt(
            RecoveryDecision.RESUME_PERSISTED,
            attempt = 2,
            reason = "Reached maximum number of turns (100)",
            recovery = info,
            maxAttempts = TURN_LIMIT_MAX_CONTINUATIONS,
        )
        assertEquals(TURN_LIMIT_MAX_CONTINUATIONS, state.maxAttempts)
        assertEquals(RecoveryBannerMode.RECONNECTING, recoveryBannerMode(state))
    }
}
