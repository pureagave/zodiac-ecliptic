package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaProjection

class HomeGuidanceTest {
    private val projection = PlayaProjection(GoldenSpike.ACTIVE)

    @Test
    fun from_the_man_the_default_home_is_on_the_7_30_radial_at_the_e_ring() {
        val g = campGuidance(ego = GoldenSpike.ACTIVE, target = Home.DEFAULT, projection = projection)
        // 7:30 radial = 270° true (axis 45° + 225°); E ring ≈ 1237 m from the Man.
        assertEquals(270.0, g.bearingDeg, 1.0)
        assertEquals(1237.0, g.distanceM, 15.0)
    }

    @Test
    fun standing_at_home_distance_is_zero() {
        val g = campGuidance(ego = Home.DEFAULT, target = Home.DEFAULT, projection = projection)
        assertTrue("expected ~0 m, got ${g.distanceM}", g.distanceM < 1.0)
    }

    @Test
    fun man_target_is_the_golden_spike() {
        val g = campGuidance(ego = GoldenSpike.ACTIVE, target = NavTarget.MAN.location, projection = projection)
        assertTrue("MAN should be the spike, got ${g.distanceM} m", g.distanceM < 1.0)
    }

    @Test
    fun temple_sits_on_the_12_oclock_axis_from_the_man() {
        // The Temple is ~2500 ft out on the 12:00 radial (45° true).
        val g = campGuidance(ego = GoldenSpike.ACTIVE, target = NavTarget.TEMPLE.location, projection = projection)
        assertEquals(45.0, g.bearingDeg, 3.0)
        assertEquals(762.0, g.distanceM, 25.0)
    }

    @Test
    fun home_target_resolves_to_the_configured_home() {
        assertEquals(Home.LOCATION, NavTarget.HOME.location)
    }

    @Test
    fun bearing_points_back_toward_home_from_the_far_side() {
        // A point due *north-west* of home should send the driver roughly
        // south-east — bearing in the lower-right quadrant (90..180).
        val northWestOfHome = LatLon(lon = Home.DEFAULT.lon - 0.02, lat = Home.DEFAULT.lat + 0.02)
        val g = campGuidance(ego = northWestOfHome, target = Home.DEFAULT, projection = projection)
        assertTrue("bearing ${g.bearingDeg} should be SE-ish", g.bearingDeg in 100.0..170.0)
        assertTrue(g.distanceM > 1_000.0)
    }
}
