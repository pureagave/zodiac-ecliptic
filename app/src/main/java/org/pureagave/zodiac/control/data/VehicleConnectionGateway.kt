package org.pureagave.zodiac.control.data

import org.pureagave.zodiac.control.core.connection.ConnectionState
import org.pureagave.zodiac.control.core.connection.TransportType
import kotlinx.coroutines.flow.StateFlow

interface VehicleConnectionGateway : VehicleGateway {
    val selectedTransport: StateFlow<TransportType>
    val connectionState: StateFlow<ConnectionState>

    suspend fun selectTransport(transportType: TransportType)

    suspend fun connect()

    suspend fun disconnect()
}
