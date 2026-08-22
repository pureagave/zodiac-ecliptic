package org.pureagave.zodiac.control.core.passenger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DisplayRoleStore]'s tri-state contract: `read() == null` (never toggled)
 * must resolve to [DisplayRoleStore]'s `deviceDefault`; an explicit
 * `true`/`false` from `read()` is an operator override that beats the
 * default. No mocking framework — `read`/`write` are plain lambdas closing
 * over local vars, per the project's test style for small collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayRoleStoreTest {
    @Test
    fun never_toggled_fire_defaults_to_passenger() =
        runTest {
            val store =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = true,
                    read = { null },
                    write = {},
                )
            advanceUntilIdle()

            assertEquals(true, store.passengerMode.value)
        }

    @Test
    fun never_toggled_samsung_defaults_to_cockpit() =
        runTest {
            val store =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = false,
                    read = { null },
                    write = {},
                )
            advanceUntilIdle()

            assertEquals(false, store.passengerMode.value)
        }

    @Test
    fun explicit_cockpit_override_beats_fire_default() =
        runTest {
            // A Fire (default=true) an operator forced into the cockpit
            // (persisted false) must stay cockpit -- the headline behaviour.
            val store =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = true,
                    read = { false },
                    write = {},
                )
            advanceUntilIdle()

            assertEquals(false, store.passengerMode.value)
        }

    @Test
    fun explicit_passenger_override_beats_samsung_default() =
        runTest {
            // Control for the row above: a Samsung (default=false) an
            // operator made a passenger display (persisted true) must stay
            // passenger, proving the precedence isn't just "always false".
            val store =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = false,
                    read = { true },
                    write = {},
                )
            advanceUntilIdle()

            assertEquals(true, store.passengerMode.value)
        }

    @Test
    fun initial_value_is_device_default_before_read_completes() =
        runTest {
            // The DataStore read is async; until it lands, the flow must
            // already read as deviceDefault (true here) so a Fire never
            // flashes the driver cockpit at riders on boot. A store that
            // seeds MutableStateFlow(false) unconditionally -- the old
            // boot-flash bug -- would fail this before the deferred
            // resolves.
            val pending = CompletableDeferred<Boolean?>()
            val store =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = true,
                    read = { pending.await() },
                    write = {},
                )

            assertEquals(true, store.passengerMode.value)

            pending.complete(false)
            advanceUntilIdle()

            assertEquals(false, store.passengerMode.value)
        }

    @Test
    fun toggle_writes_explicit_value_and_a_restart_keeps_it() =
        runTest {
            var written: Boolean? = null
            val store =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = true,
                    read = { null },
                    write = { written = it },
                )
            advanceUntilIdle()

            store.setPassengerMode(false)
            advanceUntilIdle()

            assertEquals(false, written)

            // "Reboot": a fresh store whose read() now returns what the
            // first store persisted. If setPassengerMode failed to persist,
            // written would still be null; if the store re-derived from
            // hardware instead of the persisted value, this would resolve
            // to deviceDefault (true) instead of the override (false).
            val restarted =
                DisplayRoleStore(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    deviceDefault = true,
                    read = { written },
                    write = {},
                )
            advanceUntilIdle()

            assertEquals(false, restarted.passengerMode.value)
        }
}
