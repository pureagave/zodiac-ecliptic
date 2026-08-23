package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.sensor.LocationSourceError
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle tests for the BLE source: the same bug shape already found and
 * fixed in [NetworkLocationSource] (AUDIT-2026-08-09 C5) — an unguarded
 * `start()` that spawns a second listener, and a `stop()` that cancels without
 * joining, letting an in-flight read repopulate state *after* the teardown.
 *
 * Both matter on the vehicle: the source-selector chip is tappable as fast as
 * a finger moves, and `CockpitViewModel` init calls `select(saved)` — which
 * already starts the source — then `start()` again unconditionally.
 *
 * Everything but the join test runs on one [StandardTestDispatcher] shared by
 * the test body, the source's listener and its watchdog, so the orderings under
 * test are exact rather than raced. NB: never `advanceUntilIdle()` here — the
 * freshness watchdog is an infinite `while (isActive)` loop, so it never
 * returns (same reasoning as `SystemLocationSourceTest.settle`). The one
 * exception is
 * [stop_waits_for_a_line_already_in_flight_so_disconnected_is_the_last_word],
 * which needs real threads for the reason documented on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleLocationSourceTest {
    // Canonical valid GGA: 4807.038N 01131.000E → 48.1173, 11.5167 (checksum *47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    private fun TestScope.source(
        handle: FakeSppHandle,
        dispatcher: TestDispatcher,
    ) = BleLocationSource(
        handle = handle,
        scope = backgroundScope,
        ioDispatcher = dispatcher,
        nowMs = { testScheduler.currentTime },
    )

    // --- start() must be idempotent ------------------------------------------

    @Test
    fun a_second_start_while_running_opens_no_second_link() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). NB the runCurrent() between the two starts is load
            // bearing — without it the first listener never gets to run, so
            // even the unguarded code would only ever open one link and this
            // would pass for the wrong reason.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()
            source.start()
            runCurrent()

            assertEquals("a redundant start() must not open a second SPP socket", 1, handle.links.size)
            assertEquals("the one link must still be connected", 1, handle.links[0].connectCalls)
            assertFalse("the live link must not have been abandoned open", handle.links[0].isClosed)
        }

    @Test
    fun a_redundant_start_does_not_reset_a_live_fix_to_searching() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). The state half of the same bug: relaunching the listener
            // drops a working receiver back to Searching, which on the vehicle
            // is the cockpit losing its position for a beat on every launch.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            handle.links[0].deliver(validGga)
            runCurrent()
            assertTrue("precondition: a live fix", source.state.value is LocationSourceState.Active)

            source.start() // exactly what CockpitViewModel init does after select(saved)
            runCurrent()

            assertTrue(
                "a redundant start() must not discard a live fix; state=${source.state.value}",
                source.state.value is LocationSourceState.Active,
            )
        }

    // --- Error is no longer terminal: the listener retries on a capped backoff
    // and self-heals, the same fix NetworkLocationSource got (AUDIT-2026-08-10)
    // and BLE had been missing. A Fire has no GNSS, so a BLE receiver going away
    // and never being retried is no position at all until a manual re-select. ---

    @Test
    fun adapter_coming_back_reconnects_automatically_without_a_restart() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: restore the old terminal path (set Error and return out
            // of runConnection). The adapter comes back on and nothing ever
            // reconnects, because no second start() is issued here.
            val handle = FakeSppHandle(adapterOn = false)
            val source =
                BleLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    retryBaseMs = 1_000L,
                    retryMaxMs = 4_000L,
                )
            source.start()
            runCurrent()
            assertTrue("adapter off must surface as Error, honestly", source.state.value is LocationSourceState.Error)
            assertEquals("no link opened while the adapter is off", 0, handle.links.size)

            // Operator flips Bluetooth back on; the next backoff tick reconnects
            // on its own -- crucially, NO second start() is called.
            handle.adapterOn = true
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("the retry loop must reconnect by itself", 1, handle.links.size)

            handle.links[0].deliver(validGga)
            runCurrent()
            assertTrue(
                "a fix delivered after the auto-reconnect must go Active; state=${source.state.value}",
                source.state.value is LocationSourceState.Active,
            )
        }

    @Test
    fun a_failed_connect_retries_and_recovers_on_a_later_attempt() =
        runTestOnOneDispatcher { dispatcher ->
            // A connect that throws (receiver out of range) used to end the
            // listener for good. The link comes back into range; the next
            // attempt must connect. Mutation: terminal-return on the catch.
            val handle = FakeSppHandle(connectFailure = IOException("read failed, socket might closed"))
            val source =
                BleLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    retryBaseMs = 1_000L,
                    retryMaxMs = 4_000L,
                )
            source.start()
            runCurrent()
            assertTrue("the first connect fails, honestly Error", source.state.value is LocationSourceState.Error)
            assertEquals(1, handle.links.size)
            assertTrue("the failed link must be released before the retry", handle.links[0].isClosed)

            handle.connectFailure = null // receiver back in range
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("a second attempt must open a fresh link after the backoff", 2, handle.links.size)

            handle.links[1].deliver(validGga)
            runCurrent()
            assertTrue(source.state.value is LocationSourceState.Active)
        }

    @Test
    fun the_retry_backoff_doubles_and_is_capped() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: make the backoff constant (return retryBaseMs) or uncap
            // it. Asserted through the injected wait seam, so it's exact and not
            // load-sensitive. Every attempt fails fast (adapter off), so the six
            // waits are recorded synchronously before the loop parks.
            val waits = mutableListOf<Long>()
            val handle = FakeSppHandle(adapterOn = false)
            val source =
                BleLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    retryBaseMs = 100L,
                    retryMaxMs = 400L,
                    backoffWait = { ms ->
                        waits += ms
                        if (waits.size >= 6) awaitCancellation()
                    },
                )
            source.start()
            runCurrent()

            assertEquals(
                "backoff must double from the base and clamp at the ceiling",
                listOf(100L, 200L, 400L, 400L, 400L, 400L),
                waits,
            )
        }

    @Test
    fun stop_during_a_retry_backoff_returns_disconnected_and_makes_no_further_attempt() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: a non-cancellable backoff (Thread.sleep(it)) makes
            // stop()'s join wait out the full delay -- switching GPS source in
            // the cab would hang RoutedLocationSource's select() mutex. The
            // default (real delay) is cancelled instantly by stop().
            val handle = FakeSppHandle(adapterOn = false)
            val source =
                BleLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    retryBaseMs = 60_000L,
                    retryMaxMs = 60_000L,
                )
            source.start()
            runCurrent()
            assertTrue("precondition: parked in the backoff after a failed attempt", source.state.value is LocationSourceState.Error)

            source.stop()

            assertSame(LocationSourceState.Disconnected, source.state.value)
            assertEquals("stop() during a backoff must make no further connect attempt", 0, handle.links.size)
        }

    @Test
    fun a_working_session_that_drops_reconnects_at_the_base_interval_not_the_grown_one() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: drop `if (result.received) failures = 0`. Two early
            // failures grow the backoff; a receiver that then connects, streams,
            // and drops must reset it so recovery is quick -- not make the driver
            // wait out a backoff earned by an outage that is already over.
            val handle = FakeSppHandle(adapterOn = false)
            val source =
                BleLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    staleMs = 60_000L,
                    retryBaseMs = 100L,
                    retryMaxMs = 100_000L,
                )
            source.start()
            runCurrent() // attempt 1: adapter off -> wait 100
            advanceTimeBy(100L)
            runCurrent() // attempt 2: adapter off -> wait 200
            assertEquals("no link while the adapter is off", 0, handle.links.size)

            handle.adapterOn = true
            advanceTimeBy(200L)
            runCurrent() // attempt 3: connects
            assertEquals(1, handle.links.size)
            handle.links[0].deliver(validGga)
            runCurrent()
            assertTrue("precondition: a working session", source.state.value is LocationSourceState.Active)

            // The working session ends -> failures reset -> the next backoff is
            // the base 100 ms, NOT the grown 400 ms two prior failures earned.
            handle.links[0].endStream()
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(
                "a dropped-but-previously-working link must retry at the base interval, not the grown one",
                2,
                handle.links.size,
            )
        }

    @Test
    fun stop_then_start_opens_a_fresh_link() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            source.stop()
            source.start()
            runCurrent()

            assertEquals(2, handle.links.size)
            assertTrue("the first link must have been closed by stop()", handle.links[0].isClosed)
            assertFalse("the fresh link must be open", handle.links[1].isClosed)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    // --- stop() must join, not just cancel -----------------------------------

    @Test
    fun stop_waits_for_a_line_already_in_flight_so_disconnected_is_the_last_word() =
        runBlocking {
            // Mutation: drop the `listener?.join()` from stop() (i.e. cancel
            // only, as the code shipped).
            //
            // Cancellation is cooperative and a blocking socket read is not a
            // cancellation point, so a line the OS already handed over is
            // parsed and published *after* stop() cleared the state — leaving a
            // stopped source advertising a live fix that nothing will ever
            // refresh or demote (stop() killed the freshness watchdog too).
            //
            // Real threads on purpose, unlike every other test here: on a
            // single-threaded test scheduler `stop()`'s *other* suspension
            // point (joining the watchdog) drains the queued listener anyway,
            // so a missing listener join is invisible — verified, the mutation
            // above passes when this test is written in virtual time. The race
            // is only real when the listener is on its own thread.
            val handle = FakeSppHandle(readDelayMs = IN_FLIGHT_MS)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = BleLocationSource(handle = handle, scope = scope, ioDispatcher = Dispatchers.IO)
            try {
                source.start()
                assertTrue(
                    "precondition: the listener is blocked in a read",
                    waitUntil(TIMEOUT_MS) { handle.links.size == 1 && handle.links[0].readInFlight },
                )
                // The receiver hands over a line, and the operator taps a
                // different source chip in the same breath.
                handle.links[0].deliver(validGga)
                source.stop()

                assertSame(
                    "stop() must not report Disconnected while a line is still in flight",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
                Thread.sleep(IN_FLIGHT_MS * 3)
                assertSame(
                    "an in-flight line resurrected a stopped source as Active",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_closes_the_link_before_joining_so_it_cannot_hang_on_a_quiet_receiver() =
        runTestOnOneDispatcher(timeoutSeconds = 10) { dispatcher ->
            // Mutation: delete the `link?.close()` from stop(), or move it
            // after the join. An SPP read blocks with no timeout, so nothing
            // else ever unblocks the listener — stop() then never returns, and
            // it holds RoutedLocationSource's select() mutex while it doesn't.
            // The failure mode is a hang, so this test carries its own timeout.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            assertTrue("precondition: the listener is blocked in a read", handle.links[0].readInFlight)

            source.stop()

            assertTrue("stop() must close the socket it opened", handle.links[0].isClosed)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    @Test
    fun stop_without_a_prior_start_is_safe() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)

            source.stop()

            assertEquals(0, handle.links.size)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    // --- the error path must release what it acquired ------------------------

    @Test
    fun a_failed_connect_closes_the_link_it_opened() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block in
            // runConnection. An SPP socket left open after a failed connect is
            // not reclaimed until the process dies, and Android will not hand
            // out a second link to the same device — so one out-of-range
            // attempt made the receiver unusable for the rest of the burn.
            val handle = FakeSppHandle(connectFailure = IOException("read failed, socket might closed"))
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()

            val state = source.state.value
            assertTrue("a failed connect must surface as an error; state=$state", state is LocationSourceState.Error)
            assertEquals(LocationSourceError.IO_ERROR, (state as LocationSourceState.Error).kind)
            assertTrue("a failed connect must not leak its socket", handle.links[0].isClosed)
        }

    @Test
    fun an_ended_stream_closes_the_link() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block. A
            // receiver that ends the stream (powered off, out of range long
            // enough for the stack to give up) leaves readLine() returning
            // null — the one exit path that never threw, so the old code fell
            // out of the loop and returned with the socket still held.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()

            handle.links[0].endStream()
            runCurrent()

            assertTrue("end of stream must release the socket", handle.links[0].isClosed)
        }

    @Test
    fun start_without_permission_opens_nothing() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeSppHandle(permission = false)
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()

            assertEquals(0, handle.links.size)
            val state = source.state.value
            assertTrue(state is LocationSourceState.Error)
            assertEquals(LocationSourceError.PERMISSION_DENIED, (state as LocationSourceState.Error).kind)
        }

    /**
     * One [StandardTestDispatcher] for the test body, the listener and the
     * watchdog. Sharing the *instance* matters: `stop()` closes the link inside
     * `withContext(ioDispatcher)`, and a second dispatcher would make that a
     * real dispatch, handing the in-flight listener a chance to run before
     * `stop()` reaches its join — which would hide the very bug
     * [stop_waits_for_a_line_already_in_flight_so_disconnected_is_the_last_word]
     * exists to catch.
     */
    private fun runTestOnOneDispatcher(
        timeoutSeconds: Int = 30,
        body: suspend TestScope.(TestDispatcher) -> Unit,
    ) = StandardTestDispatcher().let { dispatcher ->
        runTest(dispatcher, timeout = timeoutSeconds.seconds) { body(dispatcher) }
    }

    private inline fun waitUntil(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(POLL_MS)
        }
        return cond()
    }

    private companion object {
        /**
         * How long the fake holds a line it has already been handed before the
         * source publishes it. In production that window is microseconds wide
         * — the gap between a blocking read returning and the parse landing —
         * and widening it here is what makes the race observable rather than
         * lucky.
         */
        const val IN_FLIGHT_MS: Long = 150L
        const val TIMEOUT_MS: Long = 4_000L
        const val POLL_MS: Long = 10L
    }
}

