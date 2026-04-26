package org.pureagave.zodiac.control

import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.FakeTelemetryRepository
import org.pureagave.zodiac.control.data.RoutedVehicleGateway
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.VehicleConnectionGateway
import org.pureagave.zodiac.control.data.playa.AssetsPlayaMapRepository
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.sensor.BleLocationSource
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.LocationSourceRegistry
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.data.sensor.SystemLocationSource
import org.pureagave.zodiac.control.data.sensor.UsbLocationSource
import org.pureagave.zodiac.control.data.transport.FakeTransportAdapter
import org.pureagave.zodiac.control.data.transport.TransportRegistry
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime owner for the cockpit's manual DI graph. Replaces the
 * previous `remember { MainScope() }` inside the Composable, which leaked
 * the scope on Activity recreation. The registry, routed sources, and
 * gateway live as long as the process — exactly what we want for sensor
 * subscriptions that should outlast a configuration change.
 */
class ZodiacApplication : Application() {
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val telemetryRepository: TelemetryRepository by lazy { FakeTelemetryRepository() }

    val vehicleGateway: VehicleConnectionGateway by lazy {
        val registry =
            TransportRegistry(
                adapters =
                    listOf(
                        FakeTransportAdapter(TransportType.BLE),
                        FakeTransportAdapter(TransportType.USB),
                        FakeTransportAdapter(TransportType.WIFI),
                    ),
            )
        RoutedVehicleGateway(
            transportRegistry = registry,
            initialTransport = TransportType.BLE,
        )
    }

    val playaMapRepository: PlayaMapRepository by lazy { AssetsPlayaMapRepository(assets) }

    val locationSource: RoutedLocationSource by lazy {
        val registry =
            LocationSourceRegistry(
                sources =
                    listOf(
                        FakeLocationSource(scope = applicationScope),
                        SystemLocationSource(applicationContext = this),
                        BleLocationSource(
                            applicationContext = this,
                            scope = applicationScope,
                        ),
                        UsbLocationSource(
                            applicationContext = this,
                            scope = applicationScope,
                        ),
                    ),
            )
        RoutedLocationSource(
            registry = registry,
            scope = applicationScope,
            initialType = LocationSourceType.FAKE,
        )
    }
}
