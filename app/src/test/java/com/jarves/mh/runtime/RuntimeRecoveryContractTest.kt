package com.jarves.mh.runtime

import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RuntimeRecoveryContext
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeRecoveryContractTest {

    @Test
    fun deepSeekHarnessCannotResumePersistedButReportsToolActivity() {
        assertEquals(false, DSH_RECOVERY_PROFILE.canResumePersisted)
        assertEquals(true, DSH_RECOVERY_PROFILE.reportsToolActivity)
    }

    @Test
    fun claudeReportsToolActivity() {
        assertEquals(true, CLAUDE_RECOVERY_PROFILE.reportsToolActivity)
    }

    @Test
    fun antigravityCanResumePersisted() {
        assertEquals(true, ANTIGRAVITY_RECOVERY_PROFILE.canResumePersisted)
    }

    @Test
    fun claudeCanResumePersistedAndReportsInterruptedTools() {
        assertEquals(true, CLAUDE_RECOVERY_PROFILE.canResumePersisted)
        assertEquals(true, CLAUDE_RECOVERY_PROFILE.reportsInterruptedToolOutcome)
    }

    @Test
    fun claudeWithoutTranscriptRequiresManualAfterTools() {
        // No persisted conversation to reload, and tools ran: only a manual retry is safe.
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(CLAUDE_RECOVERY_PROFILE, context(tools = true)),
        )
    }

    @Test
    fun claudeResumesTranscriptAfterToolsWhenNoToolWasCutOff() {
        // e.g. the turn limit: every tool call has its result in the transcript.
        assertEquals(
            RecoveryDecision.RESUME_PERSISTED,
            RuntimeRecoveryPolicy.decide(
                CLAUDE_RECOVERY_PROFILE,
                context(handle = "conversation-1", tools = true, outcomeKnown = true),
            ),
        )
    }

    @Test
    fun claudeRequiresManualWhenAToolWasCutOffMidRun() {
        // The process died between a tool call and its result: the side effect may or
        // may not have happened, so resuming automatically could repeat it.
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(
                CLAUDE_RECOVERY_PROFILE,
                context(handle = "conversation-1", tools = true, outcomeKnown = false),
            ),
        )
    }

    @Test
    fun claudeResumesTranscriptBeforeTools() {
        assertEquals(
            RecoveryDecision.RESUME_PERSISTED,
            RuntimeRecoveryPolicy.decide(CLAUDE_RECOVERY_PROFILE, context(handle = "conversation-1")),
        )
    }

    @Test
    fun claudeRetriesNewSessionBeforeTools() {
        assertEquals(
            RecoveryDecision.RETRY_NEW_SESSION_SAFE,
            RuntimeRecoveryPolicy.decide(CLAUDE_RECOVERY_PROFILE, context(tools = false)),
        )
    }

    @Test
    fun antigravityResumesPersistedConversationBeforeTools() {
        assertEquals(
            RecoveryDecision.RESUME_PERSISTED,
            RuntimeRecoveryPolicy.decide(ANTIGRAVITY_RECOVERY_PROFILE, context(handle = "conversation-1")),
        )
    }

    @Test
    fun antigravityRequiresManualAfterToolsBecauseOutcomeIsUnknown() {
        // ANTIGRAVITY_RECOVERY_PROFILE.reportsInterruptedToolOutcome is false, so a
        // resume after tools could silently repeat or drop a half-finished side effect.
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(
                ANTIGRAVITY_RECOVERY_PROFILE,
                context(handle = "conversation-1", tools = true),
            ),
        )
    }

    @Test
    fun antigravityWithoutConversationIdRequiresManualAfterTools() {
        assertEquals(
            RecoveryDecision.MANUAL_RETRY_REQUIRED,
            RuntimeRecoveryPolicy.decide(ANTIGRAVITY_RECOVERY_PROFILE, context(handle = null, tools = true)),
        )
    }

    @Test
    fun autoRestartRequiresAContextThatProvesNoToolsRan() {
        // Fail-closed: a null context means the bridge cannot prove which tools
        // ran, so an automatic restart must be declined.
        assertEquals(false, canAutoRestartWithoutTools(null))
        assertEquals(false, canAutoRestartWithoutTools(context(tools = true)))
        assertEquals(true, canAutoRestartWithoutTools(context(tools = false)))
    }

    private fun context(tools: Boolean = false, handle: String? = null, outcomeKnown: Boolean = false) = RuntimeRecoveryContext(
        sessionId = "session-1",
        inPlaceContinuationAvailable = false,
        persistedHandle = handle,
        toolsExecuted = tools,
        mutatingToolsExecuted = tools,
        interruptedToolOutcomeKnown = outcomeKnown,
        checkpointAvailable = false,
        userStopped = false,
    )
}
