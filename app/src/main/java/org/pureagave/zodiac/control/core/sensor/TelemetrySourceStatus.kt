package org.pureagave.zodiac.control.core.sensor

/**
 * Which of the fleet's position sources the cockpit is actually trusting right
 * now, coarsened to what a driver can act on. Sibling to [gpsStatusLabel] — same
 * "one line, plain glyph + word" doctrine — but this line additionally carries
 * the failover leg: [org.pureagave.zodiac.control.data.sensor.FailoverLocationSource]
 * deliberately presents itself as [LocationSourceType.NET] regardless of which
 * leg (the beacon or this tablet's own GNSS) is actually answering, so nothing
 * downstream of the router can tell the two apart without this.
 */
enum class TelemetrySource { ON_BEACON, LOCAL_BACKUP, MANUAL_SOURCE, SEARCHING, NO_SIGNAL }

/** The TELEMETRY box's status line: the text to draw, and which state it represents. */
data class SourceStatusLine(val text: String, val source: TelemetrySource)

/**
 * The TELEMETRY box's status-line derivation. Pure and separate from the
 * composable for the same reason [gpsStatusLabel] is: this line is read in the
 * dark, in dust, while the car is moving, and it must say the same thing every
 * time.
 *
 * The line collapses every underlying state to three actionable answers — "can
 * I trust the position: yes / yes-but / no" — because that's what the driver
 * needs *here*; the error *category* detail (PERMISSION vs ADAPTER OFF vs NO
 * DEVICE) stays on the dev panel's [gpsStatusLabel] line and in the log.
 *
 * Truth table (priority order, top wins):
 *  1. Not [LocationSourceState.Active] — [usingFallback] is deliberately
 *     ignored here, on purpose: a stale fallback flag over a source that has
 *     actually gone Disconnected/Error must never read as a working backup.
 *     [LocationSourceState.Searching] reads as [TelemetrySource.SEARCHING]
 *     (transitional, non-fault); anything else ([LocationSourceState.Disconnected]
 *     or [LocationSourceState.Error]) reads as [TelemetrySource.NO_SIGNAL] (a
 *     real fault — no usable position from any source).
 *  2. Active, but [selected] isn't [LocationSourceType.NET] — the operator
 *     deliberately picked FAKE/SYSTEM/BLE/USB in the dev panel, so this is
 *     [TelemetrySource.MANUAL_SOURCE], named after the chosen source.
 *  3. Active, NET, [usingFallback] true — the beacon is down and this
 *     tablet's own GNSS answered instead: [TelemetrySource.LOCAL_BACKUP].
 *  4. Active, NET, not fallback — nominal: [TelemetrySource.ON_BEACON].
 */
fun telemetrySourceStatus(
    selected: LocationSourceType,
    state: LocationSourceState,
    usingFallback: Boolean,
): SourceStatusLine {
    if (state !is LocationSourceState.Active) {
        return if (state is LocationSourceState.Searching) {
            SourceStatusLine("⋯ SEARCHING", TelemetrySource.SEARCHING)
        } else {
            SourceStatusLine("✕ NO SIGNAL", TelemetrySource.NO_SIGNAL)
        }
    }
    return when {
        selected != LocationSourceType.NET ->
            SourceStatusLine("◇ SRC ${selected.name}", TelemetrySource.MANUAL_SOURCE)
        usingFallback -> SourceStatusLine("◐ LOCAL BACKUP", TelemetrySource.LOCAL_BACKUP)
        else -> SourceStatusLine("● ON BEACON", TelemetrySource.ON_BEACON)
    }
}
