package org.pureagave.zodiac.control.core.ops

import java.util.Locale

/**
 * The text of the transient "target just changed" banner (`ui/ops/NavTargetFlashBanner.kt`):
 * two always-present strings so the composable draws them with zero branching
 * — there is no "which field is present" decision to make at render time,
 * unlike [org.pureagave.zodiac.control.core.navigation.NavigationCue]'s sealed
 * variants.
 */
data class NavTargetFlash(val label: String, val value: String)

private const val ROUTING_TO = "ROUTING TO"

/**
 * Map an adopted `$ZNAV` [payload] to the banner it should flash. Formats the
 * clock field itself with `%d:%02d`/`Locale.US` (decision 5, same as
 * [NavShareProtocol.build]) rather than calling
 * [org.pureagave.zodiac.control.core.navigation.ClockTime.format], which is
 * locale-unpinned. [NavSharePayload.Preset] has its own exhaustive `when` on
 * [NavTarget] rather than reusing [NavTarget.label] — the label is the terse
 * chip text ("MAN"), not the sentence this banner reads ("THE MAN"). Both
 * `when`s are exhaustive on their sealed/enum type, so a new [NavSharePayload]
 * variant or [NavTarget] entry is a compile error here, not a silent gap.
 */
fun navTargetFlashOf(payload: NavSharePayload): NavTargetFlash =
    when (payload) {
        is NavSharePayload.Preset ->
            NavTargetFlash(
                label = ROUTING_TO,
                value =
                    when (payload.target) {
                        NavTarget.HOME -> "HOME"
                        NavTarget.MAN -> "THE MAN"
                        NavTarget.TEMPLE -> "THE TEMPLE"
                    },
            )
        is NavSharePayload.Address ->
            NavTargetFlash(
                label = ROUTING_TO,
                value = "%d:%02d".format(Locale.US, payload.clock.hours, payload.clock.minutes) + " & " + payload.ring,
            )
        NavSharePayload.Bath -> NavTargetFlash(label = ROUTING_TO, value = "NEAREST TOILET")
        // Distinct label, not "ROUTING TO CLEARED" -- there is no destination
        // to name once cleared (mirrors NavShareController.applyLocally's
        // comment: CLEAR is semantically distinct even though its effect is
        // identical to PRESET,HOME).
        NavSharePayload.Clear -> NavTargetFlash(label = "ROUTE", value = "CLEARED")
    }
