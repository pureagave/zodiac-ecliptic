package org.pureagave.zodiac.control

import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.FakeTelemetryRepository
import org.pureagave.zodiac.control.data.RoutedVehicleGateway
import org.pureagave.zodiac.control.data.playa.AssetsPlayaMapRepository
import org.pureagave.zodiac.control.data.sensor.BleLocationSource
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.LocationSourceRegistry
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.data.sensor.SystemLocationSource
import org.pureagave.zodiac.control.data.sensor.UsbLocationSource
import org.pureagave.zodiac.control.data.transport.FakeTransportAdapter
import org.pureagave.zodiac.control.data.transport.TransportRegistry
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModelFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.MainScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { zodiacApp() }
    }
}

@Composable
private fun zodiacApp() {
    val context = LocalContext.current
    val gateway =
        remember {
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
    val playaMapRepository = remember { AssetsPlayaMapRepository(context.assets) }
    val locationSource =
        remember {
            val sensorScope = MainScope()
            val registry =
                LocationSourceRegistry(
                    sources =
                        listOf(
                            FakeLocationSource(scope = sensorScope),
                            SystemLocationSource(applicationContext = context.applicationContext),
                            BleLocationSource(
                                applicationContext = context.applicationContext,
                                scope = sensorScope,
                            ),
                            UsbLocationSource(
                                applicationContext = context.applicationContext,
                                scope = sensorScope,
                            ),
                        ),
                )
            RoutedLocationSource(
                registry = registry,
                scope = sensorScope,
                initialType = LocationSourceType.FAKE,
            )
        }

    val viewModel: CockpitViewModel =
        viewModel(
            factory =
                CockpitViewModelFactory(
                    telemetryRepository = FakeTelemetryRepository(),
                    vehicleGateway = gateway,
                    playaMapRepository = playaMapRepository,
                    locationSource = locationSource,
                ),
        )
    crtVectorScreen(viewModel = viewModel)
}
