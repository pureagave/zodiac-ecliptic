package org.pureagave.zodiac.control.ui.concepts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pureagave.zodiac.control.core.model.DEVICE_BATTERY_LOW_PCT
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.telemetry.formatLuxValue
import org.pureagave.zodiac.control.core.telemetry.formatShockValue
import org.pureagave.zodiac.control.core.telemetry.luxBarFraction
import org.pureagave.zodiac.control.core.telemetry.shockBarFraction
import org.pureagave.zodiac.control.ui.debug.devControlPanel
import org.pureagave.zodiac.control.ui.ops.OPS_FOOTER_ONE_LINE
import org.pureagave.zodiac.control.ui.ops.OPS_FOOTER_TWO_LINE
import org.pureagave.zodiac.control.ui.ops.driveSelectionOf
import org.pureagave.zodiac.control.ui.ops.driveToBar
import org.pureagave.zodiac.control.ui.ops.headingGuidanceBar
import org.pureagave.zodiac.control.ui.ops.opsReadout
import org.pureagave.zodiac.control.ui.playamap.MapPalette
import org.pureagave.zodiac.control.ui.playamap.MapPointStyle
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import kotlin.math.cos
import kotlin.math.sin

/**
 * The TELEMETRY box's minimum legible height — enough for the header, the
 * status line and a few ticker lines. Matches `MotionTrackerScreen`'s
 * constant of the same name so both concepts read the same.
 */
private val TELEMETRY_BOX_MIN_HEIGHT = 170.dp

/**
 * Concept D map palette: BLOCK point style so toilets, CPNs, and art render
 * as small framed rects that read like miniature instrument tiles scattered
 * across the map. Recoloured to the green phosphor family 2026-07-04 (was
 * orange); the BLOCK styling is what keeps D distinct from Concept A.
 */
private val InstrumentBayPalette =
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
        pointStyle = MapPointStyle.BLOCK,
        labelsEnabled = true,
        labelPrimary = Color(0xFFB0FFB0),
        crtBeam = true,
    )

