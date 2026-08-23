package org.pureagave.zodiac.beacon

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPS test/simulation mode — pure model (no Android imports, everything
 * deterministic, every clock injected). See
 * `design/BEACON-GPS-TEST-MODE-spec.md` for the full design.
 *
 * **Clock domain.** [GpsSimulator.startedAtMs] and every `nowMs`/`epochMs`
 * passed into this file are wall-clock epoch milliseconds
 * (`System.currentTimeMillis()`), not `SystemClock.elapsedRealtime()`. That is
 * a deliberate choice, not an oversight: [simTickSentences] has to feed the
 * SAME instant into both [GpsSimulator.positionAt] (the dead-reckoning delta)
 * and `Nmea.gga`/`Nmea.rmc` (whose `hhmmss.ss`/`ddMMyy` fields are real UTC —
 * see `Nmea.kt`), so the sim's whole clock has to be wall time. The cost is
 * that a wall-clock jump (NTP sync) would visibly relocate the sim mid-test;
 * acceptable for an opt-in, loud, non-persistent bench tool where a visible
 * jump is debuggable, unlike the silent failure modes this project usually
 * guards against elsewhere (e.g. the odometer's use of elapsedRealtime).
 */
private const val EARTH_RADIUS_M = 6_371_000.0
private const val FULL_CIRCLE_DEG = 360.0
private const val MILLIS_PER_SEC = 1000.0
private const val KPH_TO_MPS = 1.0 / 3.6
private const val LAT_MIN_DEG = -90.0
private const val LAT_MAX_DEG = 90.0
private const val LON_MIN_DEG = -180.0
private const val LON_MAX_DEG = 180.0

/** 0..this kph — mirrors the app's own drive-to/GPS speed bound (`CockpitViewModel`'s input validation). */
internal const val MAX_SIM_SPEED_KPH = 160.0

/** 1 Hz on the 250 ms tick loop — an integer divisor, matching real GNSS's `GPS_MIN_INTERVAL_MS = 1000`. */
internal const val SIM_FIX_EVERY_TICKS = 4L

/** NMEA 0183 GGA fix-quality 8 = "Simulation mode" (a datasheet fact) — the in-band marker for every synthetic fix. */
internal const val SIM_FIX_QUALITY = 8
internal const val SIM_SATELLITES = 12
internal const val SIM_HDOP = 0.8

/** Playa elevation: a chosen plausible value, not a measurement. */
internal const val SIM_ALTITUDE_M = 1191.0

/**
 * BRC presets, coordinates restated from `:app` (the modules share no code —
 * FLEET-1 decision 10). Sources cited per value; re-check when the city moves:
 * `app/.../core/geo/GoldenSpike.kt` (Y2026), `core/ops/Camp.kt`, `core/ops/NavTarget.kt`.
 */
internal enum class SimPreset(val lat: Double, val lon: Double) {
    MAN(40.783247448000054, -119.20788409599999), // GoldenSpike.Y2026
    HOME(40.7832474, -119.2225761), // Camp.HOME_CAMP (7:30 & E)
    TEMPLE(40.78809942300006, -119.201499636), // NavTarget.TEMPLE 2026
}

/** What the operator asked for. Immutable. */
internal data class SimSpec(
    val latDeg: Double,
    val lonDeg: Double,
    val headingDeg: Double,
    val speedKph: Double,
    val label: String,
)

/**
 * A running simulation: a spec + when it started. Position is a **pure
 * function of elapsed time** — no per-tick accumulation, so it is trivially
 * deterministic and testable, and can never drift.
 */
internal class GpsSimulator(val spec: SimSpec, val startedAtMs: Long) {
    /**
     * Dead-reckoned position at [nowMs]: equirectangular projection (matches
     * the app's `PlayaProjection` approach), cos at the *start* latitude —
     * the error this introduces is negligible over test-bench-scale
     * distances.
     */
    fun positionAt(nowMs: Long): Pair<Double, Double> {
        val elapsedSec = (nowMs - startedAtMs) / MILLIS_PER_SEC
        val distanceM = spec.speedKph * KPH_TO_MPS * elapsedSec
        val hdgRad = Math.toRadians(spec.headingDeg)
        val latRad = Math.toRadians(spec.latDeg)
        val dLatDeg = Math.toDegrees((distanceM * cos(hdgRad)) / EARTH_RADIUS_M)
        val dLonDeg = Math.toDegrees((distanceM * sin(hdgRad)) / (EARTH_RADIUS_M * cos(latRad)))
        return (spec.latDeg + dLatDeg) to (spec.lonDeg + dLonDeg)
    }

    /**
     * A new simulator continuing from the CURRENT dead-reckoned position with
     * a new heading/speed — the "turn" primitive; a timed sequence of turns
     * is a path (no path DSL in the MVP — see the spec's open question 4).
     */
    fun turn(
        nowMs: Long,
        headingDeg: Double,
        speedKph: Double,
    ): GpsSimulator {
        val (lat, lon) = positionAt(nowMs)
        return GpsSimulator(spec.copy(latDeg = lat, lonDeg = lon, headingDeg = headingDeg, speedKph = speedKph), nowMs)
    }
}

private fun normalizeHeading(raw: Double): Double = ((raw % FULL_CIRCLE_DEG) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG

/** Absent → default `0.0`; present but unparsable/non-finite → reject (null) — never silently default a *present* extra. */
private fun parseHeading(hdg: String?): Double? {
    if (hdg == null) return 0.0
    val v = hdg.toDoubleOrNull() ?: return null
    return if (v.isFinite()) normalizeHeading(v) else null
}

/**
 * Absent → default `0.0`; present but unparsable/non-finite/out-of-range →
 * reject. `String.toDoubleOrNull("NaN")` returns `Double.NaN`, not null — the
 * separate `isFinite()` check is why "NaN" is rejected rather than silently
 * accepted as a parse failure.
 */
private fun parseSpeed(spd: String?): Double? {
    if (spd == null) return 0.0
    val v = spd.toDoubleOrNull() ?: return null
    return if (v.isFinite() && v in 0.0..MAX_SIM_SPEED_KPH) v else null
}

private fun parseLat(lat: String): Double? {
    val v = lat.toDoubleOrNull() ?: return null
    return if (v.isFinite() && v in LAT_MIN_DEG..LAT_MAX_DEG) v else null
}

private fun parseLon(lon: String): Double? {
    val v = lon.toDoubleOrNull() ?: return null
    return if (v.isFinite() && v in LON_MIN_DEG..LON_MAX_DEG) v else null
}

/**
 * The raw string extras of a `SIM_GPS` command, bundled into one parameter —
 * they all arrive together off a single `Intent` (see [SimGpsReceiver]) and
 * bundling keeps [simCommand] under detekt's `LongParameterList` threshold
 * (a plain function, so unlike `CockpitViewModel`'s raised
 * `constructorThreshold`, the fix here is grouping, not a raised limit).
 */
internal data class SimCommandExtras(
    val preset: String?,
    val lat: String?,
    val lon: String?,
    val hdg: String?,
    val spd: String?,
)

/** `preset` XOR (`lat` AND `lon`) — both given, or one without the other, is ambiguous. */
private fun hasUnambiguousPositionShape(extras: SimCommandExtras): Boolean {
    val hasPreset = !extras.preset.isNullOrBlank()
    val hasLat = extras.lat != null
    val hasLon = extras.lon != null
    if (hasLat != hasLon) return false
    return !(hasPreset && hasLat && hasLon)
}

private fun presetSimulator(
    preset: String,
    headingDeg: Double,
    speedKph: Double,
    nowMs: Long,
): GpsSimulator? {
    val chosen = SimPreset.entries.firstOrNull { it.name.equals(preset, ignoreCase = true) } ?: return null
    return GpsSimulator(SimSpec(chosen.lat, chosen.lon, headingDeg, speedKph, chosen.name), nowMs)
}

private fun manualSimulator(
    lat: String,
    lon: String,
    headingDeg: Double,
    speedKph: Double,
    nowMs: Long,
): GpsSimulator? {
    val latDeg = parseLat(lat) ?: return null
    val lonDeg = parseLon(lon) ?: return null
    return GpsSimulator(SimSpec(latDeg, lonDeg, headingDeg, speedKph, "MANUAL"), nowMs)
}

/** The position source, once `hdg`/`spd` are already validated: preset, explicit lat/lon, or a turn from [current]. */
private fun resolveSimulator(
    extras: SimCommandExtras,
    current: GpsSimulator?,
    nowMs: Long,
    headingDeg: Double,
    speedKph: Double,
): GpsSimulator? =
    when {
        !extras.preset.isNullOrBlank() -> presetSimulator(extras.preset, headingDeg, speedKph, nowMs)
        extras.lat != null && extras.lon != null -> manualSimulator(extras.lat, extras.lon, headingDeg, speedKph, nowMs)
        // No position given: only valid as a "turn" from an already-armed sim.
        else -> current?.turn(nowMs, headingDeg, speedKph)
    }

/**
 * Parse + validate an arm/adjust command. All inputs are the raw string
 * extras (dot-decimal; `String.toDoubleOrNull` is locale-independent, unlike
 * `NumberFormat`). Returns the new simulator, or null = reject the whole
 * command (no partial application) — the caller ([SimGpsReceiver]) logs the
 * rejection.
 */
internal fun simCommand(
    extras: SimCommandExtras,
    current: GpsSimulator?,
    nowMs: Long,
): GpsSimulator? {
    if (!hasUnambiguousPositionShape(extras)) return null
    val headingDeg = parseHeading(extras.hdg)
    val speedKph = parseSpeed(extras.spd)
    if (headingDeg == null || speedKph == null) return null
    return resolveSimulator(extras, current, nowMs, headingDeg, speedKph)
}

/**
 * What the tick loop should send this tick while armed: `$GPHDT` every tick
 * (the deterministic sim heading, not the compass — works even on a bench
 * phone with no rotation-vector sensor), plus `$GPGGA`/`$GPRMC` on the 1 Hz
 * divisor. Top-level and pure so it is directly testable and stays out of
 * `TelemetryBroadcaster`'s function budget (`TooManyFunctions` is at 21/22
 * already — see `openTransmitSocket`/`sendToTargets` for the same
 * treatment). [epochMs] is UTC wall-clock ms — see the file header for why
 * one value has to serve both the position delta and the sentence clock.
 */
internal fun simTickSentences(
    sim: GpsSimulator,
    tick: Long,
    epochMs: Long,
): List<String> {
    val hdt = Nmea.hdt(sim.spec.headingDeg)
    if (tick % SIM_FIX_EVERY_TICKS != 0L) return listOf(hdt)
    val (lat, lon) = sim.positionAt(epochMs)
    return listOf(
        hdt,
        Nmea.gga(lat, lon, epochMs),
        Nmea.rmc(lat, lon, sim.spec.speedKph, sim.spec.headingDeg, epochMs),
    )
}

/**
 * The "⚠ GPS SIMULATED" status-readout line — pure and testable, built here
 * rather than inline in `TelemetryBroadcaster.statusText()`, per the same
 * top-level-function pattern as [simTickSentences]. Label/heading/speed only —
 * the position itself is a separate "GPS SIM lat, lon" line built directly in
 * `statusText()` from `sim.positionAt(now)`, so this function has no need for
 * a `nowMs` parameter (an earlier draft took one, unused; detekt's
 * `UnusedParameter` caught it).
 */
internal fun simStatusLine(sim: GpsSimulator): String {
    val spd = "%.1f".format(Locale.US, sim.spec.speedKph)
    return "⚠ GPS SIMULATED — ${sim.spec.label} hdg ${sim.spec.headingDeg.toInt()}°  spd $spd kph"
}

/** The two-line "can't miss it" banner text for [BeaconActivity], shown only while armed. */
internal fun simBannerText(sim: GpsSimulator): String {
    val spd = "%.1f".format(Locale.US, sim.spec.speedKph)
    return "⚠ GPS SIMULATED\n${sim.spec.label}  hdg ${sim.spec.headingDeg.toInt()}°  spd $spd kph"
}
