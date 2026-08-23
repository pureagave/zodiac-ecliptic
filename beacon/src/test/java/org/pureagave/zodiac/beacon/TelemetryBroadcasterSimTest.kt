package org.pureagave.zodiac.beacon

import android.content.Context
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wires `GpsSim.kt`'s pure model into [TelemetryBroadcaster]. Uses the
 * [gpsHandleOverride] seam per `TelemetryBroadcasterGpsTest`/
 * `TelemetryBroadcasterFixHealthTest`, and never drains the tick loop (calls
 * [TelemetryBroadcaster.statusText] directly instead) -- same precedent as
 * `TelemetryBroadcasterFixHealthTest`.
 *
 * [TelemetryBroadcaster] is a process `object` singleton: every test clears
 * [gpsHandleOverride] AND [TelemetryBroadcaster.simState] in `@After`, or
 * state leaks into later tests (see `TelemetryBroadcasterGpsTest.kt:21-23`).
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryBroadcasterSimTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var capturedNmea: OnNmeaMessageListener? = null

    private val capturingGpsHandle =
        object : BeaconGpsHandle {
            override fun hasFineLocation(): Boolean = true

            override fun wire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) {
                capturedNmea = onNmea
            }

            override fun unwire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) = Unit
        }

    @After
    fun tearDown() {
        TelemetryBroadcaster.stop()
        gpsHandleOverride = null
        TelemetryBroadcaster.simState.value = null
        capturedNmea = null
    }

    private fun armedSim(
        headingDeg: Double = 90.0,
        speedKph: Double = 0.0,
    ) = GpsSimulator(SimSpec(SimPreset.MAN.lat, SimPreset.MAN.lon, headingDeg, speedKph, "MAN"), startedAtMs = 0L)

    @Test
    fun armed_status_text_shows_the_simulated_banner_and_coordinates() {
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        TelemetryBroadcaster.simState.value = armedSim()

        val status = TelemetryBroadcaster.statusText()

        assertTrue(
            "expected the GPS SIMULATED banner as the first line; was: $status",
            status.lines().first().contains("GPS SIMULATED"),
        )
        assertTrue("expected the sim GPS line; was: $status", status.contains("GPS    SIM"))
    }

    @Test
    fun stop_clears_an_armed_simulation() {
        // Mutation target: delete `simState.value = null` from stop().
        TelemetryBroadcaster.simState.value = armedSim()

        TelemetryBroadcaster.stop()

        assertNull(TelemetryBroadcaster.simState.value)
    }

    @Test
    fun start_never_inherits_an_armed_simulation() {
        // Mutation target: delete `simState.value = null` from start()'s reset
        // block. Should be unreachable via the receiver while stopped, but
        // start() must not trust that -- a rebooted/restarted process always
        // comes back on real GNSS.
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.simState.value = armedSim()

        TelemetryBroadcaster.start(context, micEnabled = false)

        assertNull(TelemetryBroadcaster.simState.value)
    }

    @Test
    fun arming_and_disarming_writes_nothing_to_the_beacon_prefs() {
        // Non-negotiable: the ONLY persistence surface is the zodiac_beacon
        // prefs file, and arm/disarm is memory-only. Deliberately does NOT
        // start() the broadcaster -- the tick loop's own HEALTH_EVERY_TICKS
        // branch calls persistTotal() on tick 0 almost immediately once
        // scheduled, which would make a prefs snapshot around start() racy
        // for reasons unrelated to this feature. Mutating simState directly
        // isolates exactly the claim under test.
        val prefs = context.getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val before = prefs.all.toMap()

        TelemetryBroadcaster.simState.value = armedSim(speedKph = 12.0)
        TelemetryBroadcaster.simState.value = null

        assertEquals("arm+disarm must not touch ${TelemetryBroadcaster.PREFS_NAME}", before, prefs.all.toMap())
    }

    @Test
    fun stopping_the_broadcaster_does_write_the_odometer_pref_as_a_positive_control() {
        // Proves the prefs-snapshot technique above is discriminating: stop()
        // IS expected to write PREF_TOTAL_METERS, so if it silently didn't,
        // the negative-control test above would be worthless (a test that
        // can't fail proves nothing).
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        val prefs = context.getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply() // wipe anything the tick loop may have already raced in

        TelemetryBroadcaster.stop()

        assertTrue("expected stop() to persist the odometer total", prefs.contains(PREF_TOTAL_METERS_KEY))
    }

    @Test
    fun forward_suppresses_a_real_gga_while_armed_and_preserves_the_sim_fix_stamp() {
        // Mutation target: delete the `if (simState.value != null) return`
        // guard at the top of forward().
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        val onNmea = capturedNmea ?: error("wire() never captured the NMEA listener")

        // Fake-stamp q8/12 the way the sim tick loop would, via the SAME real
        // forward() path while off -- no need to drain the tick loop.
        onNmea.onNmeaMessage(simLikeGgaSentence(fixQuality = 8, satellites = 12), 0L)
        assertTrue(TelemetryBroadcaster.statusText().contains("q8/12 sat"))

        TelemetryBroadcaster.simState.value = armedSim()
        onNmea.onNmeaMessage(simLikeGgaSentence(fixQuality = 1, satellites = 4), 0L)

        val status = TelemetryBroadcaster.statusText()
        assertTrue("a real GGA must not overwrite the sim's q8/12 stamp while armed; was: $status", status.contains("q8/12 sat"))
    }

    @Test
    fun forward_updates_fix_health_normally_when_sim_is_off() {
        // Positive control for the test above: the same call path DOES update
        // fix health when not armed.
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        val onNmea = capturedNmea ?: error("wire() never captured the NMEA listener")

        onNmea.onNmeaMessage(simLikeGgaSentence(fixQuality = 3, satellites = 6), 0L)

        assertTrue(TelemetryBroadcaster.statusText().contains("q3/6 sat"))
    }

    @Test
    fun speed_kph_follows_the_sim_even_with_no_real_location() {
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        TelemetryBroadcaster.simState.value = armedSim(speedKph = 12.0)

        assertTrue(TelemetryBroadcaster.statusText().contains("SPD 12 kph"))
    }

    private fun simLikeGgaSentence(
        fixQuality: Int,
        satellites: Int,
    ): String {
        val body = "GPGGA,123519,4807.038,N,01131.000,E,$fixQuality,$satellites,0.9,545.4,M,46.9,M,,"
        return "\$$body*${Nmea.checksum(body)}\r\n"
    }

    private companion object {
        // TelemetryBroadcaster.PREF_TOTAL_METERS is private; restated here as a
        // literal like TelemetryBroadcasterFixHealthTest's STALE_ADVANCE_MS.
        const val PREF_TOTAL_METERS_KEY = "odometer_total_m"
    }
}
