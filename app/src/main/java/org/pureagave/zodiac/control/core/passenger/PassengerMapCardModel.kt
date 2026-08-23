package org.pureagave.zodiac.control.core.passenger

import org.pureagave.zodiac.control.core.model.MapCameraState
import kotlin.math.min

// Pure decisions for the passenger carousel's interactive live-map card
// (`ui/passenger/PassengerMapCard.kt`). The viewport itself — pan, zoom,
// track-up follow — comes from the reused cockpit camera stack
// (`MapCameraController`, `playaMapPanel`); what's left pure here is the
// card's *starting* zoom and the touch-hold that pauses the carousel.

/** Ground metres across the canvas's short edge at card entry — a START, not a clamp. */
const val PASSENGER_MAP_START_SPAN_M: Double = 2_500.0

/**
 * How long after the last touch ends before the carousel may advance again.
 * See `design/PASSENGER-UI-split-spec.md` open question 1 — 10 s was Rob's
 * call: long enough to read the spot you panned to, short enough that an
 * abandoned card doesn't stall the rotation.
 */
const val MAP_TOUCH_RESUME_GRACE_MS: Long = 10_000L

/**
 * Zoom (screen pixels per playa metre) that fits [PASSENGER_MAP_START_SPAN_M]
 * of ground across the *short* edge of a [widthPx] x [heightPx] canvas — half
 * the city, the same starting view in either device orientation. Null while
 * the canvas is unsized (either dimension `<= 0`, e.g. before Compose's first
 * `onSizeChanged`): a divide-by-zero zoom would be a fabricated number, not an
 * honest "not yet known."
 *
 * Coerced into [MapCameraState.MIN_PIXELS_PER_METER]..[MapCameraState.MAX_PIXELS_PER_METER]
 * — the *same* bounds `MapCameraController.setPixelsPerMeter` clamps with, so
 * this stays a starting value inside the one source of camera bounds rather
 * than a second, competing one.
 */
fun passengerMapStartZoom(
    widthPx: Int,
    heightPx: Int,
): Double? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val shortEdgePx = min(widthPx, heightPx)
    return (shortEdgePx / PASSENGER_MAP_START_SPAN_M)
        .coerceIn(MapCameraState.MIN_PIXELS_PER_METER, MapCameraState.MAX_PIXELS_PER_METER)
}

/**
 * Hold the carousel: a finger is down right now, or one lifted less than
 * [graceMs] ago.
 *
 * [pointerDown] alone holds, regardless of [lastTouchMs] — a finger resting
 * motionless on the glass produces no pan/zoom callbacks, so the raw pointer
 * state is the only honest signal that a rider is still there. After release,
 * the hold persists while `nowMs - lastTouchMs < graceMs` — strictly less
 * than, so at exactly the grace boundary the hold has ended, not one tick
 * before or after.
 *
 * [nowMs] must be the carousel's own tick clock (the same one `CardRotation`
 * runs on), never wall time, or the grace window drifts against dwell.
 */
fun mapTouchHoldActive(
    pointerDown: Boolean,
    lastTouchMs: Long?,
    nowMs: Long,
    graceMs: Long = MAP_TOUCH_RESUME_GRACE_MS,
): Boolean {
    if (pointerDown) return true
    if (lastTouchMs == null) return false
    return nowMs - lastTouchMs < graceMs
}
