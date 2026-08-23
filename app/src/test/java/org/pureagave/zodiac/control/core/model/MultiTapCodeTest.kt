package org.pureagave.zodiac.control.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTapCodeTest {
    private fun code() = MultiTapCode(requiredTaps = 5, windowMs = 1_000L)

    @Test
    fun five_in_window_taps_fires_on_exactly_the_fifth() {
        val c = code()
        assertFalse(c.tap(0))
        assertFalse(c.tap(100))
        assertFalse(c.tap(200))
        assertFalse(c.tap(300))
        assertTrue("the fifth in-window tap must complete the code", c.tap(400))
    }

    @Test
    fun a_late_tap_restarts_the_count_at_one() {
        val c = code()
        assertFalse(c.tap(0))
        assertFalse(c.tap(100))
        assertFalse(c.tap(200))
        // > 1000 ms after the previous tap (200): stale, restarts at 1 (this
        // tap itself counts), so 4 more are needed, not 1.
        assertFalse(c.tap(1_500))
        assertFalse(c.tap(1_600))
        assertFalse(c.tap(1_700))
        assertFalse(c.tap(1_800))
        assertTrue("the fifth tap since the restart completes it", c.tap(1_900))
    }

    @Test
    fun after_firing_the_count_resets_and_needs_five_more() {
        val c = code()
        repeat(4) { i -> assertFalse(c.tap(i * 100L)) }
        assertTrue(c.tap(400))

        // Immediately after firing, a single tap must not re-fire.
        assertFalse(c.tap(500))
        assertFalse(c.tap(600))
        assertFalse(c.tap(700))
        assertFalse(c.tap(800))
        assertTrue("a fresh 5-tap run after a completion must fire again", c.tap(900))
    }

    @Test
    fun a_four_tap_sequence_never_fires() {
        // Kills a mutant that weakens the completion guard from
        // `count >= requiredTaps` to `count >= requiredTaps - 1`.
        val c = code()
        assertFalse(c.tap(0))
        assertFalse(c.tap(100))
        assertFalse(c.tap(200))
        assertFalse("four taps must never complete a 5-tap code", c.tap(300))
    }

    @Test
    fun a_tap_exactly_at_the_window_boundary_still_counts() {
        // The gap must be evaluated as "> windowMs is stale", not ">=" — a tap
        // landing exactly on the boundary is the positive control for that.
        val c = code()
        assertFalse(c.tap(0))
        assertFalse(c.tap(1_000))
        assertFalse(c.tap(2_000))
        assertFalse(c.tap(3_000))
        assertTrue(c.tap(4_000))
    }
}
