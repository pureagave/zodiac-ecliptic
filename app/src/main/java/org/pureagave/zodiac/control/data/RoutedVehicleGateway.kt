package org.pureagave.zodiac.control.data

import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.VehicleCommand
import org.pureagave.zodiac.control.data.transport.TransportRegistry

class RoutedVehicleGateway(
    private val transportRegistry: TransportRegistry,
    private val selectedTransport: TransportType,
) : VehicleGateway {
    override suspend fun send(command: VehicleCommand) {
        transportRegistry.adapterFor(selectedTransport).send(command)
    }
}
