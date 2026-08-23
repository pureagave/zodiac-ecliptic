package org.pureagave.zodiac.beacon

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * Minimal NMEA 0183 output helpers: the standard XOR checksum and a synthesized
 * true-heading (HDT) sentence for the phone's compass. The GNSS sentences the
 * phone already emits are forwarded verbatim; HDT is the one we generate.
 *
 * [gga]/[rmc] are the exception to "forwarded verbatim": they exist only for
 * the GPS simulation mode (`GpsSim.kt`) — real fixes never go through these,
 * they are sent byte-for-byte as the OS handed them over (`TelemetryBroadcaster.forward`).
 */
object Nmea {
    private const val FULL_CIRCLE = 360.0

    // $ZVER field bounds — mirror the tablet's FleetVersionProtocol grammar.
    private const val NODE_MAX_LEN = 8
    private const val LABEL_MAX_LEN = 16

    // Largest value the epoch grammar ([0-9]{1,10}) can carry: ten nines.
    private const val MAX_EPOCH = 9_999_999_999L

    private const val MINUTES_PER_DEGREE = 60.0
    private const val KNOTS_PER_KPH = 1.852
    private const val NANOS_PER_HUNDREDTH_SEC = 10_000_000L
    private const val HUNDRED = 100

    /** `$GPHDT,<deg>,T*cs` — true heading, 0..360, one decimal. */
    fun hdt(headingDeg: Double): String {
        val norm = ((headingDeg % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        // Locale.US: a comma-decimal locale would emit "12,3" and split the
        // sentence into an extra field, silently corrupting the fleet's heading.
        val body = "GPHDT,%.1f,T".format(Locale.US, norm)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$GPGGA,hhmmss.ss,DDMM.MMMM,N|S,DDDMM.MMMM,E|W,8,12,0.8,1191.0,M,0.0,M,,*cs`
     * — a GPS-simulation-mode fix (see `GpsSim.kt`). Fix quality **8** is the
     * NMEA 0183 "Simulation mode" indicator (a datasheet fact, flagged as
     * such) — the in-band marker that a fix is fabricated; the shipped
     * consumer accepts any quality `> 0` (measured, `NmeaParser.kt:161`).
     * [epochMillis] is UTC wall time, injected rather than read from a clock
     * here so the sentence is a pure function of its inputs.
     */
    fun gga(
        latDeg: Double,
        lonDeg: Double,
        epochMillis: Long,
    ): String {
        val fields = latLonFields(latDeg, lonDeg)
        val body =
            "GPGGA,%s,%s,%s,%s,%s,%d,%d,%.1f,%.1f,M,0.0,M,,".format(
                Locale.US,
                utcTimeField(epochMillis),
                fields.lat,
                fields.latHemi,
                fields.lon,
                fields.lonHemi,
                SIM_FIX_QUALITY,
                SIM_SATELLITES,
                SIM_HDOP,
                SIM_ALTITUDE_M,
            )
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$GPRMC,hhmmss.ss,A,DDMM.MMMM,N|S,DDDMM.MMMM,E|W,knots,cog,ddMMyy,,,A*cs`
     * — the sim's RMC (see `GpsSim.kt`): status is always `A` (a synthetic fix
     * is never "void"); course equals the sim heading (COG == heading under
     * straight-line dead reckoning), emitted even at speed 0 for determinism.
     */
    fun rmc(
        latDeg: Double,
        lonDeg: Double,
        speedKph: Double,
        headingDeg: Double,
        epochMillis: Long,
    ): String {
        val fields = latLonFields(latDeg, lonDeg)
        val knots = speedKph / KNOTS_PER_KPH
        val body =
            "GPRMC,%s,A,%s,%s,%s,%s,%.1f,%.1f,%s,,,A".format(
                Locale.US,
                utcTimeField(epochMillis),
                fields.lat,
                fields.latHemi,
                fields.lon,
                fields.lonHemi,
                knots,
                headingDeg,
                utcDateField(epochMillis),
            )
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * Proprietary vehicle-telemetry sentence `$ZTLM,pitch,roll,speedKph*cs` —
     * the IMU tilt (from the rotation vector) plus ground speed the fleet wants
     * beyond position/heading. Pitch/roll in degrees, speed in kph.
     */
    fun ztlm(
        pitchDeg: Double,
        rollDeg: Double,
        speedKph: Double,
    ): String {
        val body = "ZTLM,%.1f,%.1f,%.1f".format(Locale.US, pitchDeg, rollDeg, speedKph)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZAUD,rms,peak,beat*cs` — ambient sound for reactive lighting. [rms] and
     * [peak] are normalized 0..1 loudness; [beat] flags a detected onset this
     * frame. Three decimals on the levels so a quiet room still reads non-zero.
     */
    fun zaud(
        rms: Double,
        peak: Double,
        beat: Boolean,
    ): String {
        val body = "ZAUD,%.3f,%.3f,%d".format(Locale.US, rms, peak, if (beat) 1 else 0)
        return "\$$body*${checksum(body)}\r\n"
    }

    /** `$ZENV,lux*cs` — ambient light in lux, for the fleet's auto day/night switch. */
    fun zenv(lux: Double): String {
        val body = "ZENV,%.1f".format(Locale.US, lux)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZSHK,peakG*cs` — a shock/impact event: the peak linear-acceleration
     * magnitude (gravity removed) in g since the last quiet frame. Event-driven:
     * emitted only when a spike crosses the detector's threshold.
     */
    fun zshk(peakG: Double): String {
        val body = "ZSHK,%.2f".format(Locale.US, peakG)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZBCN,batt,fixQ,sats,uptimeS*cs` — beacon health heartbeat so the fleet
     * knows the sensor phone is alive and when it's about to die: battery percent,
     * GNSS fix quality + satellite count (from the GGA passthrough), and uptime.
     */
    fun zbcn(
        batteryPct: Int,
        fixQuality: Int,
        satellites: Int,
        uptimeSec: Long,
    ): String {
        val body = "ZBCN,%d,%d,%d,%d".format(Locale.US, batteryPct, fixQuality, satellites, uptimeSec)
        return "\$$body*${checksum(body)}\r\n"
    }

    /** `$ZODO,tripM,totalM*cs` — trip (this session) + lifetime odometer, in metres. */
    fun zodo(
        tripM: Double,
        totalM: Double,
    ): String {
        val body = "ZODO,%.1f,%.1f".format(Locale.US, tripM, totalM)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZVER,node,name,base,sha,dirty,epoch*cs` — the beacon's build announcement
     * for the FLEET-1 version monitor. One of three hand-written implementations
     * of this wire (with the tablet's `FleetVersionProtocol.kt` and the Jetson's
     * `version_protocol.py`), pinned together by `protocol/version-protocol-golden.json`;
     * the beacon only *emits*, so it builds but never parses. Fields are sanitised
     * to their grammar so the output always parses on the tablet: [node] uppercased
     * `[A-Z0-9]` last 8, [name]/[base] filtered to their legal sets, [epoch] clamped
     * into `[0, 10^10)`. [sha] and [dirty] come straight from `BuildConfig` (already
     * `9-hex`-or-`unknown` and a bool).
     */
    fun zver(
        node: String,
        name: String,
        base: String,
        sha: String,
        dirty: Boolean,
        epoch: Long,
    ): String {
        val body =
            "ZVER,${sanitizeNode(node)},${sanitizeLabel(name, "._-", "node")}," +
                "${sanitizeLabel(base, ".+~-", "0.0.0")},$sha,${if (dirty) "1" else "0"}," +
                "${epoch.coerceIn(0L, MAX_EPOCH)}"
        return "\$$body*${checksum(body)}\r\n"
    }

    /** Uppercase, `[A-Z0-9]` only, last 8 chars; "0" when nothing survives. */
    private fun sanitizeNode(raw: String): String {
        val cleaned = raw.uppercase(Locale.US).filter { it in 'A'..'Z' || it in '0'..'9' }
        return cleaned.takeLast(NODE_MAX_LEN).ifEmpty { "0" }
    }

    /** Keep only [extra]-plus-alphanumeric chars, first 16; [fallback] when nothing survives. */
    private fun sanitizeLabel(
        raw: String,
        extra: String,
        fallback: String,
    ): String {
        val cleaned = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in extra }
        return cleaned.take(LABEL_MAX_LEN).ifEmpty { fallback }
    }

    /** Two-hex-digit XOR of the sentence body (the chars between `$` and `*`). */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(Locale.ROOT, c)
    }

    /** `DDMM.MMMM`/`DDDMM.MMMM` + hemisphere, for [gga]/[rmc]. */
    private data class LatLonFields(val lat: String, val latHemi: String, val lon: String, val lonHemi: String)

    /**
     * Lat `%02d%07.4f` (deg·100 + minutes, 4 decimal minutes ≈ 0.19 m
     * resolution), lon `%03d%07.4f`; hemisphere from sign. Shared formatter
     * for [gga] and [rmc] so the two builders can't drift apart.
     */
    private fun latLonFields(
        latDeg: Double,
        lonDeg: Double,
    ): LatLonFields {
        val latHemi = if (latDeg < 0) "S" else "N"
        val lonHemi = if (lonDeg < 0) "W" else "E"
        val latAbs = abs(latDeg)
        val lonAbs = abs(lonDeg)
        val latWhole = latAbs.toInt()
        val latMin = (latAbs - latWhole) * MINUTES_PER_DEGREE
        val lonWhole = lonAbs.toInt()
        val lonMin = (lonAbs - lonWhole) * MINUTES_PER_DEGREE
        return LatLonFields(
            lat = "%02d%07.4f".format(Locale.US, latWhole, latMin),
            latHemi = latHemi,
            lon = "%03d%07.4f".format(Locale.US, lonWhole, lonMin),
            lonHemi = lonHemi,
        )
    }

    /** `hhmmss.ss` in UTC, for [gga]/[rmc]. [epochMillis] is never read from a clock — always the caller's value. */
    private fun utcTimeField(epochMillis: Long): String {
        val utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        val hundredths = utc.nano / NANOS_PER_HUNDREDTH_SEC
        return "%02d%02d%02d.%02d".format(Locale.US, utc.hour, utc.minute, utc.second, hundredths)
    }

    /** `ddMMyy` in UTC, for [rmc]. */
    private fun utcDateField(epochMillis: Long): String {
        val utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        return "%02d%02d%02d".format(Locale.US, utc.dayOfMonth, utc.monthValue, utc.year % HUNDRED)
    }
}
