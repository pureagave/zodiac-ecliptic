package org.pureagave.zodiac.control.ui.passenger

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.pureagave.zodiac.control.core.passenger.isAdvanceTap

/**
 * Whole-screen tap-to-advance for the passenger carousel.
 *
 * Not detectTapGestures: that detector calls any down->up a tap unless a
 * child CONSUMED the gesture — and the MAP card's pan/pinch handler
 * (cockpitTouchInput) deliberately never consumes, so the stock detector
 * would fire at the end of every drag and pinch on the map. This loop only
 * accumulates raw telemetry (peak pointer count, travel from the down point,
 * child consumption); the decision is core's tested [isAdvanceTap].
 * rememberUpdatedState per MapTouchInput's precedent: pointerInput(Unit)
 * attaches once, so the callback must be read through State to stay current.
 */
internal fun Modifier.advanceTapInput(onAdvanceTap: () -> Unit): Modifier =
    composed {
        val tapCb by rememberUpdatedState(onAdvanceTap)
        pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var pointers = 1
                var travelPx = 0f
                var consumed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    pointers = maxOf(pointers, event.changes.count { it.pressed })
                    if (event.changes.any { it.isConsumed }) consumed = true
                    // No `?: continue` here (detekt: LoopWithTooManyJumpStatements
                    // forbids mixing break and continue) — an event with only
                    // OTHER pointers' changes just skips the body below and the
                    // loop falls through to its next iteration on its own.
                    val tracked = event.changes.firstOrNull { it.id == down.id }
                    if (tracked != null) {
                        travelPx = maxOf(travelPx, (tracked.position - down.position).getDistance())
                        if (!tracked.pressed) break
                    }
                }
                if (isAdvanceTap(pointers, travelPx, viewConfiguration.touchSlop, consumed)) tapCb()
            }
        }
    }
