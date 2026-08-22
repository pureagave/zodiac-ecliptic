package org.pureagave.zodiac.control.core.passenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What this particular tablet is *for* — driver cockpit or passenger display.
 *
 * Process-scoped and deliberately **not** part of `CockpitUiState`: it isn't
 * cockpit state at all. It doesn't change while driving, no concept reads it,
 * and it belongs to the device rather than the session — the same reasoning
 * that keeps burn-in configuration out of the ViewModel.
 *
 * Persisted, because a passenger tablet that forgets its role after a power
 * cycle is useless in a vehicle where nobody is going to reconfigure it.
 *
 * Tri-state contract: [read] returns `null` when the operator has never
 * toggled this tablet's role, in which case the effective mode follows
 * [deviceDefault] (the hardware fact — non-OLED/Fire ⇒ passenger, OLED/Samsung
 * ⇒ cockpit). An explicit [setPassengerMode] call persists `true`/`false` and
 * that value wins forever after, surviving reboots — there is deliberately no
 * way to clear an override back to auto short of a prefs wipe.
 */
class DisplayRoleStore(
    private val scope: CoroutineScope,
    private val deviceDefault: Boolean,
    private val read: suspend () -> Boolean?,
    private val write: suspend (Boolean) -> Unit,
) {
    // Seeded with deviceDefault, not false: the DataStore read is async, so
    // until it lands a Fire must already read as passenger — it must never
    // flash the driver cockpit at riders on boot. The rare inverse (a Fire the
    // operator forced to cockpit briefly shows the carousel for the few ms
    // until the read completes) is the right side of that trade.
    private val _passengerMode = MutableStateFlow(deviceDefault)
    val passengerMode: StateFlow<Boolean> = _passengerMode.asStateFlow()

    init {
        scope.launch { _passengerMode.value = read() ?: deviceDefault }
    }

    /** Flip this tablet's role and remember it — this write is what *creates* the override. */
    fun setPassengerMode(enabled: Boolean) {
        _passengerMode.value = enabled
        scope.launch { write(enabled) }
    }
}
