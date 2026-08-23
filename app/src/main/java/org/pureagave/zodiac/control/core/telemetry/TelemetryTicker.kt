package org.pureagave.zodiac.control.core.telemetry

import java.util.Locale

/** Which visual voice a ticker segment reads in — chrome label vs. live value. */
enum class TickerRole { CHROME, VALUE }

/** One piece of a ticker line: the text, and whether it's a label or a live value. */
data class TickerSegment(val text: String, val role: TickerRole)

/**
 * Every live-state channel the TELEMETRY box's ticker can render a line for.
 * Every field is independently nullable/absent because the ticker must work on
 * every [org.pureagave.zodiac.control.core.sensor.LocationSourceType] source
 * (including SYSTEM/FAKE, which never carry a beacon) — a channel with nothing
 * behind it contributes no line, never a fabricated placeholder.
 *
 * [lat]/[lon] are gated together: the POS line only appears when both are
 * present (they always arrive together, from the same fix).
 */
data class TickerInputs(
    val lat: Double? = null,
    val lon: Double? = null,
    val fixQualityM: Double? = null,
    val headingDeg: Int? = null,
    val gpsSpeedKph: Double? = null,
    val lux: Double? = null,
    val lastShockG: Double? = null,
    val health: BeaconHealth? = null,
    val odometer: Odometer? = null,
)

/** Single-CHROME-segment line shown when [tickerLines] has nothing real to say. */
private val NO_TELEMETRY_LINE = listOf(TickerSegment("NO TELEMETRY", TickerRole.CHROME))

/**
 * Every ticker line that currently has real data behind it, in pinned order
 * (POS, FIX, HDG, SPD, LUX, SHK, BCN, ODO). A channel with no data contributes
 * no line — this mirrors the passenger carousel's "a card with no data leaves
 * the rotation" doctrine; the ticker never pads with dashes (that's the fixed
 * tiles' rule, not the rotating feed's).
 *
 * All formatting is `Locale.US`-pinned (the `Nmea.kt` convention) so the
 * strings this returns are exact and test-pinnable.
 */
fun tickerLines(inputs: TickerInputs): List<List<TickerSegment>> =
    buildList {
        val lat = inputs.lat
        val lon = inputs.lon
        if (lat != null && lon != null) {
            add(
                listOf(
                    TickerSegment("POS", TickerRole.CHROME),
                    TickerSegment("%.5f %.5f".format(Locale.US, lat, lon), TickerRole.VALUE),
                ),
            )
        }
        inputs.fixQualityM?.let { m ->
            add(
                listOf(
                    TickerSegment("FIX", TickerRole.CHROME),
                    TickerSegment("±%.1fM".format(Locale.US, m), TickerRole.VALUE),
                ),
            )
        }
        inputs.headingDeg?.let { hdg ->
            add(
                listOf(
                    TickerSegment("HDG", TickerRole.CHROME),
                    TickerSegment("%d°".format(Locale.US, hdg), TickerRole.VALUE),
                ),
            )
        }
        inputs.gpsSpeedKph?.let { spd ->
            add(
                listOf(
                    TickerSegment("SPD", TickerRole.CHROME),
                    TickerSegment("%.1fKPH".format(Locale.US, spd), TickerRole.VALUE),
                ),
            )
        }
        inputs.lux?.let { lux ->
            add(
                listOf(
                    TickerSegment("LUX", TickerRole.CHROME),
                    TickerSegment(formatLux(lux), TickerRole.VALUE),
                ),
            )
        }
        inputs.lastShockG?.let { g ->
            add(
                listOf(
                    TickerSegment("SHK", TickerRole.CHROME),
                    TickerSegment("%.1fG".format(Locale.US, g), TickerRole.VALUE),
                ),
            )
        }
        inputs.health?.let { h -> add(beaconHealthLine(h)) }
        inputs.odometer?.let { o -> add(odometerLine(o)) }
    }

private fun beaconHealthLine(health: BeaconHealth): List<TickerSegment> =
    listOf(
        TickerSegment("BCN BATT", TickerRole.CHROME),
        TickerSegment("${health.batteryPct}%", TickerRole.VALUE),
        TickerSegment("SATS", TickerRole.CHROME),
        TickerSegment("${health.satellites}", TickerRole.VALUE),
        TickerSegment("UP", TickerRole.CHROME),
        TickerSegment(formatUptime(health.uptimeSec), TickerRole.VALUE),
    )

private fun odometerLine(odometer: Odometer): List<TickerSegment> =
    listOf(
        TickerSegment("ODO TRIP", TickerRole.CHROME),
        TickerSegment("%.1fKM".format(Locale.US, odometer.tripMeters / METERS_PER_KM), TickerRole.VALUE),
        TickerSegment("TOTAL", TickerRole.CHROME),
        TickerSegment("%.1fKM".format(Locale.US, odometer.totalMeters / METERS_PER_KM), TickerRole.VALUE),
    )

/** `"3H07"` — hours (unpadded) + zero-padded minutes, for the BCN line's UP field. */
private fun formatUptime(uptimeSec: Long): String {
    val hours = uptimeSec / SECONDS_PER_HOUR
    val minutes = (uptimeSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return "%dH%02d".format(Locale.US, hours, minutes)
}

/**
 * Log-scale lux formatting shared by the ticker and (Phase C) the AMBIENT+SHOCK
 * tile — one definition, both callers. Below 10 lx: one decimal (`"8.4"`). From
 * 10 up to 1000: whole numbers (`"84"`, `"999"`). At/above 1000: kilolux with
 * one decimal (`"1.0K"`, `"12.4K"`) — the sensor spans five decades (moonless
 * playa ≈ 0.1 lx, noon ≈ 100,000 lx), so a single fixed precision would either
 * lose the low end or print absurd digits at the high end.
 */
fun formatLux(lux: Double): String =
    when {
        lux < LUX_ONE_DECIMAL_BELOW -> "%.1f".format(Locale.US, lux)
        lux < LUX_KILO_AT -> "%.0f".format(Locale.US, lux)
        else -> "%.1fK".format(Locale.US, lux / LUX_KILO_AT)
    }

/**
 * Cyclic selection for the ticker's next emission: `seq % tickerLines(inputs).size`,
 * recomputed fresh from the current [inputs] every call, so a channel dropping
 * out between calls can never index past the new (shorter) line list. Returns
 * the single [NO_TELEMETRY_LINE] when nothing has data — shown, not hidden, an
 * empty bus is a true statement.
 */
fun tickerLineAt(
    inputs: TickerInputs,
    seq: Long,
): List<TickerSegment> {
    val lines = tickerLines(inputs)
    if (lines.isEmpty()) return NO_TELEMETRY_LINE
    val index = (seq % lines.size).toInt()
    return lines[index]
}

private const val LUX_ONE_DECIMAL_BELOW: Double = 10.0
private const val LUX_KILO_AT: Double = 1000.0
private const val SECONDS_PER_HOUR: Long = 3_600L
private const val SECONDS_PER_MINUTE: Long = 60L
private const val METERS_PER_KM: Double = 1_000.0
