package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approved pulse, as pure math: same layout every time, a lit wavefront, nothing left behind. */
class PulseMathTest {
    private val field = PulseMath.field(1_080f, 2_340f)

    @Test
    fun layoutIsDeterministicAndCoversTheScreen() {
        val again = PulseMath.field(1_080f, 2_340f)
        assertEquals(field.dots, again.dots)
        assertTrue("expected a dense field, got ${field.dots.size}", field.dots.size > 800)
        val margin = field.spacing
        assertTrue(field.dots.all { it.x in -margin..1_080f + margin && it.y in -margin..2_340f + margin })
        // Dots reach the corners, so the wave visibly crosses the whole screen.
        assertTrue(field.dots.maxOf { it.r } > field.maxR * 0.95f)
    }

    @Test
    fun theWavefrontIsLitAndTheRestIsDark() {
        val elapsed = (PulseMath.PULSE_MS * 0.4).toLong()
        val front = PulseMath.easeOut(0.4f) * field.maxR * 1.08f
        val atFront = PulseMath.intensity(front, listOf(elapsed), field.spacing, field.maxR)
        val farAhead = PulseMath.intensity(front + field.spacing * 8, listOf(elapsed), field.spacing, field.maxR)
        assertTrue("front should be bright, was $atFront", atFront > 0.4f)
        assertTrue("ahead of the wave should be dark, was $farAhead", farAhead < 0.02f)
    }

    @Test
    fun aFinishedPulseLeavesNothingLit() {
        val r = field.maxR * 0.5f
        assertEquals(0f, PulseMath.intensity(r, listOf(PulseMath.PULSE_MS + 1), field.spacing, field.maxR), 0f)
        assertEquals(0f, PulseMath.intensity(r, listOf(-1L), field.spacing, field.maxR), 0f)
        assertEquals(0f, PulseMath.intensity(r, emptyList(), field.spacing, field.maxR), 0f)
    }

    @Test
    fun overlappingWavesNeverExceedFullBrightness() {
        val r = field.spacing * 2
        val value = PulseMath.intensity(r, listOf(60L, 60L, 60L), field.spacing, field.maxR)
        assertTrue(value <= 1f)
    }

    @Test
    fun theCenterBloomsBrieflyAsEachWaveLeaves() {
        val start = PulseMath.bloom(0L, field.spacing)
        assertNotNull(start)
        assertEquals(0.95f, start!!.second, 0.001f)
        assertNull(PulseMath.bloom(600L, field.spacing))
    }

    @Test
    fun dotsScaleWithTheSpacingNotThePixelDensity() {
        val small = PulseMath.field(540f, 1_170f)
        val ratioDim = PulseMath.dotRadius(0f, field.spacing) / field.spacing
        val ratioSmall = PulseMath.dotRadius(0f, small.spacing) / small.spacing
        assertEquals(ratioDim, ratioSmall, 0.0001f)
        assertTrue(PulseMath.dotRadius(1f, field.spacing) > PulseMath.dotRadius(0f, field.spacing) * 4)
    }

    @Test
    fun timingMatchesTheApprovedSequence() {
        // Pulse, pulse; one minute of black; pulse, pulse; lock.
        assertEquals(1_800L, PulseMath.PULSE_MS)
        assertEquals(900L, PulseMath.PAIR_GAP_MS)
        assertEquals(60_000L, PulseMath.WAIT_MS)
        val total = PulseMath.PAIR_MS * 2 + PulseMath.WAIT_MS + PulseMath.LOCK_DELAY_MS
        assertEquals(65_900L, total)
    }
}
