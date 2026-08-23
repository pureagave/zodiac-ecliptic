package org.pureagave.zodiac.control.data.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pureagave.zodiac.control.core.model.batteryPercent
import timber.log.Timber

/**
 * This device's own battery percent, from the sticky `ACTION_BATTERY_CHANGED`
 * broadcast — no permission required, unlike most battery APIs. Feeds MAP's
 * CELL A tile (COCKPIT-TELEMETRY-REDESIGN Phase D): every cockpit tablet must
 * live on permanent vehicle power, and a failed charger is invisible today
 * until the screen dies.
 *
 * Same thin-source shape as the `data/sensor` location sources: this class
 * talks to the platform and nothing else — [batteryPercent] (pure, tested)
 * does the arithmetic. [percent] is `null` until the first reading (or on
 * garbage extras), which the UI renders as "—", never a fake 0.
 */
class DeviceBatterySource(private val context: Context) {
    private val mutablePercent = MutableStateFlow<Int?>(null)

    /** Latest device battery percent; null until first reading / on garbage extras. */
    val percent: StateFlow<Int?> = mutablePercent.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /**
     * Idempotent: a second call while already registered is a no-op.
     * `ACTION_BATTERY_CHANGED`'s intent is sticky, so registration hands back
     * the current battery state synchronously (the return value here) — fold
     * that first value immediately rather than waiting for the next real
     * broadcast, which can be minutes away.
     */
    fun start() {
        if (receiver != null) return
        val newReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    receivedContext: Context?,
                    intent: Intent?,
                ) {
                    intent?.let(::fold)
                }
            }
        receiver = newReceiver
        registerBatteryReceiver(newReceiver)?.let(::fold)
    }

    /** Idempotent: a second call, or one before [start], is a no-op. */
    fun stop() {
        val current = receiver ?: return
        receiver = null
        unregisterBatteryReceiver(current)
    }

    private fun fold(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        mutablePercent.value = batteryPercent(level, scale)
    }

    // Broad catch at the register/unregister boundary: a failure here (a
    // stripped-down vendor ROM, a double-unregister race) must leave the
    // tile reading "—", never crash the cockpit over a battery readout.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun registerBatteryReceiver(newReceiver: BroadcastReceiver): Intent? =
        try {
            ContextCompat.registerReceiver(
                context,
                newReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Timber.w(e, "DeviceBatterySource: registerReceiver failed")
            null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun unregisterBatteryReceiver(current: BroadcastReceiver) {
        try {
            context.unregisterReceiver(current)
        } catch (e: Exception) {
            Timber.w(e, "DeviceBatterySource: unregisterReceiver failed")
        }
    }
}
