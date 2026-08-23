package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

    private companion object {
        const val TOLERANCE = 1e-9
        const val FIRE_HD10_SHORT_EDGE_ZOOM = 0.48
    }
}
