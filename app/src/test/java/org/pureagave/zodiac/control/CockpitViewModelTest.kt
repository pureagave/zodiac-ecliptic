package org.pureagave.zodiac.control

import org.pureagave.zodiac.control.core.model.CockpitMode
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.Telemetry
import org.pureagave.zodiac.control.core.model.VehicleCommand
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.FakeVehicleGateway
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.sensor.LocationSourceRegistry
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.data.sensor.StubLocationSource
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun setHeading_sendsVehicleCommand() =
        runTest {
            val gateway = FakeVehicleGateway()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = gateway,
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                vm.setHeading(123)
                advanceUntilIdle()

                assertTrue(gateway.history().contains(VehicleCommand.SetHeading(123)))
            } finally {
                store.clear()
            }
        }

    @Test
    fun setMapMode_flipsUiState() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                assertEquals(MapMode.TOP, vm.uiState.value.mapMode)

                vm.setMapMode(MapMode.TILT)
                advanceUntilIdle()
                assertEquals(MapMode.TILT, vm.uiState.value.mapMode)

                vm.setMapMode(MapMode.TOP)
                advanceUntilIdle()
                assertEquals(MapMode.TOP, vm.uiState.value.mapMode)
            } finally {
                store.clear()
            }
        }

    @Test
    fun setTiltDeg_clampsToRange() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                vm.setTiltDeg(55)
                advanceUntilIdle()
                assertEquals(55, vm.uiState.value.tiltDeg)

                // Below MIN_TILT_DEG (=0) clamps to 0.
                vm.setTiltDeg(-30)
                advanceUntilIdle()
                assertEquals(0, vm.uiState.value.tiltDeg)

                // Above MAX_TILT_DEG (=80) clamps to 80.
                vm.setTiltDeg(120)
                advanceUntilIdle()
                assertEquals(80, vm.uiState.value.tiltDeg)
            } finally {
                store.clear()
            }
        }
}

private object NoOpPlayaMapRepository : PlayaMapRepository {
    override val map: Flow<PlayaMap> = emptyFlow()

    override suspend fun load() = Unit
}

private fun newFakeRoutedLocationSource(scope: CoroutineScope): RoutedLocationSource {
    val registry = LocationSourceRegistry(sources = listOf(StubLocationSource(LocationSourceType.FAKE)))
    return RoutedLocationSource(
        registry = registry,
        scope = scope,
        initialType = LocationSourceType.FAKE,
    )
}

private class StaticTelemetryRepo : TelemetryRepository {
    override fun stream(): Flow<Telemetry> =
        flowOf(
            Telemetry(
                headingDeg = 42,
                speedKph = 30,
                thermalC = 60,
                linkStable = true,
                mode = CockpitMode.DIAGNOSTIC,
            ),
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
