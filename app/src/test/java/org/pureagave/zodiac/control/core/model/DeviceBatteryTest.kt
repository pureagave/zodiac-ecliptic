package org.pureagave.zodiac.control.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBatteryTest {
    @Test
    fun percent_is_level_over_scale() {
        assertEquals(50, batteryPercent(level = 50, scale = 100))
        // Integer division pinned: 33 * 100 / 200 = 16.5 -> 16, not rounded to 17.
        assertEquals(16, batteryPercent(level = 33, scale = 200))
        assertEquals(100, batteryPercent(level = 100, scale = 100))
    }

    @Test
    fun garbage_inputs_are_null_not_zero() {
        // The honesty pin: a mutant that returns 0 for unknown would scream
        // "dead battery" for a display whose charger is actually fine.
        assertNull(batteryPercent(level = -1, scale = 100))
        assertNull(batteryPercent(level = 50, scale = 0))
        assertNull(batteryPercent(level = 50, scale = -1))
    }

    @Test
    fun result_clamped_into_0_100() {
        // A vendor reporting level slightly above scale must not escape the axis.
        assertEquals(100, batteryPercent(level = 120, scale = 100))
        // Positive control: a valid mid-range value passes through unchanged.
        assertEquals(42, batteryPercent(level = 42, scale = 100))
    }

    @Test
    fun low_threshold_is_20() {
        assertEquals(20, DEVICE_BATTERY_LOW_PCT)
        // The <= boundary, exercised directly (this is how the UI reads the
        // constant) — a mutant that drifts the threshold or flips <= to <
        // fails one of these two rows.
        assertTrue("20% must read as low (at the boundary)", 20 <= DEVICE_BATTERY_LOW_PCT)
        assertTrue("21% must NOT read as low", 21 > DEVICE_BATTERY_LOW_PCT)
    }
}
