package org.pureagave.zodiac.control.ui.state

import org.pureagave.zodiac.control.core.connection.ConnectionPhase
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.CockpitMode
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType

data class CockpitUiState(
    val headingDeg: Int = 42,
    val speedKph: Int = 28,
    val thermalC: Int = 60,
    val mode: CockpitMode = CockpitMode.DIAGNOSTIC,
    val linkStable: Boolean = true,
    val selectedTransport: TransportType = TransportType.BLE,
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionDetail: String? = null,
    val playaMap: PlayaMap? = null,
    val selectedLocationSource: LocationSourceType = LocationSourceType.FAKE,
    val locationState: LocationSourceState = LocationSourceState.Disconnected,
    val mapMode: MapMode = MapMode.TOP,
    val tiltDeg: Int = DEFAULT_TILT_DEG,
    val panEastM: Double = 0.0,
    val panNorthM: Double = 0.0,
) {
    companion object {
        const val DEFAULT_TILT_DEG: Int = 40
        const val MIN_TILT_DEG: Int = 0
        const val MAX_TILT_DEG: Int = 80

        // Hard cap on how far the camera can drift from ego before recenter is
        // required. Keeps a stuck/dragging finger from sliding the city far
        // off-canvas where the RECENTER chip is the only escape and the user
        // might not see it.
        const val MAX_PAN_M: Double = 5_000.0
    }

    val egoFix: GpsFix? get() = (locationState as? LocationSourceState.Active)?.fix
}