@Composable
fun instrumentBayScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapInputs by remember { derivedStateOf { MapUiInputs.from(state) } }
    val theme = ThemeInstrumentBay
    val config = LocalConfiguration.current
    val portrait = config.screenHeightDp > config.screenWidthDp
    // Screen-local ephemeral UI state — deliberately not CockpitUiState, not
    // persisted: a reboot must never come up with the bench panel open.
    var devPanelOpen by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(12.dp)
                .border(2.dp, theme.primary),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The column's own 8 dp padding already counts toward the clearance.
            navCueBar(cue = state.navCue, theme = theme, modifier = Modifier.padding(end = CONCEPT_SWITCHER_CLEARANCE - 8.dp))
            // Top strip: live heading-guidance chevron to the active drive-to
            // target (replaced the decorative NOSTROMO/STATION flavour header) —
            // slide + point shows which way to steer to HOME/MAN/TEMPLE/BATH.
            Row(modifier = Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tile(theme = theme, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    headingGuidanceBar(
                        theme = theme,
                        egoFix = state.egoFix,
                        headingDeg = state.headingDeg,
                        target = state.activeDriveTarget,
                        aim = state.nextWaypoint,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (portrait) {
                // Portrait: map on top, gauges + cells in a row, controls below.
                Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bayMapTile(mapInputs, viewModel, state, theme, Modifier.fillMaxWidth().weight(1f))
                    // 4 tiles, not 3: the trace tile is new (Phase B) and gives
                    // portrait a speed representation now that speedTile is gone
                    // (Phase C) — without stealing any map height.
                    Row(modifier = Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        headingTile(theme, state.headingDeg, Modifier.weight(1f).fillMaxHeight())
                        ambientShockTile(
                            theme = theme,
                            lux = state.ambientLux,
                            lastShockG = state.lastShockG,
                            shockAlertActive = state.shockAlertG != null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        cellsTile(theme, state.deviceBatteryPct, Modifier.weight(1f).fillMaxHeight())
                        traceTile(theme, state.speedTrail.window(System.currentTimeMillis()), Modifier.weight(1f).fillMaxHeight())
                    }
                    telemetryBox(
                        state = state,
                        theme = theme,
                        onOpenDevPanel = { devPanelOpen = true },
                        modifier = Modifier.fillMaxWidth().height(TELEMETRY_BOX_MIN_HEIGHT),
                    )
                }
            } else {
                // Landscape: left gauges | centre map | right throttle/cells/controls.
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        modifier = Modifier.fillMaxHeight().width(160.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        headingTile(theme, state.headingDeg, Modifier.fillMaxWidth().weight(1f))
                        ambientShockTile(
                            theme = theme,
                            lux = state.ambientLux,
                            lastShockG = state.lastShockG,
                            shockAlertActive = state.shockAlertG != null,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                    bayMapTile(mapInputs, viewModel, state, theme, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier.fillMaxHeight().width(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        traceTile(theme, state.speedTrail.window(System.currentTimeMillis()), Modifier.fillMaxWidth().height(120.dp))
                        // 108dp (was 80dp): the label-on-background redesign
                        // needs two lines for CELL B's pending marker plus a
                        // bar under each row — 80dp clipped it.
                        cellsTile(theme, state.deviceBatteryPct, Modifier.fillMaxWidth().height(108.dp))
                        // weight(fill = false) + heightIn floor: takes the
                        // rest of this column's height (there's nothing else
                        // below it) but can never be squeezed under a
                        // legible minimum — see the RADAR-portrait bug this
                        // mirrors the fix for (MotionTrackerScreen.kt).
                        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = TELEMETRY_BOX_MIN_HEIGHT)) {
                            telemetryBox(
                                state = state,
                                theme = theme,
                                onOpenDevPanel = { devPanelOpen = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // Passenger/non-authority displays (the Fires) never show the
            // routing buttons — enabled=false on a non-authority device would
            // just be a dead control, and this project omits those rather
            // than dims them. Authority devices (hero + driver) are unchanged.
            if (state.navAuthority) {
                driveToBar(
                    theme = theme,
                    active = driveSelectionOf(state.customTarget != null, state.driveToBath, state.navTarget),
                    onSelect = viewModel::setNavTarget,
                    onSelectBath = viewModel::driveToNearestToilet,
                    onOpenAddress = { viewModel.setAddressEntryOpen(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Operational readout footer: BRC clock / sunrise-sunset / return-to-camp,
            // in the bay's tile aesthetic (replaced the decorative hazard chevron).
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
                        // Grows for the beacon line rather than flexing, so the
                        // tile wall's layout stays predictable on the Fire.
                        .height(if (state.beaconReadout.any) OPS_FOOTER_TWO_LINE else OPS_FOOTER_ONE_LINE)
                        .border(2.dp, theme.primary)
                        .padding(horizontal = 12.dp),
            )
        }

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

@Composable
private fun tile(
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.border(2.dp, theme.primary).padding(8.dp)) { content() }
}

/** The centre map tile (title + TOP/TILT toggle + BRC ground-track), sized by [modifier]. */
@Composable
private fun bayMapTile(
    mapInputs: MapUiInputs,
    viewModel: CockpitViewModel,
    state: CockpitUiState,
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
) {
    tile(theme = theme, modifier = modifier) {
        val zoomLabel = remember(state.pixelsPerMeter) { "%.2f".format(state.pixelsPerMeter) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "GROUND TRACK // ZOOM $zoomLabel px/m",
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                themedChip("TOP", state.mapMode == MapMode.TOP, theme) { viewModel.setMapMode(MapMode.TOP) }
                themedChip("TILT", state.mapMode == MapMode.TILT, theme) { viewModel.setMapMode(MapMode.TILT) }
            }
        }
        // clipToBounds: a Compose Canvas does not clip, so without it the city
        // drew straight across the neighbouring tiles and the header.
        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp).clipToBounds()) {
            playaMapPanel(
                inputs = mapInputs,
                viewModel = viewModel,
                style =
                    PlayaMapPanelStyle(
                        palette = InstrumentBayPalette,
                        egoStyle = EgoStyle.TRIANGLE,
                        egoColor = theme.accent,
                        allowTilt = true,
                        showRetroGrid = false,
                        routeColor = theme.secondary,
                    ),
                modifier = Modifier.fillMaxSize(),
            )
            // Inside the map tile, so it can never land on the drive-to bar
            // (it used to float over the screen and covered ADDR).
            recenterButton(
                followMode = state.followMode,
                theme = theme,
                onClick = viewModel::recenterPan,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

@Composable
private fun headingTile(
    theme: ConceptTheme,
    headingDeg: Int,
    modifier: Modifier = Modifier,
) {
    tile(theme = theme, modifier = modifier) {
        Text("HEADING", color = theme.primary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            headingDial(theme = theme, headingDeg = headingDeg)
        }
    }
}

/**
 * Replaces the old prop speed gauge (COCKPIT-TELEMETRY-REDESIGN Phase C) with
 * two live-sensor mini-readouts: ambient lux (`$ZENV`) and the persistent last
 * shock peak (`$ZSHK`). Absence renders "—" and no bar fill, never a fake 0 —
 * [formatLuxValue]/[formatShockValue]/[luxBarFraction]/[shockBarFraction] (all
 * pure, in `core/telemetry/EnvGaugeModel.kt`) own that decision; this
 * composable only draws what they return.
 */
@Composable
private fun ambientShockTile(
    theme: ConceptTheme,
    lux: Double?,
    lastShockG: Double?,
    shockAlertActive: Boolean,
    modifier: Modifier = Modifier,
) {
    tile(theme = theme, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            envReadout(
                theme = theme,
                label = "LUX",
                value = formatLuxValue(lux),
                barFraction = luxBarFraction(lux),
                valueColor = theme.accent,
            )
            envReadout(
                theme = theme,
                label = "SHOCK",
                // A fresh jolt (the 2 s shockAlertG window) flashes red; a
                // settled/absent reading stays the ordinary live-data purple.
                value = formatShockValue(lastShockG),
                barFraction = shockBarFraction(lastShockG),
                valueColor = if (shockAlertActive) theme.error else theme.accent,
            )
        }
    }
}

/** One `LABEL  VALUE` row + a thin bar underneath; no bar fill when [barFraction] is null. */
@Composable
private fun envReadout(
    theme: ConceptTheme,
    label: String,
    value: String,
    barFraction: Float?,
    valueColor: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = theme.primary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text(
                value,
                color = valueColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(ENV_BAR_HEIGHT)
                    .border(1.dp, theme.primary),
        ) {
            if (barFraction != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = theme.accent,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width * barFraction, size.height / 2f),
                        strokeWidth = size.height,
                    )
                }
            }
        }
    }
}

/**
 * CELL A = this device's real battery ([org.pureagave.zodiac.control.core.model.batteryPercent]
 * via [org.pureagave.zodiac.control.data.power.DeviceBatterySource]); CELL B =
 * a static 50% Victron placeholder, visibly dimmed so it can't be mistaken for
 * a live reading next to the real A (COCKPIT-TELEMETRY-REDESIGN Phase D — the
 * earlier pseudo-random `CellGauge` prop model is gone).
 */
@Composable
private fun cellsTile(
    theme: ConceptTheme,
    deviceBatteryPct: Int?,
    modifier: Modifier = Modifier,
) {
    tile(theme = theme, modifier = modifier) {
        cellBar(theme = theme, label = "CELL A", pct = deviceBatteryPct, live = true)
        Spacer(Modifier.height(6.dp))
        // The Victron drop-in shape: when the GX feed lands, this call site
        // becomes (…, victronSocPct, live = true) and the pending treatment
        // disappears — no tile redesign. Explicit two-line label (rather than
        // letting "CELL B · VEHICLE PENDING" auto-wrap) so the break point is
        // always clean and "VEHICLE PENDING" is never truncated.
        cellBar(theme = theme, label = "CELL B\nVEHICLE PENDING", pct = VICTRON_PLACEHOLDER_PCT, live = false)
    }
}

@Composable
private fun headingDial(
    theme: ConceptTheme,
    headingDeg: Int,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.width.coerceAtMost(size.height) / 2f - 4f
        drawCircle(theme.primary, r, Offset(cx, cy), style = Stroke(width = 1.6f))
        drawCircle(theme.primary, r * 0.78f, Offset(cx, cy), style = Stroke(width = 1f))
        // Cardinal ticks
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0 - 90.0)
            val outer = Offset((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            val inner = Offset((cx + (r - 8f) * cos(a)).toFloat(), (cy + (r - 8f) * sin(a)).toFloat())
            drawLine(theme.primary, outer, inner, strokeWidth = 1.4f)
        }
        // Needle
        val needle = Math.toRadians(headingDeg.toDouble() - 90.0)
        drawLine(
            color = theme.accent,
            start = Offset(cx, cy),
            end =
                Offset(
                    (cx + r * 0.86f * cos(needle)).toFloat(),
                    (cy + r * 0.86f * sin(needle)).toFloat(),
                ),
            strokeWidth = 3f,
        )
        drawCircle(theme.accent, 4f, Offset(cx, cy))
    }
}

/** The bordered "THROTTLE TRACE" tile — label + [throttleTrace], shared by both orientations. */
@Composable
private fun traceTile(
    theme: ConceptTheme,
    window: List<Float?>,
    modifier: Modifier = Modifier,
) {
    tile(theme = theme, modifier = modifier) {
        Text("THROTTLE TRACE", color = theme.primary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
            throttleTrace(theme = theme, window = window)
        }
    }
}

/**
 * The real 30-minute speed graph (COCKPIT-TELEMETRY-REDESIGN Phase B) —
 * replaces the old hard-coded 21-point literal array. [window] is
 * [org.pureagave.zodiac.control.core.telemetry.SpeedTrail.window]'s fixed
 * [org.pureagave.zodiac.control.core.telemetry.SpeedTrail.WINDOW_BUCKETS]-slot
 * list; this composable only maps it to pixels — the bucketing, clamping and
 * eviction already happened in core. A `null` slot **breaks** the polyline
 * (no interpolation/coasting, DECIDED) rather than drawing a fabricated
 * straight line across a gap in the data.
 */
@Composable
private fun throttleTrace(
    theme: ConceptTheme,
    window: List<Float?>,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val axisMax = CockpitUiState.MAX_SPEED_KPH.toFloat()
        // Dim chrome gridlines at 40/80/120 kph out of the fixed 0..160 axis.
        for (kph in TRACE_GRIDLINES_KPH) {
            val y = h * (1f - kph / axisMax)
            drawLine(theme.dim, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (window.size < 2) return@Canvas
        val dx = w / (window.size - 1)
        for (i in 0 until window.size - 1) {
            val a = window[i]
            val b = window[i + 1]
            if (a == null || b == null) continue
            drawLine(
                color = theme.accent,
                start = Offset(i * dx, h * (1f - a / axisMax)),
                end = Offset((i + 1) * dx, h * (1f - b / axisMax)),
                strokeWidth = 2f,
            )
        }
    }
}

private val TRACE_GRIDLINES_KPH = listOf(40f, 80f, 120f)

/**
 * Label + value drawn on the tile's plain black background, with a separate
 * **thin** bar underneath carrying the fill — never text overlaid on the bar.
 * On-device (A54, 2026-08-26) the previous text-on-fill design was illegible
 * at both extremes: white/green text vanished into a ~100%-full green bar,
 * and CELL B's longer label overlapped/truncated against its own fill
 * ("L B"). Text-on-background is legible at any fill level; the bar is the
 * only thing that moves.
 *
 * `pct` drives the fill fraction; `live` picks the charter colour (green for
 * a real reading, red at/under [DEVICE_BATTERY_LOW_PCT], dimmed for the
 * Victron placeholder) and whether a percent is drawn at all — a static
 * placeholder shows no number (a number invites belief). [label] may contain
 * `\n` for a deliberate two-line break (CELL B's pending marker needs the
 * room; CELL A's short label never wraps).
 */
@Composable
private fun cellBar(
    theme: ConceptTheme,
    label: String,
    pct: Int?,
    live: Boolean,
) {
    val low = live && pct != null && pct <= DEVICE_BATTERY_LOW_PCT
    val color =
        when {
            !live -> theme.dim
            low -> theme.error
            else -> theme.primary
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = if (live) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Text(
                    text = pct?.let { "$it%" } ?: "—",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .height(CELL_BAR_HEIGHT)
                    .border(1.dp, theme.primary),
        ) {
            if (pct != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val frac = (pct / 100f).coerceIn(0f, 1f)
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width * frac, size.height / 2f),
                        strokeWidth = size.height,
                    )
                }
            }
        }
    }
}

/** Static Victron SoC stand-in until the GX Modbus-TCP/MQTT feed lands — see [cellsTile]'s KDoc. */
private const val VICTRON_PLACEHOLDER_PCT = 50

private val ENV_BAR_HEIGHT = 6.dp
private val CELL_BAR_HEIGHT = 5.dp
