package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

private const val DELTA = 1e-4f

class EnvGaugeModelTest {
    @Test
    fun luxBarFraction_anchor_rows_pin_the_decade_span() {
        // A mutant that shifts the log span (e.g. a different min/max decade)
        // fails the midpoint (100 lx sits exactly halfway across 0.1..100_000
        // on a log scale) even if the two endpoints happen to still hold.
        assertEquals(0.0f, luxBarFraction(0.1)!!, DELTA)
        assertEquals(1.0f, luxBarFraction(100_000.0)!!, DELTA)
        assertEquals(0.5f, luxBarFraction(100.0)!!, DELTA)
    }

    @Test
    fun luxBarFraction_clamps_outside_the_span_with_an_in_range_control() {
        assertEquals("below the span floors at 0", 0.0f, luxBarFraction(0.01)!!, DELTA)
        assertEquals("above the span ceils at 1", 1.0f, luxBarFraction(1_000_000.0)!!, DELTA)
        // In-range control: an unclamped value must not also read as 0 or 1.
        val midish = luxBarFraction(500.0)!!
        assertFalse(midish <= 0.0f || midish >= 1.0f)
    }

    @Test
    fun luxBarFraction_null_is_null_not_a_fake_zero() {
        // The honesty pin: a `?: 0f` mutant would make an absent sensor read
        // as "confirmed dark", which a dead beacon cannot claim.
        assertNull(luxBarFraction(null))
    }

    @Test
    fun shockBarFraction_anchor_rows_and_clamp() {
        assertEquals(0.5f, shockBarFraction(1.0)!!, DELTA)
        // 3.0 g is above the 2 g span; clamps to full rather than escaping it.
        assertEquals(1.0f, shockBarFraction(3.0)!!, DELTA)
    }

    @Test
    fun shockBarFraction_null_is_null_not_a_fake_zero() {
        assertNull(shockBarFraction(null))
    }

    @Test
    fun formatLuxValue_dashes_on_absence_with_a_non_null_control() {
        assertEquals("—", formatLuxValue(null))
        // Positive control: a real reading reuses formatLux's own formatting
        // (pinned by TelemetryTickerTest) rather than the dash.
        assertEquals("84", formatLuxValue(84.0))
    }

    @Test
    fun formatShockValue_dashes_on_absence_with_a_non_null_control() {
        assertEquals("—", formatShockValue(null))
        assertEquals("1.2G", formatShockValue(1.2))
    }
}
