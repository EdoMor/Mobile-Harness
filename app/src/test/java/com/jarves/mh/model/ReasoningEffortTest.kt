package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortTest {
    @Test
    fun normalizesKnownLevelsInEscalationOrder() {
        assertEquals(
            listOf("low", "high", "max"),
            normalizedReasoningEfforts(listOf("max", "none", "HIGH", "low", "high")),
        )
    }

    @Test
    fun keepsAValidCurrentLevelAndFallsBackToHigh() {
        assertEquals("max", preferredReasoningEffort(listOf("low", "high", "max"), "max"))
        assertEquals("high", preferredReasoningEffort(listOf("low", "high", "max"), "medium"))
        assertEquals("high", preferredReasoningEffort(listOf("high", "max"), ""))
        assertEquals("max", preferredReasoningEffort(listOf("max"), ""))
        assertEquals("", preferredReasoningEffort(emptyList(), "high"))
    }

    @Test
    fun onlyOpenCodeGoExposesTheFeature() {
        assertTrue(providerSupportsReasoningEffort(ProviderKind.OPENCODE_GO))
        assertFalse(providerSupportsReasoningEffort(ProviderKind.ANTHROPIC))
        assertFalse(providerSupportsReasoningEffort(ProviderKind.OPENCODE_ZEN))
        assertFalse(providerSupportsReasoningEffort(ProviderKind.CUSTOM))
    }
}
