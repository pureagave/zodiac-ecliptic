package org.pureagave.zodiac.control.ui.concepts

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.pureagave.zodiac.control.core.model.MultiTapCode
import org.pureagave.zodiac.control.core.sensor.TelemetrySource
import org.pureagave.zodiac.control.core.sensor.telemetrySourceStatus
import org.pureagave.zodiac.control.core.telemetry.TickerInputs
import org.pureagave.zodiac.control.core.telemetry.TickerRole
import org.pureagave.zodiac.control.core.telemetry.TickerSegment
import org.pureagave.zodiac.control.core.telemetry.tickerLineAt
import org.pureagave.zodiac.control.ui.state.CockpitUiState

/** How often the ticker steps to its next synthesized line. */
private const val TICKER_STEP_MS = 800L

/** How many stepped lines the ticker keeps on screen before culling the oldest. */
private const val MAX_TICKER_LINES = 14

/**
 * The cockpit's TELEMETRY box — replaces the debug `conceptControlStrip` on
 * RADAR + MAP (both orientations). Always-honest by construction: the status
 * line is [telemetrySourceStatus] (pure, tested) and every ticker line is a
 * real value from [state] via [tickerLineAt] (also pure, tested) — this
 * composable draws and forwards, nothing here decides what's true.
 *
 * The header carries the hidden dev-panel trigger: 5 quick taps (
 * [MultiTapCode]) opens [org.pureagave.zodiac.control.ui.debug.devControlPanel]
 * via [onOpenDevPanel] — not a corner (all four are already spoken for) and not
 * a long-press (this needed to be discoverable-by-nobody but the operator who
 * already knows it's there).
 */
@Composable
fun telemetryBox(
    state: CockpitUiState,
    theme: ConceptTheme,
    onOpenDevPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tapCode = remember { MultiTapCode() }
    val status =
        remember(state.selectedLocationSource, state.locationState, state.gpsUsingFallback) {
            telemetrySourceStatus(state.selectedLocationSource, state.locationState, state.gpsUsingFallback)
        }
    val inputs =
        TickerInputs(
            lat = state.egoFix?.location?.lat,
            lon = state.egoFix?.location?.lon,
            fixQualityM = state.egoFix?.fixQualityM,
            // Presence gated on having an active fix at all — the value itself
            // is the folded heading CockpitUiState already carries (GPS course
            // when available, else the last known value), but a HDG line with
            // no fix behind it at all would be raw-telemetry theatre.
            headingDeg = state.egoFix?.let { state.headingDeg },
            gpsSpeedKph = state.gpsSpeedKph,
            lux = state.ambientLux,
            lastShockG = state.lastShockG,
            health = state.beaconHealth,
            odometer = state.odometer,
        )

    Column(
        modifier = modifier.border(1.dp, theme.primary).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "> TELEMETRY",
            color = theme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier =
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (tapCode.tap(System.currentTimeMillis())) onOpenDevPanel() },
                    )
                },
        )
        Text(
            text = status.text,
            color = statusColor(status.source, theme),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        telemetryTicker(
            inputs = inputs,
            chromeColor = theme.primary,
            valueColor = theme.accent,
            dimColor = theme.dim,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Colour mapping per the resolved charter: both source states are blue status; only NO_SIGNAL is a fault. */
private fun statusColor(
    source: TelemetrySource,
    theme: ConceptTheme,
): Color =
    when (source) {
        TelemetrySource.ON_BEACON, TelemetrySource.LOCAL_BACKUP, TelemetrySource.MANUAL_SOURCE -> theme.secondary
        TelemetrySource.SEARCHING -> theme.dim
        TelemetrySource.NO_SIGNAL -> theme.error
    }

/**
 * The scrolling ticker column: steps to a new synthesized line every
 * [TICKER_STEP_MS], newest at the bottom, no smooth-scroll animation
 * (stepped/terminal feel, and cheaper). Local state only — [inputs] changing
 * doesn't restart the ticker's own clock, it just changes what the *next* line
 * says, so the box's recomposition stays confined to itself.
 *
 * Exposed as its own composable (not inlined into [telemetryBox]) so the
 * passenger TELEMETRY card (Phase E) can reuse it with its own colour policy.
 */
@Composable
fun telemetryTicker(
    inputs: TickerInputs,
    chromeColor: Color,
    valueColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier,
) {
    val latestInputs = rememberUpdatedState(inputs)
    var lines by remember { mutableStateOf(listOf<List<TickerSegment>>()) }
    var seq by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = (lines + listOf(tickerLineAt(latestInputs.value, seq))).takeLast(MAX_TICKER_LINES)
            seq++
            delay(TICKER_STEP_MS)
        }
    }
    Column(modifier = modifier.clipToBounds(), verticalArrangement = Arrangement.Bottom) {
        val fadeCount = lines.size / FADE_DIVISOR
        lines.forEachIndexed { index, segments ->
            val dimmed = index < fadeCount
            Text(
                text =
                    buildAnnotatedString {
                        segments.forEachIndexed { segIndex, segment ->
                            if (segIndex > 0) append(" ")
                            val color =
                                if (dimmed) {
                                    dimColor
                                } else {
                                    when (segment.role) {
                                        TickerRole.CHROME -> chromeColor
                                        TickerRole.VALUE -> valueColor
                                    }
                                }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = color)) { append(segment.text) }
                        }
                    },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                softWrap = false,
            )
        }
    }
}

/** The oldest third of visible ticker lines dims to [Color] `dim` — a draw-only fade flourish. */
private const val FADE_DIVISOR = 3
