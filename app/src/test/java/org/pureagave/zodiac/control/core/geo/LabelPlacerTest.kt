package org.pureagave.zodiac.control.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacerTest {
    private fun placer() = LabelPlacer(gapPx = 2f).apply { reset(1000f, 1000f) }

    @Test
    fun first_claimant_keeps_the_spot_and_an_overlapping_label_drops_out() {
        val p = placer()
        assertTrue(p.tryPlace(100f, 100f, 200f, 120f))
        assertFalse(p.tryPlace(150f, 110f, 250f, 130f))
        assertEquals(1, p.placedCount)
    }

    @Test
    fun disjoint_labels_both_place() {
        val p = placer()
        assertTrue(p.tryPlace(100f, 100f, 200f, 120f))
        assertTrue(p.tryPlace(100f, 200f, 200f, 220f))
    }

    @Test
    fun the_gap_keeps_touching_labels_apart() {
        val p = placer()
        assertTrue(p.tryPlace(100f, 100f, 200f, 120f))
        // 3 px below: inside the 2 + 2 px of combined gap.
        assertFalse(p.tryPlace(100f, 123f, 200f, 143f))
        // 5 px below: clear.
        assertTrue(p.tryPlace(100f, 125f, 200f, 145f))
    }

    @Test
    fun an_offscreen_label_is_rejected_without_claiming_space() {
        val p = placer()
        assertFalse(p.tryPlace(-300f, 100f, -200f, 120f))
        assertFalse(p.tryPlace(1100f, 100f, 1200f, 120f))
        assertEquals(0, p.placedCount)
    }

    @Test
    fun reset_frees_every_spot() {
        val p = placer()
        assertTrue(p.tryPlace(100f, 100f, 200f, 120f))
        p.reset(1000f, 1000f)
        assertTrue(p.tryPlace(100f, 100f, 200f, 120f))
    }

    @Test
    fun grows_past_its_initial_capacity() {
        val p = LabelPlacer(gapPx = 2f).apply { reset(1000f, 5000f) }
        for (i in 0 until 200) assertTrue(p.tryPlace(0f, i * 10f, 10f, i * 10f + 4f))
        assertEquals(200, p.placedCount)
    }
}
