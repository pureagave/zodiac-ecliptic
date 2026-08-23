package org.pureagave.zodiac.beacon

import android.content.Context
import android.content.Intent
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives [SimGpsReceiver.onReceive] directly with a Robolectric context, the
 * broadcaster started via the [gpsHandleOverride] seam (real `LocationManager`
 * is orthogonal to what this suite tests).
 */
@RunWith(RobolectricTestRunner::class)
class SimGpsReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val receiver = SimGpsReceiver()

    private val noOpGpsHandle =
        object : BeaconGpsHandle {
            override fun hasFineLocation(): Boolean = false

            override fun wire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) = Unit

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
    }

    private fun simGpsIntent(vararg extras: Pair<String, String>): Intent =
        Intent(ACTION_SIM_GPS).apply { extras.forEach { (k, v) -> putExtra(k, v) } }

    @Test
    fun preset_intent_while_running_arms_at_man_coordinates() {
        gpsHandleOverride = noOpGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)

        receiver.onReceive(context, simGpsIntent("preset" to "MAN"))

        val sim = TelemetryBroadcaster.simState.value
        assertNotNull(sim)
        assertEquals(SimPreset.MAN.lat, sim!!.spec.latDeg, 0.0)
        assertEquals(SimPreset.MAN.lon, sim.spec.lonDeg, 0.0)
    }

    @Test
    fun lat_lon_hdg_spd_intent_arms_accordingly_and_off_clears_it() {
        gpsHandleOverride = noOpGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)

        receiver.onReceive(context, simGpsIntent("lat" to "10.5", "lon" to "-20.25", "hdg" to "90", "spd" to "8"))
        val sim = TelemetryBroadcaster.simState.value
        assertNotNull(sim)
        assertEquals(10.5, sim!!.spec.latDeg, 0.0)
        assertEquals(8.0, sim.spec.speedKph, 0.0)

        receiver.onReceive(context, Intent(ACTION_SIM_GPS_OFF))
        assertNull(TelemetryBroadcaster.simState.value)
    }

    @Test
    fun malformed_commands_leave_the_sim_off() {
        // Mutation target: any of simCommand's validation short-circuits removed.
        gpsHandleOverride = noOpGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)

        receiver.onReceive(context, simGpsIntent("lat" to "abc", "lon" to "0"))
        assertNull(TelemetryBroadcaster.simState.value)

        receiver.onReceive(context, simGpsIntent("lat" to "0", "lon" to "0", "spd" to "200"))
        assertNull(TelemetryBroadcaster.simState.value)

        receiver.onReceive(context, simGpsIntent("preset" to "MAN", "lat" to "0", "lon" to "0"))
        assertNull(TelemetryBroadcaster.simState.value)
    }

    @Test
    fun a_valid_command_is_accepted_as_the_positive_control_beside_the_malformed_ones() {
        gpsHandleOverride = noOpGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)

        receiver.onReceive(context, simGpsIntent("lat" to "0", "lon" to "0"))

        assertNotNull(TelemetryBroadcaster.simState.value)
    }

    @Test
    fun commands_are_ignored_while_the_beacon_is_not_running() {
        // Mutation target: delete the isRunning guard at the top of onReceive.
        // (Broadcaster is stopped by default / via @After from any prior test.)
        receiver.onReceive(context, simGpsIntent("preset" to "MAN"))
        assertNull(TelemetryBroadcaster.simState.value)
    }

    @Test
    fun turn_form_while_armed_changes_heading_speed_and_keeps_the_position_close_to_the_armed_point() {
        gpsHandleOverride = noOpGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        receiver.onReceive(context, simGpsIntent("preset" to "MAN", "hdg" to "0", "spd" to "10"))
        assertNotNull(TelemetryBroadcaster.simState.value)

        receiver.onReceive(context, simGpsIntent("hdg" to "180", "spd" to "5"))

        val turned = TelemetryBroadcaster.simState.value
        assertNotNull(turned)
        assertEquals(180.0, turned!!.spec.headingDeg, 0.0)
        assertEquals(5.0, turned.spec.speedKph, 0.0)
        // Position continuity, not a reset to some other value: the two adb
        // calls above happen within milliseconds of each other in-test, so the
        // dead-reckoned drift is at most a few metres -- a generous 0.001 deg
        // (~100 m) tolerance absorbs that jitter while still catching a
        // regression that ignored `current` entirely (e.g. reset to 0,0).
        assertEquals(SimPreset.MAN.lat, turned.spec.latDeg, 0.001)
        assertEquals(SimPreset.MAN.lon, turned.spec.lonDeg, 0.001)
    }
}
