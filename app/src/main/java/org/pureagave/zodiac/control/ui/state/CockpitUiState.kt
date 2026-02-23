package org.pureagave.zodiac.control.ui.state

import org.pureagave.zodiac.control.core.model.CockpitMode

data class CockpitUiState(
    val headingDeg: Int = 42,
    val speedKph: Int = 28,
    val thermalC: Int = 60,
    val mode: CockpitMode = CockpitMode.DIAGNOSTIC,
    val linkStable: Boolean = true,
)
