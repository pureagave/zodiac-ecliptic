package org.pureagave.zodiac.control.data

import org.pureagave.zodiac.control.core.model.Telemetry
import kotlinx.coroutines.flow.Flow

interface TelemetryRepository {
    fun stream(): Flow<Telemetry>
}
