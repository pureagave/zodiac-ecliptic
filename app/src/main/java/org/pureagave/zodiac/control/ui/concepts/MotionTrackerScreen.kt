package org.pureagave.zodiac.control.ui.concepts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.ui.debug.devControlPanel
import org.pureagave.zodiac.control.ui.ops.OPS_FOOTER_ONE_LINE
import org.pureagave.zodiac.control.ui.ops.OPS_FOOTER_TWO_LINE
import org.pureagave.zodiac.control.ui.ops.driveSelectionOf
import org.pureagave.zodiac.control.ui.ops.driveToBar
import org.pureagave.zodiac.control.ui.ops.headingGuidanceBar
import org.pureagave.zodiac.control.ui.ops.opsReadout
import org.pureagave.zodiac.control.ui.playamap.MapPalette
import org.pureagave.zodiac.control.ui.scanlineOverlay
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel

private const val SWEEP_PERIOD_S = 4f
private const val SWEEP_WIDTH_DEG = 60f
private const val FORWARD_CONE_DEG = 70f

/**
 * The TELEMETRY box's minimum legible height — enough for the header, the
 * status line and a few ticker lines. Matches `InstrumentBayScreen`'s
 * already-working portrait fixed height so both concepts read the same.
 */
private val TELEMETRY_BOX_MIN_HEIGHT = 170.dp

/**
 * Dim base palette — features barely show until the sweep arm lights them up.
 * Skeletal city outline only, so the M41A "blip on a dark scope" effect reads.
 */
private val TrackerBasePalette =
    MapPalette(
        fence = Color(0xFF1E5C16),
        street = Color(0xFF124210),
        streetOutline = Color(0xFF0A2A0A),
        plaza = Color(0xFF2A5C16),
        toilet = Color(0xFF1E5C16),
        cpn = Color(0xFF1E5C16),
        artMajor = Color(0xFF2A5C16),
        artMinor = Color(0xFF124210),
        grid = Color(0xFF0A2A0A),
        crtBeam = true,
    )

/** Lit palette — what the wedge re-renders inside its clip region. */
private val TrackerLitPalette =
    MapPalette(
        fence = Color(0xFFB8FF98),
        street = Color(0xFF7EFF62),
        streetOutline = Color(0xFF3AAA2C),
        plaza = Color(0xFFC77DFF),
        toilet = Color(0xFF00BFFF),
        cpn = Color(0xFF7EFF62),
        artMajor = Color(0xFFFF66CC),
        artMinor = Color(0xFF7EFF62),
        grid = Color(0xFF3AAA2C),
        crtBeam = true,
    )

