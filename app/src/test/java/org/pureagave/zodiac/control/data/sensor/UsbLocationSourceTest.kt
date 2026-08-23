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
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle tests for the USB source — same bug shape and same reasoning as
 * [BleLocationSourceTest]; see its header for why everything shares one
 * [StandardTestDispatcher] and why `advanceUntilIdle()` must never be used here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbLocationSourceTest {
    // Canonical valid GGA: 4807.038N 01131.000E → 48.1173, 11.5167 (checksum *47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"

    private fun TestScope.source(
        handle: FakeUsbHandle,
        dispatcher: TestDispatcher,
    ) = UsbLocationSource(
        handle = handle,
        scope = backgroundScope,
        ioDispatcher = dispatcher,
        nowMs = { testScheduler.currentTime },
    )

    // --- start() must be idempotent ------------------------------------------

    @Test
    fun a_second_start_while_running_opens_no_second_port() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). NB the runCurrent() between the two starts is load
            // bearing — without it the first listener never gets to run, so
            // even the unguarded code would only ever attach once and this
            // would pass for the wrong reason.
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()
            source.start()
            runCurrent()

            assertEquals("a redundant start() must not attach a second port", 1, handle.links.size)
            assertEquals("the one port must still be open", 1, handle.links[0].openCalls)
            assertFalse("the live port must not have been abandoned open", handle.links[0].isClosed)
        }

    @Test
    fun a_redundant_start_does_not_reset_a_live_fix_to_searching() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). The state half of the same bug: relaunching the listener
            // drops a working dongle back to Searching, which on the vehicle is
            // the cockpit losing its position for a beat on every launch.
            val handle = FakeUsbHandle()
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
    // and self-heals, the same fix NetworkLocationSource / BleLocationSource got
    // and USB had been missing. ---

    @Test
    fun a_dongle_reappearing_reconnects_automatically_without_a_restart() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: restore the old terminal path (set Error and return out
            // of runConnection). The dongle is re-seated and nothing reconnects,
            // because no second start() is issued here.
            val handle = FakeUsbHandle(unavailable = UsbAttach.Unavailable("no dongle", LocationSourceError.NO_DEVICE_FOUND))
            val source =
                UsbLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    retryBaseMs = 1_000L,
                    retryMaxMs = 4_000L,
                )
            source.start()
            runCurrent()
            assertTrue("an unattached dongle must surface as Error", source.state.value is LocationSourceState.Error)
            assertEquals("no port attached while the dongle is absent", 0, handle.links.size)

            // The dongle is re-seated; the next backoff tick reconnects on its
            // own -- crucially, NO second start() is called.
            handle.unavailable = null
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
    fun a_failed_open_retries_and_recovers_on_a_later_attempt() =
        runTestOnOneDispatcher { dispatcher ->
            // A failed open (a bad-baud cable moment) used to end the listener
            // for good. The next attempt must open. Mutation: terminal-return.
            val handle = FakeUsbHandle(openFailure = IOException("Error setting baud rate"))
            val source =
                UsbLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    retryBaseMs = 1_000L,
                    retryMaxMs = 4_000L,
                )
            source.start()
            runCurrent()
            assertTrue("the first open fails, honestly Error", source.state.value is LocationSourceState.Error)
            assertEquals(1, handle.links.size)
            assertTrue("the failed port must be released before the retry", handle.links[0].isClosed)

            handle.openFailure = null // cable re-seated
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("a second attempt must attach a fresh port after the backoff", 2, handle.links.size)

            handle.links[1].deliver(validGga)
            runCurrent()
            assertTrue(source.state.value is LocationSourceState.Active)
        }

    @Test
    fun the_retry_backoff_doubles_and_is_capped() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: make the backoff constant (return retryBaseMs) or uncap
            // it. Asserted through the injected wait seam, so it's exact and not
            // load-sensitive. Every attempt fails fast (dongle absent).
            val waits = mutableListOf<Long>()
            val handle = FakeUsbHandle(unavailable = UsbAttach.Unavailable("no dongle", LocationSourceError.NO_DEVICE_FOUND))
            val source =
                UsbLocationSource(
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
            val handle = FakeUsbHandle(unavailable = UsbAttach.Unavailable("no dongle", LocationSourceError.NO_DEVICE_FOUND))
            val source =
                UsbLocationSource(
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
            assertEquals("stop() during a backoff must make no further attach attempt", 0, handle.links.size)
        }

    @Test
    fun a_working_session_that_drops_reconnects_at_the_base_interval_not_the_grown_one() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: drop `if (result.received) failures = 0`. Two early
            // failures grow the backoff; a dongle that then attaches, streams,
            // and is yanked must reset it so recovery is quick. USB signals the
            // drop by a *thrown* read, so this also pins that pumpNmea carries
            // the received flag out through the exception (PumpResult).
            val handle = FakeUsbHandle(unavailable = UsbAttach.Unavailable("no dongle", LocationSourceError.NO_DEVICE_FOUND))
            val source =
                UsbLocationSource(
                    handle = handle,
                    scope = backgroundScope,
                    ioDispatcher = dispatcher,
                    nowMs = { testScheduler.currentTime },
                    staleMs = 60_000L,
                    retryBaseMs = 100L,
                    retryMaxMs = 100_000L,
                )
            source.start()
            runCurrent() // attempt 1: absent -> wait 100
            advanceTimeBy(100L)
            runCurrent() // attempt 2: absent -> wait 200
            assertEquals("no port while the dongle is absent", 0, handle.links.size)

            handle.unavailable = null
            advanceTimeBy(200L)
            runCurrent() // attempt 3: attaches
            assertEquals(1, handle.links.size)
            handle.links[0].deliver(validGga)
            runCurrent()
            assertTrue("precondition: a working session", source.state.value is LocationSourceState.Active)

            // The dongle is yanked mid-read -> the read throws, but the session
            // read data, so failures reset and the next backoff is the base
            // 100 ms, NOT the grown 400 ms two prior failures earned.
            handle.links[0].failRead(IOException("Connection closed"))
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(
                "a dropped-but-previously-working dongle must retry at the base interval, not the grown one",
                2,
                handle.links.size,
            )
        }

    @Test
    fun stop_then_start_attaches_a_fresh_port() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            source.stop()
            source.start()
            runCurrent()

            assertEquals(2, handle.links.size)
            assertTrue("the first port must have been closed by stop()", handle.links[0].isClosed)
            assertFalse("the fresh port must be open", handle.links[1].isClosed)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    // --- stop() must join, not just cancel -----------------------------------

    @Test
    fun stop_waits_for_bytes_already_in_flight_so_disconnected_is_the_last_word() =
        runBlocking {
            // Mutation: drop the `listener?.join()` from stop() (i.e. cancel
            // only, as the code shipped).
            //
            // Cancellation is cooperative and a blocking bulk read is not a
            // cancellation point, so bytes the driver already handed over are
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
            val handle = FakeUsbHandle(readDelayMs = IN_FLIGHT_MS)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = UsbLocationSource(handle = handle, scope = scope, ioDispatcher = Dispatchers.IO)
            try {
                source.start()
                assertTrue(
                    "precondition: the listener is blocked in a read",
                    waitUntil(TIMEOUT_MS) { handle.links.size == 1 && handle.links[0].readInFlight },
                )
                // The dongle hands over a sentence, and the operator taps a
                // different source chip in the same breath.
                handle.links[0].deliver(validGga)
                source.stop()

                assertSame(
                    "stop() must not report Disconnected while bytes are still in flight",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
                Thread.sleep(IN_FLIGHT_MS * 3)
                assertSame(
                    "in-flight bytes resurrected a stopped source as Active",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_closes_the_port_before_joining_so_it_cannot_hang_on_a_quiet_dongle() =
        runTestOnOneDispatcher(timeoutSeconds = 10) { dispatcher ->
            // Mutation: delete the `link?.close()` from stop(), or move it
            // after the join. The read is a blocking bulk transfer, so nothing
            // else unblocks the listener — stop() then never returns, and it
            // holds RoutedLocationSource's select() mutex while it doesn't.
            // The failure mode is a hang, so this test carries its own timeout.
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            assertTrue("precondition: the listener is blocked in a read", handle.links[0].readInFlight)

            source.stop()

            assertTrue("stop() must close the port it opened", handle.links[0].isClosed)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    @Test
    fun stop_without_a_prior_start_is_safe() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)

            source.stop()

            assertEquals(0, handle.links.size)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    // --- the error path must release what it acquired ------------------------

    @Test
    fun a_failed_open_closes_the_port_it_attached() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block in
            // runConnection. A port left open is not reclaimed until the
            // process dies, and the driver will not open it twice — so one bad
            // cable moment made the dongle unusable for the rest of the burn.
            val handle = FakeUsbHandle(openFailure = IOException("Error setting baud rate"))
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()

            val state = source.state.value
            assertTrue("a failed open must surface as an error; state=$state", state is LocationSourceState.Error)
            assertEquals(LocationSourceError.IO_ERROR, (state as LocationSourceState.Error).kind)
            assertTrue("a failed open must not leak the port", handle.links[0].isClosed)
        }

    @Test
    fun a_read_that_throws_closes_the_port() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block. A
            // dongle yanked mid-drive throws out of the read loop; the old code
            // reported the error and returned with the port still held, so
            // plugging it back in could never reopen it.
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()

            handle.links[0].failRead(IOException("Connection closed"))
            runCurrent()

            assertTrue("a read fault must release the port", handle.links[0].isClosed)
            assertTrue(source.state.value is LocationSourceState.Error)
        }

    @Test
    fun an_unavailable_dongle_reports_its_own_reason() =
        runTestOnOneDispatcher { dispatcher ->
            val handle =
                FakeUsbHandle(
                    unavailable =
                        UsbAttach.Unavailable("USB device permission not granted", LocationSourceError.PERMISSION_DENIED),
                )
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
     * watchdog. Sharing the *instance* matters: `stop()` closes the port inside
     * `withContext(ioDispatcher)`, and a second dispatcher would make that a
     * real dispatch, handing the in-flight listener a chance to run before
     * `stop()` reaches its join — which would hide the very bug
     * [stop_waits_for_bytes_already_in_flight_so_disconnected_is_the_last_word]
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
         * How long the fake holds bytes it has already been handed before the
         * source parses them. In production that window is microseconds wide —
         * the gap between a blocking read returning and the parse landing —
         * and widening it here is what makes the race observable rather than
         * lucky.
         */
        const val IN_FLIGHT_MS: Long = 150L
        const val TIMEOUT_MS: Long = 4_000L
        const val POLL_MS: Long = 10L
    }
}

/**
 * A dongle that behaves like a real serial port: [read] blocks until bytes
 * arrive or the port is closed, and — the part that matters — it suspends via
 * [suspendCoroutine], not `suspendCancellableCoroutine`, because a blocking
 * bulk read does *not* abort when the coroutine is cancelled. Modelling it as
 * cancellable would quietly test a bug shape the real source cannot have, and
 * every stop() test would pass for the wrong reason.
 */
private class FakeUsbLink(
    private val openFailure: Exception?,
    /**
     * Null: [read] suspends on the test scheduler, so virtual-time tests stay
     * deterministic. Set: [read] blocks a real thread and then holds the bytes
     * for this long before returning them — the only way to observe whether
     * `stop()` really waits for the listener, since a single-threaded test
     * scheduler runs the queued listener during `stop()`'s other joins
     * regardless.
     */
    private val readDelayMs: Long?,
) : UsbSerialLink {
    var openCalls: Int = 0
        private set

    private var waiting: Continuation<ByteArray>? = null

    @Volatile private var blocked = false

    @Volatile private var closed = false

    private val handoff = LinkedBlockingQueue<ByteArray>()

    /** A real close is idempotent, so "was it released" is the question, not how often. */
    val isClosed: Boolean get() = closed

    val readInFlight: Boolean get() = blocked || waiting != null

    override suspend fun open(baudRate: Int) {
        openCalls++
        openFailure?.let { throw it }
    }

    override suspend fun read(): ByteArray {
        if (readDelayMs != null) return blockingRead(readDelayMs)
        // Reading a closed port throws rather than returning nothing, which is
        // both what the driver does and what keeps a stray loop from spinning.
        if (closed) throw IOException("port closed")
        return suspendCoroutine { cont -> waiting = cont }
    }

    private fun blockingRead(delayMs: Long): ByteArray {
        blocked = true
        val chunk = handoff.take()
        blocked = false
        Thread.sleep(delayMs)
        return chunk
    }

    override fun close() {
        closed = true
        // Closing the port is what unblocks a blocked read.
        resumeWith(ByteArray(0))
    }

    /** Hand the blocked read a chunk, as the driver would. */
    fun deliver(text: String) = resumeWith(text.toByteArray(Charsets.US_ASCII))

    /** The dongle is yanked mid-read. */
    fun failRead(ex: Exception) {
        val cont = waiting ?: return
        waiting = null
        cont.resumeWithException(ex)
    }

    private fun resumeWith(chunk: ByteArray) {
        if (readDelayMs != null) {
            handoff.put(chunk)
            return
        }
        val cont = waiting ?: return
        waiting = null
        cont.resume(chunk)
    }
}

private class FakeUsbHandle(
    var unavailable: UsbAttach.Unavailable? = null,
    // var, not val: a test flips a failing open back to succeeding to model a
    // dongle re-seated between retry attempts.
    var openFailure: Exception? = null,
    private val readDelayMs: Long? = null,
) : UsbSerialHandle {
    val links = mutableListOf<FakeUsbLink>()

    override fun attach(): UsbAttach = unavailable ?: UsbAttach.Attached(FakeUsbLink(openFailure, readDelayMs).also { links += it })
}
