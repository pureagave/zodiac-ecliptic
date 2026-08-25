package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvanceTapTest {
    @Test
    fun a_still_single_finger_tap_advances() {
        assertEquals(true, isAdvanceTap(maxPointerCount = 1, travelPx = 0f, touchSlopPx = SLOP, consumedByChild = false))
    }

    @Test
    fun travel_past_touch_slop_is_a_drag_not_a_tap() {
        // Kills a dropped slop guard — the map panning would advance the card.
        assertEquals(
            false,
            isAdvanceTap(maxPointerCount = 1, travelPx = 25f, touchSlopPx = SLOP, consumedByChild = false),
        )
    }

    @Test
    fun travel_exactly_at_slop_still_counts_as_a_tap() {
        // Kills `<` in place of `<=`: the boundary must match PinchSession's
        // own `<=` suppression exactly, or a gesture at precisely the slop
        // distance satisfies neither {map pans, card advances}.
        assertEquals(
            true,
            isAdvanceTap(maxPointerCount = 1, travelPx = SLOP, touchSlopPx = SLOP, consumedByChild = false),
        )
    }

    @Test
    fun a_second_finger_makes_it_a_pinch_not_a_tap() {
        // Kills a dropped pointer-count guard.
        assertEquals(
            false,
            isAdvanceTap(maxPointerCount = 2, travelPx = 0f, touchSlopPx = SLOP, consumedByChild = false),
        )
    }

    @Test
    fun a_gesture_a_child_consumed_is_not_a_tap() {
        // Kills a dropped consumption guard — the recenter button would
        // both recenter AND advance the card.
        assertEquals(
            false,
            isAdvanceTap(maxPointerCount = 1, travelPx = 0f, touchSlopPx = SLOP, consumedByChild = true),
        )
    }

    private companion object {
        const val SLOP = 24f
    }
}
