package org.pureagave.zodiac.beacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-only GPS simulation control (`design/BEACON-GPS-TEST-MODE-spec.md`):
 * arms/adjusts/disarms [TelemetryBroadcaster.simState].
 *
 * ```
 * # park the fleet at the Man
 * adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es preset MAN
 * # drive from camp toward the Man-ish direction at 12 kph
 * adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es preset HOME --es hdg 292 --es spd 12
 * # arbitrary point, eastbound at 8 kph
 * adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es lat 40.78325 --es lon -119.20788 --es hdg 90 --es spd 8
 * # turn: continue from the CURRENT simulated position on a new heading/speed
 * adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS --es hdg 180 --es spd 8
 * # off -- real GNSS resumes immediately
 * adb shell am broadcast -a org.pureagave.zodiac.beacon.SIM_GPS_OFF
 * ```
 *
 * Context-registered by [TelemetryService] (not manifest-declared) so an arm
 * command against a stopped beacon is inert by construction, and guarded by
 * `android.permission.DUMP` on registration — the adb shell uid holds DUMP
 * (it's what makes `dumpsys` work); ordinary installed apps cannot. All
 * extras are **strings**, deliberately: a wrong `--ef` vs `--es` type would
 * make `getFloatExtra` silently return its default, which is exactly the
 * quiet-failure trap this project avoids elsewhere; `--es` +
 * `String.toDoubleOrNull` (in [simCommand]) rejects loudly instead.
 *
 * Every decision lives in [simCommand] — this receiver is a thin shell that
 * reads extras, calls it, and logs the outcome.
 */
internal class SimGpsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!TelemetryBroadcaster.isRunning.value) {
            Log.w(TAG, "sim: beacon not running, ignored")
            return
        }
        when (intent.action) {
            ACTION_SIM_GPS -> handleSimGps(intent)
            ACTION_SIM_GPS_OFF -> {
                TelemetryBroadcaster.simState.value = null
                Log.i(TAG, "sim: OFF -- real GNSS restored")
            }
        }
    }

    private fun handleSimGps(intent: Intent) {
        val extras =
            SimCommandExtras(
                preset = intent.getStringExtra(EXTRA_PRESET),
                lat = intent.getStringExtra(EXTRA_LAT),
                lon = intent.getStringExtra(EXTRA_LON),
                hdg = intent.getStringExtra(EXTRA_HDG),
                spd = intent.getStringExtra(EXTRA_SPD),
            )
        val next = simCommand(extras, current = TelemetryBroadcaster.simState.value, nowMs = System.currentTimeMillis())
        if (next != null) {
            TelemetryBroadcaster.simState.value = next
            Log.i(
                TAG,
                "sim: armed -- ${next.spec.label} hdg ${next.spec.headingDeg}° spd ${next.spec.speedKph} kph " +
                    "@ ${next.spec.latDeg}, ${next.spec.lonDeg}",
            )
        } else {
            Log.w(TAG, "sim: invalid command rejected: $extras")
        }
    }

    private companion object {
        const val TAG = "ZodiacBeacon"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_HDG = "hdg"
        const val EXTRA_SPD = "spd"
    }
}

/** Arm/adjust the GPS simulation. Extras: `preset` OR `lat`+`lon`, plus optional `hdg`/`spd` — see [SimGpsReceiver]'s KDoc. */
internal const val ACTION_SIM_GPS = "org.pureagave.zodiac.beacon.SIM_GPS"

/** Disarm: real GNSS resumes immediately. No extras. */
internal const val ACTION_SIM_GPS_OFF = "org.pureagave.zodiac.beacon.SIM_GPS_OFF"
