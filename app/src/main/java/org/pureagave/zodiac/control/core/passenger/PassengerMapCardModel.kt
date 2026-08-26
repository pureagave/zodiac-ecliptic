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

// --- Recenter button placement -------------------------------------------
//
// Where the card's recenter button sits is a *decision*, not a draw detail:
// two independent geometry faults made the button unreachable on hardware
// (measured 2026-08-25/26; the button, its onClick and recenterPan were all
// healthy the whole time), and both are settled by the numbers below.
//
//  (a) Occlusion. `CockpitScreen.kt` draws an invisible CORNER_HOT_ZONE_DP
//      square at BottomEnd carrying the hidden log-viewer / kiosk-exit taps.
//      It is a LATER sibling of the passenger screen, so it is topmost and
//      eats every pointer event in that corner. At the old 16 dp inset the
//      button's centre sat 44 dp in — inside the square. Measured on the hero
//      S9+ (immersive, no nav bar, button drawn unclipped): a press on it gave
//      neither a recenter nor a carousel advance. Pure occlusion.
//  (b) Clipping. The card is laid out edge-to-edge (MainActivity calls
//      setDecorFitsSystemWindows(false)), so on a device whose navigation bar
//      is really there the button is positioned against the *screen*, not the
//      visible window, and hangs off the bottom. Measured on a Fire HD KFMAWI
//      (API 28, density 1.5, 48 dp bar): the 56 dp button survived as a 34 px
//      (portrait) / 37 px (landscape) strip. Tapping that strip DID recenter —
//      which is what proves the wiring was never the fault.
//
// The cockpit's identical button escapes (a) only by accident: InstrumentBay
// pads it `bottom = 58.dp` to clear the ops footer. A bare 58 here would work
// on today's Fire by that same accident, so it is derived instead.

/** End inset for the passenger recenter button. */
const val PASSENGER_RECENTER_END_DP: Int = 16

/** Occlusion floor: clears the 56 dp hidden corner + 2 dp density-rounding slack; cockpit parity. */
const val PASSENGER_RECENTER_MIN_BOTTOM_DP: Int = 58

/**
 * A BottomEnd corner square is escaped by clearing EITHER axis.
 *
 * The hot zone is a square anchored in the corner, so a control whose inset on
 * *either* axis reaches [hotZoneDp] is outside it — pushing it up is as good as
 * pushing it left, and only one of the two has to be paid for. Boundary is
 * inclusive: at exactly [hotZoneDp] the control's near edge is flush with the
 * zone's far edge, i.e. already out.
 */
fun clearsBottomEndHotZone(
    endInsetDp: Int,
    bottomInsetDp: Int,
    hotZoneDp: Int,
): Boolean = endInsetDp >= hotZoneDp || bottomInsetDp >= hotZoneDp

/**
 * Bottom inset for the passenger recenter button: whichever is larger of the
 * hidden-corner occlusion floor ([PASSENGER_RECENTER_MIN_BOTTOM_DP]) and
 * [PASSENGER_RECENTER_END_DP] of clearance above the system navigation bar
 * ([navBarInsetDp], 0 on an immersive device).
 *
 * One function, both faults. On the hero S9+ (immersive, inset 0) it returns
 * 58 and the occlusion floor governs; on a Fire (inset 48) it returns 64, the
 * bar governs, and 64 is still >= the hot zone so fault (a) stays fixed. A
 * device with no bar and a device with a bar are therefore fixed by the same
 * expression rather than by a constant that happens to suit whichever tablet
 * was on the bench.
 */
fun passengerRecenterBottomInsetDp(navBarInsetDp: Int): Int =
    maxOf(PASSENGER_RECENTER_MIN_BOTTOM_DP, navBarInsetDp + PASSENGER_RECENTER_END_DP)
