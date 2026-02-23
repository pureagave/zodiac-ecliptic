package org.pureagave.zodiac.control.data.transport

import org.pureagave.zodiac.control.core.connection.ConnectionState
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.VehicleCommand
import kotlinx.coroutines.flow.StateFlow

interface TransportAdapter {
    val type: TransportType
    val state: StateFlow<ConnectionState>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun send(command: VehicleCommand)
}
