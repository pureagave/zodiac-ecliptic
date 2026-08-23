package org.pureagave.zodiac.beacon

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Set by the caller when a start has a reason to believe it's happening in the
 * background: a boot start (B3), or a manual re-delivery. A `START_STICKY`
 * process restart delivers a null intent, which [onStartCommand] treats the
 * same way without needing this extra at all. */
const val EXTRA_FROM_BACKGROUND = "from_background"

private const val TAG = "ZodiacBeacon"

/**
 * Foreground service that runs [TelemetryBroadcaster] so the beacon keeps
 * broadcasting with the screen off — it's a headless box bolted to the vehicle,
 * not something anyone looks at.
 *
 * `startForeground` and the broadcaster start live in [onStartCommand], not
 * [onCreate]: only `onStartCommand` receives the intent, and the intent (null,
 * or carrying [EXTRA_FROM_BACKGROUND]) is the only signal for *why* this start
 * is happening — which [safeForegroundTypes] needs to pick a legal type set
 * (see AUDIT-2026-08-09 B5).
 */
class TelemetryService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    // FLEET-1 version emit — its own scope + broadcaster, entirely separate from
    // TelemetryBroadcaster so a fault here can never touch the GNSS transmit path.
    private val versionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var versionBroadcaster: VersionBroadcaster? = null

    // adb-only GPS simulation control (SIM-GPS): only registered while this
    // service runs, so an arm command against a stopped beacon is inert by
    // construction -- see SimGpsReceiver's KDoc.
    private var simReceiver: SimGpsReceiver? = null
    private var simNotifyJob: Job? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val fromBackground = intent == null || intent.getBooleanExtra(EXTRA_FROM_BACKGROUND, false)
        val micUsable = hasRecordAudio() && !fromBackground
        val types = safeForegroundTypes(Build.VERSION.SDK_INT, hasLocationPermission(), hasRecordAudio(), fromBackground)
        if (types == null) {
            startForeground(NOTIFICATION_ID, buildNotification(degradedText()))
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(degradedText()), types)
        }
        acquireWakeLock()
        // The wake lock is taken first (the odometer persist inside stop() needs
        // the CPU awake), which means a throw in start() leaves this service
        // alive, holding a wake lock, showing its notification — and broadcasting
        // nothing, forever, on a phone bolted to a vehicle. Observed 2026-08-11
        // after a cold boot. Never let that be silent again.
        runCatching { TelemetryBroadcaster.start(this, micEnabled = micUsable) }
            .onFailure { Log.e(TAG, "beacon: start() failed — the service is up but will not transmit", it) }
        // Separate from the GNSS start above and swallowed on failure: the version
        // announce is a nice-to-have, never worth risking the fleet's only GNSS.
        runCatching { startVersionBroadcast() }
            .onFailure { Log.w(TAG, "beacon: version announce failed to start (non-fatal)", it) }
        // Same treatment: the adb sim-control receiver is a test aid, never
        // worth risking the fleet's only GNSS if registration somehow fails.
        runCatching { registerSimReceiver() }
            .onFailure { Log.w(TAG, "beacon: sim GPS receiver registration failed (non-fatal)", it) }
        return START_STICKY
    }

    /**
     * Idempotent (mirrors [startVersionBroadcast]'s guard): a `START_STICKY`
     * re-delivery or a repeated manual start must not register a second
     * receiver. `RECEIVER_EXPORTED` is required at targetSdk 35 even though
     * the `android.permission.DUMP` argument means only the adb shell uid (or
     * system) can actually deliver to it.
     */
    private fun registerSimReceiver() {
        if (simReceiver != null) return
        val receiver = SimGpsReceiver()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_SIM_GPS)
                addAction(ACTION_SIM_GPS_OFF)
            },
            Manifest.permission.DUMP,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        simReceiver = receiver
        // Re-post the ongoing notification on arm/disarm so "GPS SIMULATED" is
        // visible without opening the app. Optional per the spec (droppable if
        // it fought the service-owns-its-notification lifecycle) -- it doesn't,
        // since this only ever calls the same buildNotification() the service
        // already owns, from a job this same service cancels in onDestroy.
        simNotifyJob =
            versionScope.launch {
                TelemetryBroadcaster.simState.collect { sim ->
                    val text = if (sim != null) "⚠ GPS SIMULATED — ${sim.spec.label}" else degradedText()
                    runCatching {
                        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        mgr.notify(NOTIFICATION_ID, buildNotification(text))
                    }
                }
            }
    }

    override fun onDestroy() {
        // Stop first: the odometer's final persist happens inside stop(), and it
        // must run with the CPU still awake, not race a wake lock release.
        TelemetryBroadcaster.stop()
        runCatching { versionBroadcaster?.stop() }
        versionBroadcaster = null
        simNotifyJob?.cancel()
        simNotifyJob = null
        simReceiver?.let { runCatching { unregisterReceiver(it) } }
        simReceiver = null
        versionScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Announce this beacon's build on the FLEET-1 version bus. Idempotent for a
     * `START_STICKY` restart or a repeated manual start — a second call must not
     * stack broadcasters. The sentence is fixed for the process; the broadcaster
     * re-sends it every 10 s and re-resolves its subnet-broadcast target from the
     * live DHCP lease (the phone can boot before the router has one).
     */
    private fun startVersionBroadcast() {
        if (versionBroadcaster != null) return
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val sentence = beaconVersionSentence(androidId, Build.MODEL)
        val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val broadcaster =
            VersionBroadcaster(
                scope = versionScope,
                dhcp = {
                    @Suppress("DEPRECATION")
                    val info = wifi?.dhcpInfo
                    DhcpLease(info?.ipAddress ?: 0, info?.netmask ?: 0)
                },
            )
        broadcaster.start(sentence)
        versionBroadcaster = broadcaster
        Log.i(TAG, "beacon: announcing version ${sentence.trim()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * A foreground service does not by itself keep the CPU awake — Doze stalls
     * the tick loop otherwise (AUDIT-2026-08-09 B4). Idempotent: a `START_STICKY`
     * restart or repeated manual start must not stack up wake lock objects.
     * No timeout: the service lifecycle *is* the timeout for a 14-day
     * unattended run — a sleeping beacon is operationally identical to a dead
     * one, and `$ZBCN`'s battery percentage gives hours of warning either way.
     */
    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zodiac:beacon").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun degradedText(): String =
        if (!hasLocationPermission()) {
            "Degraded: location permission missing — broadcasting sensors only"
        } else {
            "Broadcasting vehicle telemetry"
        }

    private fun buildNotification(text: String): Notification {
        // minSdk is 29, so the pre-O "no notification channels" branch was dead
        // code (Lint ObsoleteSdkInt). Channel creation is idempotent, and it must
        // stay unconditional: without the channel the foreground notification is
        // dropped and startForeground() kills the service.
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Zodiac Beacon", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zodiac Beacon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "zodiac_beacon"
        const val NOTIFICATION_ID = 1
    }
}
