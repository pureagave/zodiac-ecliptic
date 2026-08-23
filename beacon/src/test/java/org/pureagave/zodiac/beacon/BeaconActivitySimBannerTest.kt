package org.pureagave.zodiac.beacon

import android.os.Looper
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [BeaconActivity]'s "can't miss it" red banner: `GONE` unless [TelemetryBroadcaster.simState]
 * is armed. [BeaconActivity.simBanner] is `internal` specifically so this test
 * can read it directly rather than walking the view tree.
 *
 * [TelemetryBroadcaster] is a process `object` singleton — [TelemetryBroadcaster.simState]
 * is cleared in `@After` so state doesn't leak into other test classes.
 */
@RunWith(RobolectricTestRunner::class)
class BeaconActivitySimBannerTest {
    @After
    fun tearDown() {
        TelemetryBroadcaster.simState.value = null
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun banner_is_gone_when_the_sim_is_off() {
        val activity = Robolectric.buildActivity(BeaconActivity::class.java).create().get()
        assertEquals(View.GONE, activity.simBanner.visibility)
    }

    @Test
    fun banner_becomes_visible_with_the_label_when_armed() {
        // Mutation target: the visibility binding (e.g. inverting the if/else,
        // or never assigning VISIBLE at all).
        val activity = Robolectric.buildActivity(BeaconActivity::class.java).create().get()
        val sim = GpsSimulator(SimSpec(SimPreset.MAN.lat, SimPreset.MAN.lon, 90.0, 12.0, "MAN"), startedAtMs = 0L)

        TelemetryBroadcaster.simState.value = sim
        idleMainLooper()

        assertEquals(View.VISIBLE, activity.simBanner.visibility)
        assertEquals(simBannerText(sim), activity.simBanner.text.toString())
    }

    @Test
    fun banner_returns_to_gone_once_disarmed() {
        val activity = Robolectric.buildActivity(BeaconActivity::class.java).create().get()
        TelemetryBroadcaster.simState.value =
            GpsSimulator(SimSpec(SimPreset.MAN.lat, SimPreset.MAN.lon, 0.0, 0.0, "MAN"), startedAtMs = 0L)
        idleMainLooper()

        TelemetryBroadcaster.simState.value = null
        idleMainLooper()

        assertEquals(View.GONE, activity.simBanner.visibility)
    }
}
