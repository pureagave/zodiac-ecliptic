package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.kiosk.CORNER_HOT_ZONE_DP
import org.pureagave.zodiac.control.core.model.MapCameraState

class PassengerMapCardModelTest {
    // --- passengerMapStartZoom ----------------------------------------------

    @Test
    fun start_zoom_maps_short_edge_exactly() {
        // Fire HD 10 in landscape and portrait: same short edge, same zoom —
        // orientation-independent by construction (min, not width).
        assertEquals(FIRE_HD10_SHORT_EDGE_ZOOM, passengerMapStartZoom(1920, 1200)!!, TOLERANCE)
        assertEquals(FIRE_HD10_SHORT_EDGE_ZOOM, passengerMapStartZoom(1200, 1920)!!, TOLERANCE)
    }

    @Test
    fun start_zoom_is_coerced_into_camera_bounds() {
        // Absurdly large canvas would compute far past MAX_PIXELS_PER_METER.
        val huge = passengerMapStartZoom(1_000_000, 1_000_000)!!
        assertEquals(MapCameraState.MAX_PIXELS_PER_METER, huge, TOLERANCE)

        // Absurdly small canvas would compute far below MIN_PIXELS_PER_METER.
        val tiny = passengerMapStartZoom(1, 1)!!
        assertEquals(MapCameraState.MIN_PIXELS_PER_METER, tiny, TOLERANCE)

        // Positive control: an in-range value passes through UNCHANGED — this
        // is what tells "drops the coercion" apart from "coerces everything."
        val inRangeWidthPx = 1_200
        val inRange = passengerMapStartZoom(inRangeWidthPx, inRangeWidthPx)!!
        assertEquals(inRangeWidthPx / PASSENGER_MAP_START_SPAN_M, inRange, TOLERANCE)
    }

    @Test
    fun start_zoom_null_until_sized() {
        assertNull(passengerMapStartZoom(0, 1200))
        assertNull(passengerMapStartZoom(1920, 0))
    }

    // --- mapTouchHoldActive --------------------------------------------------

    @Test
    fun finger_down_holds_regardless_of_history() {
        // A motionless finger produces no pan/zoom callbacks; the raw pointer
        // state alone must hold, even with no touch history at all.
        assertTrue(mapTouchHoldActive(pointerDown = true, lastTouchMs = null, nowMs = 500))
    }

    @Test
    fun grace_window_boundary_is_exclusive() {
        val graceMs = MAP_TOUCH_RESUME_GRACE_MS
        assertTrue(mapTouchHoldActive(pointerDown = false, lastTouchMs = 0L, nowMs = graceMs - 1))
        assertFalse(mapTouchHoldActive(pointerDown = false, lastTouchMs = 0L, nowMs = graceMs))
    }

    @Test
    fun no_touch_no_hold() {
        assertFalse(mapTouchHoldActive(pointerDown = false, lastTouchMs = null, nowMs = 100))
    }

    // --- recenter button placement -------------------------------------------
    //
    // These pin the GEOMETRY DECISION only. Two things they cannot cover, and
    // no JVM unit test can: (1) whether Compose's hit test really routes a tap
    // at these coordinates to the button rather than to CockpitScreen's later
    // sibling hot-zone Box, and (2) whether WindowInsets.safeDrawing actually
    // delivers 48 on a Fire and 0 on the S9+. Both were established on
    // hardware (2026-08-25/26) and both stay hardware-verified.

    @Test
    fun immersive_device_still_clears_the_hidden_corner() {
        // Hero S9+: immersive, so the nav-bar inset really is 0 and fault (b)
        // never applies. Fails if the occlusion floor is dropped and the inset
        // is left to follow the bar alone — which is the state the bug shipped
        // in: 0 + 16 = 16, back inside the 56 dp hot zone.
        assertEquals(PASSENGER_RECENTER_MIN_BOTTOM_DP, passengerRecenterBottomInsetDp(0))
        assertEquals(58, passengerRecenterBottomInsetDp(0))
    }

    @Test
    fun fire_hd_nav_bar_governs_over_the_floor() {
        // The measured Fire HD KFMAWI case: 48 dp bottom nav bar, so the button
        // must sit 48 + 16 = 64 dp up to be both unclipped and clear of the
        // zone. This is fault (b): with the pre-fix flat 16 dp the button hung
        // below the visible window and survived as a 34 px strip. Fails if the
        // real inset stops being added.
        assertEquals(FIRE_NAV_BAR_DP + PASSENGER_RECENTER_END_DP, passengerRecenterBottomInsetDp(FIRE_NAV_BAR_DP))
        assertEquals(64, passengerRecenterBottomInsetDp(FIRE_NAV_BAR_DP))
    }

    @Test
    fun crossover_is_at_the_floor_boundary() {
        // 42 + 16 == 58 == the floor: the exact point where the two terms meet.
        // Because both terms are 58 here this row is blind to *which* one wins
        // — verified by mutation, it survives "always the floor" and "+" -> "-"
        // alike. What it does catch is either constant drifting while the other
        // term still lands on 58: floor -> 56 gives 58 != 56, end -> 20 gives
        // 62 != 58.
        assertEquals(PASSENGER_RECENTER_MIN_BOTTOM_DP, passengerRecenterBottomInsetDp(42))
        // One dp past the crossover the bar must win. THIS is the row that
        // proves the second term is live: it kills "always the floor" (58),
        // "+" -> "-" (58), a dropped "+ END" (58) and maxOf -> minOf (58).
        assertEquals(59, passengerRecenterBottomInsetDp(43))
    }

