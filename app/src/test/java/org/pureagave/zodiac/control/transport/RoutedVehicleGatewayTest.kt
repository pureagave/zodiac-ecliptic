package org.pureagave.zodiac.control.transport

import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.VehicleCommand
import org.pureagave.zodiac.control.data.RoutedVehicleGateway
import org.pureagave.zodiac.control.data.transport.FakeTransportAdapter
import org.pureagave.zodiac.control.data.transport.TransportRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedVehicleGatewayTest {
    @Test
    fun routes_command_to_selected_transport() {
        runTest {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val wifi = FakeTransportAdapter(TransportType.WIFI)
            ble.connect()
            wifi.connect()

            val registry = TransportRegistry(listOf(ble, wifi))
            val gateway = RoutedVehicleGateway(registry, TransportType.WIFI)

            gateway.send(VehicleCommand.SetHeading(90))

            assertTrue(wifi.commandHistory().contains(VehicleCommand.SetHeading(90)))
        }
    }
}