@Composable
fun motionTrackerScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapInputs by remember { derivedStateOf { MapUiInputs.from(state) } }
    val theme = ThemeTracker
    val config = LocalConfiguration.current
    val portrait = config.screenHeightDp > config.screenWidthDp
    // Screen-local ephemeral UI state — deliberately not CockpitUiState, not
    // persisted: a reboot must never come up with the bench panel open.
    var devPanelOpen by remember { mutableStateOf(false) }

    // Backing state for the sweep angle. Deliberately NOT read in this
    // composable's body — only the map/arm draw lambdas read it (via the
    // SweepOverlay.sweepDeg lambda below), so the 60 fps frame ticker
    // invalidates just the draw phase. Reading sweepDeg.floatValue here
    // would recompose the whole screen (and every String.format) per frame.
    val sweepDeg = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    sweepDeg.floatValue = (sweepDeg.floatValue + dt * 360f / SWEEP_PERIOD_S) % 360f
                }
                last = now
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(12.dp)
                .border(2.dp, theme.primary),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(start = 14.dp, end = CONCEPT_SWITCHER_CLEARANCE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "MOTION TRACKER",
                    color = theme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("M41A / ZODIAC BAY", color = theme.primary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        "RNG ${"%.1f".format(rangeMetres(state.pixelsPerMeter))}m",
                        color = theme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            navCueBar(cue = state.navCue, theme = theme)
            Spacer(Modifier.height(4.dp))
            headingGuidanceBar(
                theme = theme,
                egoFix = state.egoFix,
                headingDeg = state.headingDeg,
                target = state.activeDriveTarget,
                aim = state.nextWaypoint,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, theme.primary)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))

            val bearingSpeed =
                StatPair("BEARING", "%03d".format(state.headingDeg), "SPEED", "%03d".format(state.speedKph))
            val rangeZoom =
                StatPair("RANGE", "%.0fm".format(rangeMetres(state.pixelsPerMeter)), "ZOOM", "%.2f".format(state.pixelsPerMeter))

            if (portrait) {
                // Portrait: scope shrinks to whatever's left (weighted, like
                // the landscape scope below) once the stat row and the
                // TELEMETRY box's fixed floor height are subtracted. The
                // scope previously forced a full-width square via
                // `fillMaxWidth().aspectRatio(1f)`, which on a tall-narrow
                // portrait screen exceeded the whole column's height budget
                // on its own and starved the (weight(1f)) TELEMETRY box below
                // it to 0dp — confirmed on the A54. Fixed height here mirrors
                // `InstrumentBayScreen`'s already-working portrait treatment.
                Column(Modifier.weight(1f)) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f)) {
                            radarScope(
                                mapInputs = mapInputs,
                                viewModel = viewModel,
                                theme = theme,
                                sweepDeg = { sweepDeg.floatValue },
                                activeTargetLocation = state.activeDriveTarget?.location,
                                modifier = Modifier.fillMaxSize(),
                            )
                            scopeRecenterButton(state, viewModel, theme)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        trackerStatColumn(pair = bearingSpeed, theme = theme, modifier = Modifier.weight(1f))
                        trackerStatColumn(pair = rangeZoom, theme = theme, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    telemetryBox(
                        state = state,
                        theme = theme,
                        onOpenDevPanel = { devPanelOpen = true },
                        modifier = Modifier.fillMaxWidth().height(TELEMETRY_BOX_MIN_HEIGHT),
                    )
                }
            } else {
                // Landscape: left stats | centre scope | right stats + controls.
                Row(Modifier.weight(1f)) {
                    trackerStatColumn(pair = bearingSpeed, theme = theme, modifier = Modifier.fillMaxHeight().width(170.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f)) {
                            radarScope(
                                mapInputs = mapInputs,
                                viewModel = viewModel,
                                theme = theme,
                                sweepDeg = { sweepDeg.floatValue },
                                activeTargetLocation = state.activeDriveTarget?.location,
                                modifier = Modifier.fillMaxSize(),
                            )
                            scopeRecenterButton(state, viewModel, theme)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(
                        modifier = Modifier.fillMaxHeight().width(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        trackerStatColumn(pair = rangeZoom, theme = theme, modifier = Modifier.fillMaxWidth())
                        // weight(fill = false) + heightIn floor: takes the
                        // rest of this column's height (there's nothing else
                        // below it) but can never be squeezed under a legible
                        // minimum, which a plain unweighted/unbounded modifier
                        // here left undefined (see the portrait bug above).
                        telemetryBox(
                            state = state,
                            theme = theme,
                            onOpenDevPanel = { devPanelOpen = true },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = TELEMETRY_BOX_MIN_HEIGHT),
                        )
                    }
                }
            }

            // Passenger/non-authority displays (the Fires) never show the
            // routing buttons — enabled=false on a non-authority device would
            // just be a dead control, and this project omits those rather
            // than dims them. Authority devices (hero + driver) are unchanged.
            // The flanking Spacer stays outside the guard so the gap before
            // opsReadout is identical whether or not the bar renders.
            if (state.navAuthority) {
                Spacer(Modifier.height(6.dp))
                driveToBar(
                    theme = theme,
                    active = driveSelectionOf(state.customTarget != null, state.driveToBath, state.navTarget),
                    onSelect = viewModel::setNavTarget,
                    onSelectBath = viewModel::driveToNearestToilet,
                    onOpenAddress = { viewModel.setAddressEntryOpen(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            opsReadout(
                theme = theme,
                egoFix = state.egoFix,
                headingDeg = state.headingDeg,
                target = state.activeDriveTarget,
                aim = state.nextWaypoint,
                beacon = state.beaconReadout,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(if (state.beaconReadout.any) OPS_FOOTER_TWO_LINE else OPS_FOOTER_ONE_LINE)
                        .border(1.dp, theme.primary)
                        .padding(horizontal = 14.dp),
            )
        }

        scanlineOverlay()

        conceptSwitcher(
            current = state.concept,
            onCycle = onCycleConcept,
            accent = theme.primary,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        if (devPanelOpen) {
            devControlPanel(state = state, viewModel = viewModel, theme = theme, onClose = { devPanelOpen = false })
        }
    }
}

/**
 * The recenter button, in the bottom-right corner of the scope's square, the
 * empty space outside the circle. It used to float over the whole screen and
 * landed on the drive-to bar's ADDR button.
 */
@Composable
private fun BoxScope.scopeRecenterButton(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    recenterButton(
        followMode = state.followMode,
        theme = theme,
        onClick = viewModel::recenterPan,
        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
    )
}

/** The M41A scope (bordered circular map with sweep + contacts), sized by [modifier]. */
@Composable
private fun radarScope(
    mapInputs: MapUiInputs,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
    sweepDeg: () -> Float,
    activeTargetLocation: LatLon?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.border(2.dp, theme.primary)) {
        playaMapPanel(
            inputs = mapInputs,
            viewModel = viewModel,
            style =
                PlayaMapPanelStyle(
                    palette = TrackerBasePalette,
                    egoStyle = EgoStyle.TRIANGLE,
                    egoColor = theme.accent,
                    allowTilt = false,
                    clipCircular = true,
                    lockCameraToEgo = true,
                    routeColor = theme.secondary,
                    sweep =
                        SweepOverlay(
                            sweepDeg = sweepDeg,
                            sweepWidthDeg = SWEEP_WIDTH_DEG,
                            litPalette = TrackerLitPalette,
                            armColor = theme.primary,
                            coneFwdDeg = FORWARD_CONE_DEG,
                            coneFill = Color(0x267EFF62),
                        ),
                    contacts =
                        ContactsOverlay(
                            // Match the lit map's own encoding: art in pink, camps in
                            // data-purple; active target in status-blue.
                            artColor = TrackerLitPalette.artMajor,
                            campColor = DataPurple,
                            targetColor = StatusBlue,
                            target = activeTargetLocation,
                            sweepDeg = sweepDeg,
                        ),
                ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private data class StatPair(val title1: String, val value1: String, val title2: String, val value2: String)

@Composable
private fun trackerStatColumn(
    pair: StatPair,
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.border(1.dp, theme.primary).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(pair.title1, color = theme.primary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(pair.value1, color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(pair.title2, color = theme.primary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(pair.value2, color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Approx. on-screen radius (in playa metres) covered by the scope at the
 * current zoom — surfaced as the "RNG" header so the M41A's metre callout
 * actually reflects the visible map sector.
 */
private fun rangeMetres(pixelsPerMeter: Double): Float = (300.0 / pixelsPerMeter).toFloat()
