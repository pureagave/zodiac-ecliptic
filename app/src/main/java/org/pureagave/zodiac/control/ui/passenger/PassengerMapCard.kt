package org.pureagave.zodiac.control.ui.passenger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import org.pureagave.zodiac.control.core.passenger.passengerMapStartZoom
import org.pureagave.zodiac.control.ui.RetroFont
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import org.pureagave.zodiac.control.ui.concepts.EgoStyle
import org.pureagave.zodiac.control.ui.concepts.MapUiInputs
import org.pureagave.zodiac.control.ui.concepts.PlayaMapPanelStyle
import org.pureagave.zodiac.control.ui.concepts.playaMapPanel
import org.pureagave.zodiac.control.ui.concepts.recenterButton
import org.pureagave.zodiac.control.ui.playamap.MapPalette
import org.pureagave.zodiac.control.ui.playamap.MapPointStyle
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel

/**
 * The passenger carousel's interactive live map: track-up, auto-following,
 * centred on the vehicle — until a rider touches it. Pan and pinch-zoom are
 * wanted here (riders are not the driver, so the "no head-down study" rule
 * that binds the DRIVER HUD doesn't apply); a two-finger twist is not, since
 * heading alone must drive the track-up rotation or a manual twist would
 * fight auto-follow the moment the vehicle turns
 * ([PlayaMapPanelStyle.allowRotate] = false).
 *
 * **Reuses the cockpit's camera stack wholesale** — [viewModel]'s
 * [CockpitViewModel] camera API plus [playaMapPanel] — rather than a parallel
 * passenger camera; see `design/PASSENGER-UI-split-spec.md`'s Phase 2 reuse
 * decision for why. The compensating control for holding a `CockpitViewModel`
 * reference on a passenger screen: this file reaches it *only* through
 * [playaMapPanel]'s own pan/zoom wiring plus [CockpitViewModel.recenterPan]
 * and [CockpitViewModel.setPixelsPerMeter] for the reset-on-entry and
 * recenter button below — never the nav, GPS, or concept-switching APIs — and
 * renders no control beyond the recenter button. Every *decision* (starting
 * zoom, the touch-hold that pauses the carousel) lives in the pure, tested
 * `core/passenger/PassengerMapCardModel.kt`; everything here is draw or
 * forward-what-core-decided.
 *
 * Tradeoffs accepted, per the spec:
 *  - The cockpit's 60 s idle auto-revert
 *    ([org.pureagave.zodiac.control.core.model.AUTO_RECENTER_MS]) still runs
 *    underneath this card. Harmless here (a walked-away-from card re-follows
 *    within a minute, even mid-dwell) and *not* the carousel-resume
 *    mechanism — that's [onMapTouch] feeding
 *    [org.pureagave.zodiac.control.core.passenger.mapTouchHoldActive]'s much
 *    shorter grace, in `PassengerScreen.kt`'s ticker.
 *  - Zoom set here persists as the cockpit's own zoom preference
 *    ([CockpitViewModel.setPixelsPerMeter] writes the same DataStore key the
 *    cockpit reads). A rider's pinch on a passenger Fire therefore rewrites
 *    that device's persisted cockpit zoom — accepted: harmless on a dedicated
 *    passenger tablet, always bounded, and recoverable by one pinch if the
 *    tablet is ever flipped back to cockpit duty.
 *  - [playaMapPanel]'s map-load-error RETRY overlay can never draw here: a
 *    failed load means `playaMap == null`, and this card's `hasFix && hasMap`
 *    availability gate (`core/passenger/PassengerData.kt`) has already
 *    dropped it out of the rotation before this composable runs.
 */
@Composable
fun mapCard(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
    /** Raw touch telemetry for the carousel hold; the hold *decision* is core's. */
    onMapTouch: (pointerDown: Boolean) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Runs once per carousel entry — a `when` branch composing this function
    // fresh is its own composition slot, so keying on Unit here means "on
    // arrival at this card," not "on first app launch." Recenters immediately
    // (pan + rotation), then waits for the panel's first real measured size to
    // apply the starting zoom exactly once — passengerMapStartZoom returns
    // null until sized, and `.first` is the latch that stops it re-applying
    // on every later resize (e.g. an orientation flip while the card is up).
    LaunchedEffect(Unit) {
        viewModel.recenterPan()
        val sized = snapshotFlow { canvasSize }.first { it.width > 0 && it.height > 0 }
        passengerMapStartZoom(sized.width, sized.height)?.let(viewModel::setPixelsPerMeter)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    // Unconsumed observer: watches raw finger down/up on the
                    // Initial pass so it sees every event without stealing it
                    // from cockpitTouchInput's Main-pass handling inside
                    // playaMapPanel below. Plumbing only, per the spec — it
                    // decides nothing; mapTouchHoldActive in core does.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            onMapTouch(event.changes.any { it.pressed })
                        }
                    }
                },
    ) {
        playaMapPanel(
            inputs = MapUiInputs.from(state),
            viewModel = viewModel,
            style =
                PlayaMapPanelStyle(
                    palette = PassengerMapPalette,
                    egoStyle = EgoStyle.TRIANGLE,
                    // Purple: live data, per the colour system — same family as
                    // the cockpit's own ego marker.
                    egoColor = theme.accent,
                    // Always top-down, the RADAR precedent — no TILT on the
                    // passenger map.
                    allowTilt = false,
                    allowRotate = false,
                ),
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = "BLACK ROCK CITY",
            color = theme.primary,
            fontFamily = RetroFont,
            fontSize = CAPTION_SP.sp,
            letterSpacing = CAPTION_LETTER_SPACING.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = CAPTION_TOP_PADDING.dp),
        )

        recenterButton(
            followMode = state.followMode,
            theme = theme,
            onClick = {
                // Passenger recenter resets pan + rotation (recenterPan, same
                // as the cockpit button) AND zoom back to the starting span
                // (the cockpit's own button leaves zoom alone) — resolved open
                // question 3. The touch stamp makes the resume grace run from
                // the tap too, same as if a finger had just lifted.
                viewModel.recenterPan()
                passengerMapStartZoom(canvasSize.width, canvasSize.height)?.let(viewModel::setPixelsPerMeter)
                onMapTouch(false)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(RECENTER_PADDING.dp),
        )
    }
}

/**
 * Palette copied from `InstrumentBayScreen.kt`'s green-family values with two
 * passenger-specific swaps: DOT point style (BLOCK is concept D's own look)
 * and CRT beam on for the same phosphor feel the rest of the passenger
 * carousel doesn't otherwise use but which reads well full-bleed here.
 */
private val PassengerMapPalette =
    MapPalette(
        fence = Color(0xFF00FF66),
        street = Color(0xFF1F8F46),
        streetOutline = Color(0xFF0F5C2D),
        plaza = Color(0xFFC77DFF),
        toilet = Color(0xFF00BFFF),
        cpn = Color(0xFF00FF66),
        artMajor = Color(0xFFFF66CC),
        artMinor = Color(0xFF80366A),
        grid = Color(0xFF1F6E37),
        pointStyle = MapPointStyle.DOT,
        labelsEnabled = true,
        labelPrimary = Color(0xFFB0FFB0),
        crtBeam = true,
    )

private const val CAPTION_SP = 16
private const val CAPTION_LETTER_SPACING = 4
private const val CAPTION_TOP_PADDING = 14
private const val RECENTER_PADDING = 16
