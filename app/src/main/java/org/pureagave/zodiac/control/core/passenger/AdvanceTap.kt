package org.pureagave.zodiac.control.core.passenger

/**
 * Whether a completed whole-screen pointer gesture on the passenger display
 * counts as a tap-to-advance. A tap is: one finger only, never travelling
 * past the platform touch slop, and not claimed by any child (the recenter
 * button's clickable consumes its events; the MAP card's pan/pinch handler
 * deliberately does not, which is why travel — not consumption — is what
 * separates a tap from a drag here).
 *
 * `<=` on the slop boundary is deliberate: the map's own PinchSession
 * suppresses pan while travel <= slop and pans strictly beyond it, so with
 * the same platform slop value exactly one of {map pans, card advances}
 * fires for any gesture.
 */
fun isAdvanceTap(
    maxPointerCount: Int,
    travelPx: Float,
    touchSlopPx: Float,
    consumedByChild: Boolean,
): Boolean = maxPointerCount == 1 && travelPx <= touchSlopPx && !consumedByChild
