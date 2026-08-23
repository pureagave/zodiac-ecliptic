package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun render(line: List<TickerSegment>): String = line.joinToString(" ") { it.text }

/** Every channel present — the reference used by the per-channel-removed tests below. */
private val FULL_INPUTS =
    TickerInputs(
        lat = 40.78644,
        lon = -119.20632,
        fixQualityM = 4.2,
        headingDeg = 273,
        gpsSpeedKph = 12.4,
        lux = 84.0,
        lastShockG = 1.2,
        health = BeaconHealth(batteryPct = 78, fixQuality = 1, satellites = 11, uptimeSec = 11_220L),
        odometer = Odometer(tripMeters = 3_100.0, totalMeters = 812_400.0),
    )

class TelemetryTickerTest {
    @Test
    fun full_inputs_produce_every_line_in_pinned_order_with_pinned_text() {
        val lines = tickerLines(FULL_INPUTS)
        assertEquals(8, lines.size)
        assertEquals(
            listOf(
                "POS 40.78644 -119.20632",
                "FIX ±4.2M",
                "HDG 273°",
                "SPD 12.4KPH",
                "LUX 84",
                "SHK 1.2G",
                "BCN BATT 78% SATS 11 UP 3H07",
                "ODO TRIP 3.1KM TOTAL 812.4KM",
            ),
            lines.map(::render),
        )
    }

    @Test
    fun pos_line_segments_split_chrome_and_value() {
        // Representative role check (the rest of the pinned-text test covers
        // the string content) — kills a mutant that swaps CHROME/VALUE.
        val pos = tickerLines(FULL_INPUTS).first()
        assertEquals(listOf(TickerRole.CHROME, TickerRole.VALUE), pos.map { it.role })
        assertEquals("POS", pos[0].text)
    }

    @Test
    fun missing_lat_drops_only_the_pos_line() {
        val lines = tickerLines(FULL_INPUTS.copy(lat = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("POS") })
    }

    @Test
    fun missing_lon_alone_also_drops_the_pos_line() {
        // POS requires BOTH lat and lon — a mutant that gates on only one of
        // the two would let this one through with a fabricated line.
        val lines = tickerLines(FULL_INPUTS.copy(lon = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("POS") })
    }

    @Test
    fun missing_fixQualityM_drops_only_the_fix_line() {
        val lines = tickerLines(FULL_INPUTS.copy(fixQualityM = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("FIX") })
    }

    @Test
    fun missing_headingDeg_drops_only_the_hdg_line() {
        val lines = tickerLines(FULL_INPUTS.copy(headingDeg = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("HDG") })
    }

    @Test
    fun missing_gpsSpeedKph_drops_only_the_spd_line() {
        val lines = tickerLines(FULL_INPUTS.copy(gpsSpeedKph = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("SPD") })
    }

    @Test
    fun missing_lux_drops_only_the_lux_line() {
        val lines = tickerLines(FULL_INPUTS.copy(lux = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("LUX") })
    }

    @Test
    fun missing_lastShockG_drops_only_the_shk_line() {
        val lines = tickerLines(FULL_INPUTS.copy(lastShockG = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("SHK") })
    }

    @Test
    fun missing_health_drops_only_the_bcn_line() {
        val lines = tickerLines(FULL_INPUTS.copy(health = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("BCN") })
    }

    @Test
    fun missing_odometer_drops_only_the_odo_line() {
        val lines = tickerLines(FULL_INPUTS.copy(odometer = null)).map(::render)
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.startsWith("ODO") })
    }

    @Test
    fun empty_inputs_produce_no_lines_and_tickerLineAt_falls_back_to_no_telemetry() {
        val empty = TickerInputs()
        assertEquals(emptyList<List<TickerSegment>>(), tickerLines(empty))
        assertEquals("NO TELEMETRY", render(tickerLineAt(empty, seq = 0L)))
        // Any seq, not just 0 — the fallback doesn't depend on the counter.
        assertEquals("NO TELEMETRY", render(tickerLineAt(empty, seq = 41L)))
    }

    @Test
    fun tickerLineAt_cycles_through_lines_in_order_and_wraps_at_seq_equals_size() {
        // Exactly 3 lines: POS, FIX, HDG (in pinned order).
        val threeLine =
            TickerInputs(lat = 1.0, lon = 2.0, fixQualityM = 3.0, headingDeg = 90)
        assertEquals(3, tickerLines(threeLine).size)

        assertEquals("POS 1.00000 2.00000", render(tickerLineAt(threeLine, seq = 0L)))
        assertEquals("FIX ±3.0M", render(tickerLineAt(threeLine, seq = 1L)))
        assertEquals("HDG 90°", render(tickerLineAt(threeLine, seq = 2L)))
        // seq == size wraps back to line 0 — the pinned boundary.
        assertEquals("POS 1.00000 2.00000", render(tickerLineAt(threeLine, seq = 3L)))
    }

    @Test
    fun tickerLineAt_survives_an_inputs_shrink_between_calls() {
        // 4 lines: POS (lat+lon paired), FIX, HDG, SPD.
        val four = TickerInputs(lat = 1.0, lon = 2.0, fixQualityM = 3.0, headingDeg = 90, gpsSpeedKph = 5.0)
        assertEquals(4, tickerLines(four).size)
        // A valid high index against the larger input list...
        render(tickerLineAt(four, seq = 4L))

        // ...must not throw when the very next call sees a shrunk input list
        // (e.g. the beacon dropped a channel between ticks); it must re-derive
        // the modulus from the *current* list, not a stale size.
        val one = TickerInputs(lat = 1.0, lon = 2.0)
        assertEquals(1, tickerLines(one).size)
        assertEquals("POS 1.00000 2.00000", render(tickerLineAt(one, seq = 4L)))
    }

    @Test
    fun formatLux_boundary_rows() {
        assertEquals("9.9", formatLux(9.9))
        assertEquals("10", formatLux(10.0))
        assertEquals("999", formatLux(999.0))
        assertEquals("1.0K", formatLux(1_000.0))
        assertEquals("12.4K", formatLux(12_400.0))
        assertEquals("8.4", formatLux(8.4))
    }
}
