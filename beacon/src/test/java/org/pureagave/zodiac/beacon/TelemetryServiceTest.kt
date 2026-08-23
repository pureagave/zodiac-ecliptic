package org.pureagave.zodiac.beacon

import android.app.Application
import android.content.Intent
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * B4: a foreground service does not by itself keep the CPU awake — Doze stalls
 * the beacon's 250 ms tick loop otherwise. `ShadowPowerManager` models real
 * `PowerManager`/`WakeLock` acquire/release semantics, which is exactly what
 * this bug (and its fix) is about, so it's used directly rather than inventing
 * a `PowerManager` seam.
 *
 * [TelemetryBroadcaster] is a process `object` singleton — every test here
 * starts it (via the real service) and must stop it in `@After`, or state
 * leaks into later tests. [gpsHandleOverride] avoids exercising a real
 * `LocationManager`, which is orthogonal to what this suite is testing.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryServiceTest {
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
    }

    @Test
    fun service_holds_a_partial_wake_lock_while_running() {
        // Mutations: delete `acquire()` inside acquireWakeLock() → the lock
        // exists but isHeld is false. Delete the whole `newWakeLock` block
        // (call acquireWakeLock() a no-op) → getLatestWakeLock() is null.
        gpsHandleOverride = noOpGpsHandle
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Robolectric.buildService(TelemetryService::class.java, Intent(context, TelemetryService::class.java))
            .create()
            .startCommand(0, 0)

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("expected a wake lock to have been created for the running service", lock)
        assertTrue("wake lock must be held (PARTIAL_WAKE_LOCK) while the service is running", lock!!.isHeld)
    }

    @Test
    fun destroying_the_service_releases_the_lock() {
        // Mutation: delete `release()` in onDestroy() — the 14-day-leak mutation:
        // the CPU never sleeps again for the rest of the run.
        gpsHandleOverride = noOpGpsHandle
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller =
            Robolectric.buildService(TelemetryService::class.java, Intent(context, TelemetryService::class.java))
                .create()
                .startCommand(0, 0)
        controller.destroy()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(lock)
        assertFalse("wake lock must be released when the service is destroyed", lock!!.isHeld)
    }

    @Test
    fun starting_the_service_registers_the_sim_gps_receiver_guarded_by_dump() {
        // Mutation target: dropping the broadcastPermission argument (or
        // passing null) from ContextCompat.registerReceiver -- would silently
        // let any app broadcast SIM_GPS, not just adb shell/system.
        gpsHandleOverride = noOpGpsHandle
        val application = ApplicationProvider.getApplicationContext<Application>()
        Robolectric.buildService(TelemetryService::class.java, Intent(application, TelemetryService::class.java))
            .create()
            .startCommand(0, 0)

        val wrapper = shadowOf(application).registeredReceivers.firstOrNull { it.broadcastReceiver is SimGpsReceiver }
        assertNotNull("expected the SIM_GPS receiver to be registered", wrapper)
        assertEquals("android.permission.DUMP", wrapper!!.broadcastPermission)
        val actions = wrapper.intentFilter.actionsIterator().asSequence().toList()
        assertTrue(actions.contains(ACTION_SIM_GPS))
        assertTrue(actions.contains(ACTION_SIM_GPS_OFF))
    }

    @Test
    fun destroying_the_service_unregisters_the_sim_gps_receiver() {
        // Mutation target: delete the unregisterReceiver call in onDestroy() --
        // the receiver (and its DUMP guard) would leak past the service's
        // lifetime, contradicting "an arm command against a stopped beacon is
        // inert by construction".
        gpsHandleOverride = noOpGpsHandle
        val application = ApplicationProvider.getApplicationContext<Application>()
        val controller =
            Robolectric.buildService(TelemetryService::class.java, Intent(application, TelemetryService::class.java))
                .create()
                .startCommand(0, 0)
        controller.destroy()

        val stillRegistered = shadowOf(application).registeredReceivers.any { it.broadcastReceiver is SimGpsReceiver }
        assertFalse("expected the SIM_GPS receiver to be unregistered on destroy", stillRegistered)
    }
}
