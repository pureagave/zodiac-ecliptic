package org.pureagave.zodiac.control.data

import org.pureagave.zodiac.control.core.model.VehicleCommand

interface VehicleGateway {
    suspend fun send(command: VehicleCommand)
}
