package org.pureagave.zodiac.control.ui.passenger

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.pureagave.zodiac.control.core.sensor.TelemetrySource
import org.pureagave.zodiac.control.core.sensor.telemetrySourceStatus
import org.pureagave.zodiac.control.core.telemetry.TickerInputs
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import org.pureagave.zodiac.control.ui.concepts.telemetryTicker
import org.pureagave.zodiac.control.ui.state.CockpitUiState

/**
 * [org.pureagave.zodiac.control.core.passenger.PassengerCard.TELEMETRY] — the
 * same source-status line and scrolling ticker the cockpit's TELEMETRY box
 * shows (`ui/concepts/TelemetryBox.kt`), reused wholesale for the rider who
 * asks what all the numbers are.
 *
 * New file rather than an addition to `PassengerCards.kt` — split-don't-grow,
 * same precedent as `PassengerMapCard.kt`.
 *
 * Every decision here is Phase A's, already pure and already tested:
 * [telemetrySourceStatus] derives the status line, [telemetryTicker] draws the
 * synthesized lines from a [TickerInputs] built by copying [state] fields —
 * exactly the plumbing `telemetryBox` in `TelemetryBox.kt` does, not logic
 * (the `passengerInputs` precedent). Nothing new is decided here except the
 * colour policy below, and that policy is a straight colour lookup, the same
 * class of draw-only decision `TelemetryBox.kt`'s own (untested) `statusColor`
 * already is — not a new availability or formatting rule.
 *
 * The one thing that *is* different from the cockpit box: the passenger
 * screen's "no collision flags, no braking, no alarm red" doctrine
 * (`PassengerScreen.kt`'s KDoc) outranks the cockpit's colour table on this
 * surface, so [passengerStatusColor] renders `NO_SIGNAL` as
 * [ConceptTheme.dim] rather than [ConceptTheme.error]. In practice a rider
 * rarely sees it: [org.pureagave.zodiac.control.core.passenger.availablePassengerCards]
 * already pulls this card out of the rotation the moment neither a fix nor any
 * beacon channel is alive, so a lingering fault message is not what
 * `NO_SIGNAL` would mean here — it would mean "the position source dropped out
 * mid-card, but a beacon channel (lux, odometer) is still keeping the card up."
 */
@Composable
fun telemetryCard(
    state: CockpitUiState,
    theme: ConceptTheme,
) {
    val status = telemetrySourceStatus(state.selectedLocationSource, state.locationState, state.gpsUsingFallback)
    val inputs =
        TickerInputs(
            lat = state.egoFix?.location?.lat,
            lon = state.egoFix?.location?.lon,
            fixQualityM = state.egoFix?.fixQualityM,
            headingDeg = state.egoFix?.let { state.headingDeg },
            gpsSpeedKph = state.gpsSpeedKph,
            lux = state.ambientLux,
            lastShockG = state.lastShockG,
            health = state.beaconHealth,
            odometer = state.odometer,
        )

    passengerCardFrame(
        theme = theme,
        label = "VEHICLE TELEMETRY",
        value = status.text,
        valueColor = passengerStatusColor(status.source, theme),
        footnote = "LIVE FROM THE FLEET BUS",
    ) {
        telemetryTicker(
            inputs = inputs,
            chromeColor = theme.primary,
            valueColor = theme.accent,
            dimColor = theme.dim,
            modifier = Modifier.fillMaxWidth(TICKER_WIDTH_FRACTION).height(TICKER_HEIGHT),
        )
    }
}

/**
 * Passenger colour policy for [TelemetrySource]: the cockpit's mapping
 * (`TelemetryBox.kt`'s `statusColor`) minus red. `ON_BEACON`/`LOCAL_BACKUP`/
 * `MANUAL_SOURCE` stay [ConceptTheme.secondary] (link/GPS status, unchanged);
 * `SEARCHING` stays [ConceptTheme.dim] (already non-fault); `NO_SIGNAL` moves
 * from [ConceptTheme.error] to [ConceptTheme.dim] — the one row this surface
 * is not allowed to render red.
 */
private fun passengerStatusColor(
    source: TelemetrySource,
    theme: ConceptTheme,
): Color =
    when (source) {
        TelemetrySource.ON_BEACON, TelemetrySource.LOCAL_BACKUP, TelemetrySource.MANUAL_SOURCE -> theme.secondary
        TelemetrySource.SEARCHING, TelemetrySource.NO_SIGNAL -> theme.dim
    }

/** Ticker body size: wide enough to read the longer lines (BCN/ODO), tall enough for ~8 stepped lines at arm's length. */
private val TICKER_HEIGHT = 200.dp
private const val TICKER_WIDTH_FRACTION = 0.92f
