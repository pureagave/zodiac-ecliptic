package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0
private const val FULL_CIRCLE = 360.0
private const val HOUR_MS = 3_600_000L
private const val TEN_MIN_MS = 600_000L
private const val DISTANCE_TOLERANCE_FRACTION = 0.001 // ±0.1%, per the spec
private const val BEARING_TOLERANCE_DEG = 1.0

/**
 * Independent haversine distance/bearing — deliberately NOT the
 * implementation's equirectangular math (`GpsSimulator.positionAt`), per this
 * project's rule that a cross-check test must not agree with the code it is
 * checking. Hand-written from the standard formulas, not shared with
 * `:app` (the modules share no code).
 */
private fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sinDLat = sin(dLat / 2)
    val sinDLon = sin(dLon / 2)
    val a = sinDLat * sinDLat + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinDLon * sinDLon
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_M * c
}

private fun haversineInitialBearingDeg(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    val theta = Math.toDegrees(atan2(y, x))
    return ((theta % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
}

/** Default `current = null` / `nowMs = 0L` so most call sites only need to state the extras that matter. */
private fun cmd(
    extras: SimCommandExtras,
    current: GpsSimulator? = null,
    nowMs: Long = 0L,
): GpsSimulator? = simCommand(extras, current, nowMs)

private fun preset(
    name: String,
    hdg: String? = null,
    spd: String? = null,
) = SimCommandExtras(preset = name, lat = null, lon = null, hdg = hdg, spd = spd)

private fun latLon(
    lat: String?,
    lon: String?,
    hdg: String? = null,
    spd: String? = null,
) = SimCommandExtras(preset = null, lat = lat, lon = lon, hdg = hdg, spd = spd)

private fun turn(
    hdg: String?,
    spd: String?,
) = SimCommandExtras(preset = null, lat = null, lon = null, hdg = hdg, spd = spd)

class GpsSimTest {
    private val startLat = 40.78
    private val startLon = -119.20

    // --- GpsSimulator.positionAt / turn ---

    @Test
    fun stationary_sim_never_moves() {
        // Mutation target: dead reckoning applied unconditionally (e.g. distance
        // computed without multiplying by speed, or a "+ 1 tick" default).
        val sim = GpsSimulator(SimSpec(startLat, startLon, headingDeg = 47.0, speedKph = 0.0, label = "TEST"), startedAtMs = 0L)
        val (lat, lon) = sim.positionAt(HOUR_MS)
        assertEquals(startLat, lat, 0.0)
        assertEquals(startLon, lon, 0.0)
    }

    @Test
    fun north_at_10kph_matches_independent_haversine() {
        val sim = GpsSimulator(SimSpec(startLat, startLon, headingDeg = 0.0, speedKph = 10.0, label = "TEST"), startedAtMs = 0L)
        val (lat, lon) = sim.positionAt(TEN_MIN_MS)
        val dist = haversineMeters(startLat, startLon, lat, lon)
        assertEquals(1666.7, dist, 1666.7 * DISTANCE_TOLERANCE_FRACTION)
        assertEquals(0.0, haversineInitialBearingDeg(startLat, startLon, lat, lon), BEARING_TOLERANCE_DEG)
    }

    @Test
    fun east_at_10kph_pins_the_cos_lat_longitude_scaling() {
        // A missing cos(lat) divisor on the longitude term is a ~32% error at
        // 40.78°N -- comfortably outside the ±0.1% tolerance below, so this
        // kills that mutant specifically (the north case above can't: cos(lat)
        // only scales the longitude term, which is ~0 when heading due north).
        val sim = GpsSimulator(SimSpec(startLat, startLon, headingDeg = 90.0, speedKph = 10.0, label = "TEST"), startedAtMs = 0L)
        val (lat, lon) = sim.positionAt(TEN_MIN_MS)
        val dist = haversineMeters(startLat, startLon, lat, lon)
        assertEquals(1666.7, dist, 1666.7 * DISTANCE_TOLERANCE_FRACTION)
        assertEquals(90.0, haversineInitialBearingDeg(startLat, startLon, lat, lon), BEARING_TOLERANCE_DEG)
    }

    @Test
    fun southwest_at_10kph_matches_independent_haversine() {
        val sim = GpsSimulator(SimSpec(startLat, startLon, headingDeg = 225.0, speedKph = 10.0, label = "TEST"), startedAtMs = 0L)
        val (lat, lon) = sim.positionAt(TEN_MIN_MS)
        val dist = haversineMeters(startLat, startLon, lat, lon)
        assertEquals(1666.7, dist, 1666.7 * DISTANCE_TOLERANCE_FRACTION)
        assertEquals(225.0, haversineInitialBearingDeg(startLat, startLon, lat, lon), BEARING_TOLERANCE_DEG)
    }

    @Test
    fun position_is_a_pure_function_of_elapsed_time_no_accumulation() {
        // Mutation target: positionAt caching/mutating internal state instead
        // of recomputing from (spec, startedAtMs, nowMs) every call.
        val sim = GpsSimulator(SimSpec(startLat, startLon, 45.0, 20.0, "TEST"), startedAtMs = 1_000L)
        val a = sim.positionAt(50_000L)
        val b = sim.positionAt(50_000L)
        assertEquals(a, b)
    }

    @Test
    fun turn_square_path_returns_near_start() {
        // Kills sign/axis mutants: a wrong sign on dLat or dLon accumulates
        // across 4 turns into a large final error, not a small one.
        var sim = GpsSimulator(SimSpec(startLat, startLon, 0.0, 8.0, "TEST"), startedAtMs = 0L)
        var t = 0L
        val legMs = 90_000L
        repeat(4) {
            t += legMs
            sim = sim.turn(t, sim.spec.headingDeg + 90.0, 8.0)
        }
        val (lat, lon) = sim.positionAt(t)
        val dist = haversineMeters(startLat, startLon, lat, lon)
        assertTrue("expected the square path to close near the start; was ${dist}m away", dist < 5.0)
    }

    @Test
    fun turn_continues_from_the_current_dead_reckoned_position_not_the_original_start() {
        val armed = GpsSimulator(SimSpec(startLat, startLon, 0.0, 10.0, "MANUAL"), startedAtMs = 0L)
        val expected = armed.positionAt(TEN_MIN_MS)

        val turned = armed.turn(TEN_MIN_MS, headingDeg = 180.0, speedKph = 5.0)

        assertEquals(expected.first, turned.spec.latDeg, 1e-9)
        assertEquals(expected.second, turned.spec.lonDeg, 1e-9)
        assertEquals(180.0, turned.spec.headingDeg, 0.0)
        assertEquals(5.0, turned.spec.speedKph, 0.0)
    }

    // --- simCommand: positive paths ---

    @Test
    fun preset_resolves_to_the_exact_restated_coordinates_case_insensitively() {
        // Assert the literal doubles, not just "close to" -- a drift guard: if
        // someone fat-fingers a digit re-stating these from :app, this fails.
        val sim = cmd(preset("man"))
        requireNotNull(sim)
        assertEquals(40.783247448000054, sim.spec.latDeg, 0.0)
        assertEquals(-119.20788409599999, sim.spec.lonDeg, 0.0)
        assertEquals("MAN", sim.spec.label)
    }

    @Test
    fun lat_lon_path_arms_at_the_given_point() {
        val sim = cmd(latLon(lat = "10.5", lon = "-20.25", hdg = "90", spd = "8"))
        requireNotNull(sim)
        assertEquals(10.5, sim.spec.latDeg, 0.0)
        assertEquals(-20.25, sim.spec.lonDeg, 0.0)
        assertEquals(90.0, sim.spec.headingDeg, 0.0)
        assertEquals(8.0, sim.spec.speedKph, 0.0)
        assertEquals("MANUAL", sim.spec.label)
    }

    @Test
    fun heading_is_normalized_into_0_360() {
        // Mutation target: normalizeHeading's double-modulo collapsed to a
        // single `%`, which would leave a negative or >=360 result unwrapped.
        val sim = cmd(preset("man", hdg = "450"))
        requireNotNull(sim)
        assertEquals(90.0, sim.spec.headingDeg, 0.0)
    }

    @Test
    fun missing_heading_and_speed_default_to_zero() {
        val sim = cmd(preset("man"))
        requireNotNull(sim)
        assertEquals(0.0, sim.spec.headingDeg, 0.0)
        assertEquals(0.0, sim.spec.speedKph, 0.0)
    }

    @Test
    fun turn_form_continues_from_the_current_position_not_the_original_start() {
        val armed = GpsSimulator(SimSpec(startLat, startLon, 0.0, 10.0, "MANUAL"), startedAtMs = 0L)
        val turned = cmd(turn(hdg = "180", spd = "5"), current = armed, nowMs = TEN_MIN_MS)
        requireNotNull(turned)
        val expected = armed.positionAt(TEN_MIN_MS)
        assertEquals(expected.first, turned.spec.latDeg, 1e-9)
        assertEquals(expected.second, turned.spec.lonDeg, 1e-9)
        assertEquals(180.0, turned.spec.headingDeg, 0.0)
        assertEquals(5.0, turned.spec.speedKph, 0.0)
    }

    // --- simCommand: negative paths, each with an adjacent positive control ---

    @Test
    fun preset_and_lat_lon_together_is_ambiguous_and_rejected() {
        assertNull(cmd(SimCommandExtras(preset = "man", lat = "10", lon = "20", hdg = null, spd = null)))
    }

    @Test
    fun preset_alone_is_accepted_as_the_positive_control() {
        assertTrue(cmd(preset("man")) != null)
    }

    @Test
    fun lat_without_lon_is_ambiguous_and_rejected() {
        assertNull(cmd(latLon(lat = "10", lon = null)))
    }

    @Test
    fun lat_and_lon_together_is_accepted_as_the_positive_control() {
        assertTrue(cmd(latLon(lat = "10", lon = "20")) != null)
    }

    @Test
    fun latitude_above_90_is_rejected() {
        assertNull(cmd(latLon(lat = "91", lon = "0")))
    }

    @Test
    fun latitude_within_range_is_accepted_as_the_positive_control() {
        assertTrue(cmd(latLon(lat = "89", lon = "0")) != null)
    }

    @Test
    fun longitude_below_negative_180_is_rejected() {
        assertNull(cmd(latLon(lat = "0", lon = "-181")))
    }

    @Test
    fun longitude_within_range_is_accepted_as_the_positive_control() {
        assertTrue(cmd(latLon(lat = "0", lon = "-179")) != null)
    }

    @Test
    fun garbage_latitude_string_is_rejected() {
        assertNull(cmd(latLon(lat = "abc", lon = "0")))
    }

    @Test
    fun negative_speed_is_rejected() {
        assertNull(cmd(preset("man", spd = "-1")))
    }

    @Test
    fun speed_above_max_is_rejected() {
        assertNull(cmd(preset("man", spd = "161")))
    }

    @Test
    fun speed_at_the_max_boundary_is_accepted_as_the_positive_control() {
        assertTrue(cmd(preset("man", spd = "160")) != null)
    }

    @Test
    fun nan_speed_string_is_rejected_even_though_it_parses() {
        // String.toDoubleOrNull("NaN") returns Double.NaN, not null. Pins the
        // spec's required negative case; Kotlin's ClosedFloatingPointRange
        // already excludes NaN from `v in 0.0..MAX_SIM_SPEED_KPH` by IEEE-754
        // semantics (verified: deleting parseSpeed's isFinite() check alone does
        // NOT make this fail), so the discriminating mutant here is dropping the
        // bounds check entirely -- already covered jointly with
        // negative_speed_is_rejected/speed_above_max_is_rejected, which a
        // dropped-bounds-check mutant would also break.
        assertNull(cmd(preset("man", spd = "NaN")))
    }

    @Test
    fun nan_heading_string_is_rejected_even_though_it_parses() {
        // Unlike speed, heading has NO range comparison after the finite check
        // -- normalizeHeading's `%`/`+` arithmetic propagates NaN silently
        // instead of excluding it (no IEEE-754 comparison operator involved).
        // isFinite() is therefore the ONLY guard here. Mutation target: delete
        // `v.isFinite()` from parseHeading -- verified by hand-mutation that
        // this specific test (not the speed one above) is what catches it.
        assertNull(cmd(preset("man", hdg = "NaN")))
    }

    @Test
    fun no_position_and_not_yet_armed_is_rejected() {
        // Mutation target: the `current != null` branch guard removed/inverted.
        assertNull(cmd(turn(hdg = "90", spd = "8"), current = null))
    }

    @Test
    fun unknown_preset_is_rejected() {
        assertNull(cmd(preset("MARS")))
    }

    // --- readout/banner text ---

    @Test
    fun sim_status_line_contains_the_warning_label_heading_and_speed() {
        val sim = GpsSimulator(SimSpec(10.0, 20.0, 270.0, 8.0, "HOME"), startedAtMs = 0L)
        val line = simStatusLine(sim)
        assertTrue(line.contains("GPS SIMULATED"))
        assertTrue(line.contains("HOME"))
        assertTrue(line.contains("270"))
        assertTrue(line.contains("8.0"))
    }

    @Test
    fun sim_banner_text_is_two_lines_with_the_label_heading_and_speed() {
        val sim = GpsSimulator(SimSpec(10.0, 20.0, 47.0, 12.0, "MAN"), startedAtMs = 0L)
        val text = simBannerText(sim)
        val lines = text.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("GPS SIMULATED"))
        assertTrue(lines[1].contains("MAN"))
        assertTrue(lines[1].contains("47"))
        assertTrue(lines[1].contains("12.0"))
    }
}
