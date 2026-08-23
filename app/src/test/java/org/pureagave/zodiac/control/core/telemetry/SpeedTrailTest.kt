package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 1e-4f

class SpeedTrailTest {
    @Test
    fun window_is_always_exactly_180_slots() {
        assertEquals(SpeedTrail.WINDOW_BUCKETS, SpeedTrail.EMPTY.window(0L).size)
        assertEquals(SpeedTrail.WINDOW_BUCKETS, SpeedTrail.EMPTY.record(0L, 40.0).window(0L).size)

        // Fully populated: one sample per bucket across the whole window.
        var full = SpeedTrail.EMPTY
        for (i in 0 until SpeedTrail.WINDOW_BUCKETS) {
            full = full.record(i * SpeedTrail.BUCKET_MS, i.toDouble())
        }
        val window = full.window((SpeedTrail.WINDOW_BUCKETS - 1) * SpeedTrail.BUCKET_MS)
        assertEquals(SpeedTrail.WINDOW_BUCKETS, window.size)
        assertTrue("a fully populated window must have no gaps", window.none { it == null })
    }

    @Test
    fun record_lands_in_the_right_slot_and_newest_is_last() {
        val trail =
            SpeedTrail.EMPTY
                .record(0L, 10.0)
                .record(SpeedTrail.BUCKET_MS, 20.0)
                .record(2 * SpeedTrail.BUCKET_MS, 30.0)

        val window = trail.window(2 * SpeedTrail.BUCKET_MS)
        assertEquals(SpeedTrail.WINDOW_BUCKETS, window.size)
        // A mutant that reverses order or off-by-ones the bucket index fails
        // this exact tail slice.
        assertEquals(listOf(10f, 20f, 30f), window.takeLast(3))
        assertEquals("newest sample must be the window's last slot", 30f, window.last()!!, DELTA)
    }

    @Test
    fun same_bucket_last_write_wins() {
        // Both land in bucket 0 (BUCKET_MS = 10_000 ms).
        val trail = SpeedTrail.EMPTY.record(500L, 10.0).record(9_999L, 20.0)

        // No duplicated slot for the bucket...
        assertEquals(1, trail.samples.size)
        // ...and the second write is the one that survives.
        assertEquals(20f, trail.samples.single().kph, DELTA)
    }

    @Test
    fun samples_age_out_after_30_minutes() {
        val windowMs = SpeedTrail.WINDOW_BUCKETS * SpeedTrail.BUCKET_MS
        var trail = SpeedTrail.EMPTY.record(0L, 50.0)

        // Just past the 30-minute window: the original sample must not appear.
        val staleWindow = trail.window(windowMs + SpeedTrail.BUCKET_MS)
        assertTrue("a sample older than the window must never resurface", staleWindow.all { it == null })

        // Eviction also bounds the underlying list (not just window()'s
        // lookup range) — a later record() call must drop the ancient entry
        // rather than let the list grow across a whole session.
        trail = trail.record(windowMs + SpeedTrail.BUCKET_MS, 10.0)
        assertEquals("the aged-out bucket-0 sample must be evicted, not retained", 1, trail.samples.size)
    }

    @Test
    fun missed_ticks_leave_null_gaps() {
        // Records at t=0 and t=30s (bucket 0 and bucket 3) — buckets 1 and 2
        // were never sampled (process asleep / app restarted).
        val trail =
            SpeedTrail.EMPTY
                .record(0L, 10.0)
                .record(3 * SpeedTrail.BUCKET_MS, 40.0)

        val window = trail.window(3 * SpeedTrail.BUCKET_MS)
        val tail = window.takeLast(4)
        // A mutant that fabricates continuity (interpolates/coasts) would put
        // a non-null value in the middle two slots.
        assertEquals(listOf(10f, null, null, 40f), tail)
    }

    @Test
    fun kph_clamped_to_axis() {
        assertEquals(
            "above MAX_SPEED_KPH clamps to 160",
            160f,
            SpeedTrail.EMPTY.record(0L, 200.0).samples.single().kph,
            DELTA,
        )
        assertEquals(
            "below MIN_SPEED_KPH clamps to 0",
            0f,
            SpeedTrail.EMPTY.record(0L, -5.0).samples.single().kph,
            DELTA,
        )
        // Positive control: an in-range value passes through unchanged.
        assertEquals(80f, SpeedTrail.EMPTY.record(0L, 80.0).samples.single().kph, DELTA)
    }
}