/**
 * A paired receiver that behaves like a real SPP link: [readLine] blocks until
 * a line arrives or the socket is closed, and — the part that matters — it
 * suspends via [suspendCoroutine], not `suspendCancellableCoroutine`, because a
 * blocking socket read does *not* abort when the coroutine is cancelled.
 * Modelling it as cancellable would quietly test a bug shape the real source
 * cannot have, and every stop() test would pass for the wrong reason.
 */
private class FakeSppLink(
    private val connectFailure: Exception?,
    /**
     * Null: [readLine] suspends on the test scheduler, so virtual-time tests
     * stay deterministic. Set: [readLine] blocks a real thread and then holds
     * the line for this long before returning it — the only way to observe
     * whether `stop()` really waits for the listener, since a single-threaded
     * test scheduler runs the queued listener during `stop()`'s other joins
     * regardless.
     */
    private val readDelayMs: Long?,
) : BluetoothSppLink {
    var connectCalls: Int = 0
        private set

    private var waiting: Continuation<String?>? = null

    @Volatile private var blocked = false

    @Volatile private var closed = false

    private val handoff = LinkedBlockingQueue<String>()

    /** A real close is idempotent, so "was it released" is the question, not how often. */
    val isClosed: Boolean get() = closed

    val readInFlight: Boolean get() = blocked || waiting != null

    override suspend fun connect() {
        connectCalls++
        connectFailure?.let { throw it }
    }

    override suspend fun readLine(): String? =
        if (readDelayMs == null) {
            if (closed) null else suspendCoroutine { cont -> waiting = cont }
        } else {
            blockingRead(readDelayMs)
        }

    private fun blockingRead(delayMs: Long): String? {
        blocked = true
        val item = handoff.take()
        blocked = false
        Thread.sleep(delayMs)
        return item.takeIf { it != EOF }
    }

    override fun close() {
        closed = true
        // Closing the socket is what unblocks a blocked read — the real one
        // returns end-of-stream or throws; either ends the loop.
        resumeWith(null)
    }

    /** Hand the blocked read a line, as the receiver would. */
    fun deliver(line: String) = resumeWith(line)

    /** The receiver ends the stream without an error (powered off, out of range). */
    fun endStream() = resumeWith(null)

    private fun resumeWith(line: String?) {
        if (readDelayMs != null) {
            handoff.put(line ?: EOF)
            return
        }
        val cont = waiting ?: return
        waiting = null
        cont.resume(line)
    }

    private companion object {
        /** Sentinel for end-of-stream on the blocking hand-off queue. */
        const val EOF: String = " "
    }
}

private class FakeSppHandle(
    private val permission: Boolean = true,
    var adapterOn: Boolean = true,
    private val paired: List<String> = listOf("Garmin GLO 1234"),
    // var, not val: a test flips a failing connect back to succeeding to model a
    // receiver returning to range between retry attempts.
    var connectFailure: Exception? = null,
    private val readDelayMs: Long? = null,
) : BluetoothSppHandle {
    val links = mutableListOf<FakeSppLink>()

    override fun hasConnectPermission(): Boolean = permission

    override fun isAdapterEnabled(): Boolean = adapterOn

    override fun pairedDeviceNames(): List<String> = paired

    override fun createLink(deviceName: String): BluetoothSppLink = FakeSppLink(connectFailure, readDelayMs).also { links += it }
}
