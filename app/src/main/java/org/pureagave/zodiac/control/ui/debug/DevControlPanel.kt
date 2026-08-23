package org.pureagave.zodiac.control.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pureagave.zodiac.control.core.connection.ConnectionPhase
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.sensor.gpsStatusLabel
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import org.pureagave.zodiac.control.ui.concepts.actionChip
import org.pureagave.zodiac.control.ui.concepts.themedChip
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import org.pureagave.zodiac.control.ui.wrapHeading

private val Scrim = Color(0xF2000000)
private val PanelBg = Color(0xFF010401)
private const val WIDTH_FRACTION = 0.94f
private const val HEIGHT_FRACTION = 0.88f
private const val HEADER_SP = 15

/**
 * The bench/dev controls the old `conceptControlStrip` carried, moved off the
 * cockpit and behind a hidden 5-tap trigger on the TELEMETRY box header (see
 * `ui/concepts/TelemetryBox.kt`). Every control here is fully functional and
 * calls the exact same [CockpitViewModel] entry points the strip used — this
 * is the same bench tool, just no longer standing in prime cockpit real
 * estate on every drive.
 *
 * `RECENTER` and the `TOP`/`TILT` row from the old strip are **not** here —
 * both are already covered elsewhere (an always-visible `recenterButton`
 * overlays both screens; MAP's own map tile carries a live TOP/TILT pair, and
 * both current call sites pass `showTiltToggle = false`) — see the spec's
 * per-control verdict table.
 *
 * Full-screen scrim overlay, `LogViewerPanel`'s pattern: hidden to *enter*
 * (the 5-tap code), obvious to *leave* (a visible CLOSE chip, and tapping the
 * scrim itself also closes).
 */
@Composable
fun devControlPanel(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().fillMaxHeight().background(Scrim).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(WIDTH_FRACTION)
                    .fillMaxHeight(HEIGHT_FRACTION)
                    .background(PanelBg)
                    .border(1.dp, theme.primary)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    // Swallow taps on the panel itself so they don't fall
                    // through to the scrim's onClose.
                    .clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DEV / BENCH CONTROLS",
                    color = theme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = HEADER_SP.sp,
                )
                closePanelChip(theme, onClose)
            }

            transportSection(state, viewModel, theme)
            gpsSection(state, viewModel, theme)
            headingSection(state, viewModel, theme)
            speedSection(state, viewModel, theme)
            zoomSection(state, viewModel, theme)
            if (state.selectedLocationSource == LocationSourceType.FAKE) {
                fakeGpsNudgeSection(viewModel, theme)
            }
        }
    }
}

@Composable
private fun transportSection(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sectionLabel("> TRANSPORT", theme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TransportType.entries.forEach { t ->
                themedChip(
                    label = t.name,
                    selected = state.selectedTransport == t,
                    theme = theme,
                    onClick = { viewModel.selectTransport(t) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actionChip(
                label = if (state.connectionPhase == ConnectionPhase.CONNECTED) "DISCONNECT" else "CONNECT",
                theme = theme,
                onClick = {
                    viewModel.setTransportConnected(state.connectionPhase != ConnectionPhase.CONNECTED)
                },
            )
            Text(
                text = state.connectionPhase.name,
                color = if (state.connectionPhase == ConnectionPhase.ERROR) theme.error else theme.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun gpsSection(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sectionLabel("> GPS", theme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LocationSourceType.entries.forEach { s ->
                themedChip(
                    label = s.name,
                    selected = state.selectedLocationSource == s,
                    theme = theme,
                    onClick = { viewModel.selectLocationSource(s) },
                )
            }
        }
        // The manual source override's own diagnostic: the error *category*,
        // which names the fix (PERMISSION vs ADAPTER OFF vs NO DEVICE) — the
        // cockpit's TELEMETRY status line deliberately collapses this detail,
        // so it lives here for the operator actually troubleshooting it.
        val gpsStatus = gpsStatusLabel(state.locationState)
        Text(
            text = gpsStatus.text,
            color = if (gpsStatus.fault) theme.error else theme.secondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun headingSection(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sectionLabel("> HDG ${state.headingDeg}°", theme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HEADING_STEPS.forEach { step ->
                themedChip(
                    label = if (step >= 0) "+$step" else "$step",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.setHeading(wrapHeading(state.headingDeg + step)) },
                )
            }
        }
    }
}

@Composable
private fun speedSection(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sectionLabel("> SPD ${state.speedKph} kph", theme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SPEED_STEPS.forEach { step ->
                themedChip(
                    label = if (step >= 0) "+$step" else "$step",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.setSpeed(state.speedKph + step) },
                )
            }
        }
    }
}

@Composable
private fun zoomSection(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sectionLabel("> ZOOM ${"%.2f".format(state.pixelsPerMeter)} px/m", theme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            themedChip(
                label = "ZOOM-",
                selected = false,
                theme = theme,
                onClick = { viewModel.setPixelsPerMeter(state.pixelsPerMeter / ZOOM_STEP) },
            )
            themedChip(
                label = "ZOOM+",
                selected = false,
                theme = theme,
                onClick = { viewModel.setPixelsPerMeter(state.pixelsPerMeter * ZOOM_STEP) },
            )
        }
    }
}

@Composable
private fun fakeGpsNudgeSection(
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sectionLabel("> FAKE GPS NUDGE", theme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            themedChip(
                label = "N+$NUDGE_STEP_M",
                selected = false,
                theme = theme,
                onClick = { viewModel.nudgeFakeGps(0.0, NUDGE_STEP_M.toDouble()) },
            )
            themedChip(
                label = "S+$NUDGE_STEP_M",
                selected = false,
                theme = theme,
                onClick = { viewModel.nudgeFakeGps(0.0, -NUDGE_STEP_M.toDouble()) },
            )
            themedChip(
                label = "E+$NUDGE_STEP_M",
                selected = false,
                theme = theme,
                onClick = { viewModel.nudgeFakeGps(NUDGE_STEP_M.toDouble(), 0.0) },
            )
            themedChip(
                label = "W+$NUDGE_STEP_M",
                selected = false,
                theme = theme,
                onClick = { viewModel.nudgeFakeGps(-NUDGE_STEP_M.toDouble(), 0.0) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actionChip(label = "GPS RESET", theme = theme, onClick = viewModel::resetFakeGps)
        }
    }
}

@Composable
private fun closePanelChip(
    theme: ConceptTheme,
    onClose: () -> Unit,
) {
    Text(
        text = "CLOSE",
        color = theme.error,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = HEADER_SP.sp,
        modifier =
            Modifier
                .border(1.dp, theme.error)
                .clickable(onClick = onClose)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun sectionLabel(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

private val HEADING_STEPS = listOf(-15, -1, 1, 15)
private val SPEED_STEPS = listOf(-10, -1, 1, 10)
private const val ZOOM_STEP: Double = 1.4
private const val NUDGE_STEP_M: Int = 100