    @Test
    fun hot_zone_is_escaped_by_clearing_either_axis() {
        // The literal pre-fix geometry: 16 dp on both axes, inside the 56 dp
        // square — this row is the bug, stated as a fact.
        assertFalse(clearsBottomEndHotZone(16, 16, CORNER_HOT_ZONE_DP))
        // Cleared on the bottom axis alone (what the fix does).
        assertTrue(clearsBottomEndHotZone(16, 58, CORNER_HOT_ZONE_DP))
        // Cleared on the end axis alone. Kills "||" -> "&&", which would
        // demand both axes and call each of these two rows false.
        assertTrue(clearsBottomEndHotZone(58, 16, CORNER_HOT_ZONE_DP))
        // Exactly at the edge counts as clear: the control's near edge is flush
        // with the zone's far edge. Kills ">=" -> ">".
        assertTrue(clearsBottomEndHotZone(CORNER_HOT_ZONE_DP, 16, CORNER_HOT_ZONE_DP))
    }

    @Test
    fun computed_inset_clears_the_zone_and_the_bar_on_both_measured_devices() {
        // Property, not a table: for each inset the fleet actually reports, the
        // number this function returns must (a) escape the hidden corner given
        // the 16 dp end inset the card really uses, and (b) sit above the bar.
        // Reads CORNER_HOT_ZONE_DP from core, which CockpitScreen's LOG_HOT_ZONE
        // is defined from, so this cannot drift from the zone the app draws.
        for (navBarDp in intArrayOf(0, FIRE_NAV_BAR_DP)) {
            val bottom = passengerRecenterBottomInsetDp(navBarDp)
            assertTrue(
                "inset $bottom does not clear the hidden corner at navBar=$navBarDp",
                clearsBottomEndHotZone(PASSENGER_RECENTER_END_DP, bottom, CORNER_HOT_ZONE_DP),
            )
            // `>= navBarDp` would be satisfied by a button resting flush on the
            // bar; the requirement is a visible margin above it, so assert the
            // gap against a literal REQUIRED_BAR_CLEARANCE_DP rather than
            // against PASSENGER_RECENTER_END_DP, which a mutant moves too.
            assertTrue(
                "inset $bottom leaves no margin above the nav bar at navBar=$navBarDp",
                bottom - navBarDp >= REQUIRED_BAR_CLEARANCE_DP,
            )
        }
    }

    @Test
    fun a_deep_navigation_bar_governs_by_the_full_margin() {
        // No device on this fleet reports a bar this deep, so these are not
        // measurements — they are the guard that the bar term is an unbounded
        // sum and not a lookup that happens to suit the two tablets on the
        // bench. Mutation-checked: a `.coerceAtMost(64)` clamp fits BOTH
        // measured devices (0 -> 58, 48 -> 64) and survives every other test
        // in this file; it dies here.
        assertEquals(112, passengerRecenterBottomInsetDp(96))
        assertEquals(216, passengerRecenterBottomInsetDp(200))
    }

    @Test
    fun a_nonsensical_negative_inset_falls_back_to_the_floor() {
        // WindowInsets.getBottom() should never go negative, but the card
        // reaches this function through `.toDp().value.toInt()` on a platform
        // value and the function is public, so "should never" is not a
        // guarantee. maxOf() is what makes the floor a real floor; without it
        // a negative inset would drive the button off the bottom of the screen
        // — the failure mode this whole fix exists to end.
        assertEquals(PASSENGER_RECENTER_MIN_BOTTOM_DP, passengerRecenterBottomInsetDp(-1))
        assertEquals(58, passengerRecenterBottomInsetDp(-1))
        assertEquals(58, passengerRecenterBottomInsetDp(-1_000))
    }

    @Test
    fun every_plausible_bar_depth_clears_both_the_zone_and_the_bar() {
        // The two invariants the fix is FOR, asserted across the whole range a
        // system bar could plausibly report rather than at the two points that
        // were on the bench. Any inset the platform hands us must leave the
        // button (1) outside CockpitScreen's hidden BottomEnd hot zone and
        // (2) a visible margin above the bar itself.
        for (navBarDp in 0..200) {
            val bottom = passengerRecenterBottomInsetDp(navBarDp)
            assertTrue(
                "navBar=$navBarDp -> inset $bottom is inside the hidden corner",
                clearsBottomEndHotZone(PASSENGER_RECENTER_END_DP, bottom, CORNER_HOT_ZONE_DP),
            )
            assertTrue(
                "navBar=$navBarDp -> inset $bottom leaves no margin above the bar",
                bottom - navBarDp >= REQUIRED_BAR_CLEARANCE_DP,
            )
        }
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val FIRE_HD10_SHORT_EDGE_ZOOM = 0.48

        /** Measured on a Fire HD KFMAWI (API 28, density 1.5): 72 px bottom bar. */
        const val FIRE_NAV_BAR_DP = 48

        /**
         * Visible gap the button must keep above the system bar. A literal on
         * purpose: expressing it as PASSENGER_RECENTER_END_DP would move with
         * a mutation of that constant instead of catching it.
         */
        const val REQUIRED_BAR_CLEARANCE_DP = 16
    }
}
