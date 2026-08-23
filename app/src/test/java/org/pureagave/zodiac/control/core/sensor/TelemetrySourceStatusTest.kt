package org.pureagave.zodiac.control.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon

private val ACTIVE = LocationSourceState.Active(GpsFix(location = LatLon(lon = -119.2, lat = 40.78)))

class TelemetrySourceStatusTest {
    @Test
    fun net_active_is_on_beacon() {
        // Positive control for the headline row, and the one that kills a
        // mutant that inverts/ignores the usingFallback flag entirely.
        val status = telemetrySourceStatus(LocationSourceType.NET, ACTIVE, usingFallback = false)
        assertEquals(SourceStatusLine("● ON BEACON", TelemetrySource.ON_BEACON), status)
    }

    @Test
    fun net_active_fallback_is_local_backup() {
        // The headline: dropping the usingFallback branch entirely would make
        // this equal the previous test's result instead.
        val status = telemetrySourceStatus(LocationSourceType.NET, ACTIVE, usingFallback = true)
        assertEquals(SourceStatusLine("◐ LOCAL BACKUP", TelemetrySource.LOCAL_BACKUP), status)
    }

    @Test
    fun fallback_over_dead_source_is_no_signal_not_backup() {
        // A stale fallback flag over a genuinely dead source must never read
        // as a working backup — this kills a mutant that orders fallback
        // above liveness (checks usingFallback before the Active guard).
        val disconnected = telemetrySourceStatus(LocationSourceType.NET, LocationSourceState.Disconnected, usingFallback = true)
        assertEquals(SourceStatusLine("✕ NO SIGNAL", TelemetrySource.NO_SIGNAL), disconnected)

        val error =
            telemetrySourceStatus(
                LocationSourceType.NET,
                LocationSourceState.Error(detail = "boom", kind = LocationSourceError.IO_ERROR),
                usingFallback = true,
            )
        assertEquals(SourceStatusLine("✕ NO SIGNAL", TelemetrySource.NO_SIGNAL), error)
    }

    @Test
    fun manual_sources_name_themselves() {
        // Kills a mutant that hardcodes "NET" or otherwise drops the selected
        // source's own name from the manual-override line.
        val fake = telemetrySourceStatus(LocationSourceType.FAKE, ACTIVE, usingFallback = false)
        assertEquals(SourceStatusLine("◇ SRC FAKE", TelemetrySource.MANUAL_SOURCE), fake)

        val usb = telemetrySourceStatus(LocationSourceType.USB, ACTIVE, usingFallback = true)
        assertEquals(SourceStatusLine("◇ SRC USB", TelemetrySource.MANUAL_SOURCE), usb)
    }

    @Test
    fun searching_and_no_signal_rows() {
        // Kills a mutant that merges the transitional SEARCHING state into
        // the NO_SIGNAL fault (or vice versa).
        val searching = telemetrySourceStatus(LocationSourceType.NET, LocationSourceState.Searching, usingFallback = false)
        assertEquals(SourceStatusLine("⋯ SEARCHING", TelemetrySource.SEARCHING), searching)

        val error =
            telemetrySourceStatus(
                LocationSourceType.NET,
                LocationSourceState.Error(detail = "boom"),
                usingFallback = false,
            )
        assertEquals(SourceStatusLine("✕ NO SIGNAL", TelemetrySource.NO_SIGNAL), error)

        val disconnected = telemetrySourceStatus(LocationSourceType.NET, LocationSourceState.Disconnected, usingFallback = false)
        assertEquals(SourceStatusLine("✕ NO SIGNAL", TelemetrySource.NO_SIGNAL), disconnected)
    }
}
