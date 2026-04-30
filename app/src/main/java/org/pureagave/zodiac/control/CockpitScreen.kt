package org.pureagave.zodiac.control

import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.ui.concepts.instrumentBayScreen
import org.pureagave.zodiac.control.ui.concepts.motionTrackerScreen
import org.pureagave.zodiac.control.ui.concepts.perspectiveGridScreen
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Top-level dispatcher: reads the current [CockpitConcept] and renders the
 * matching screen. The cycle callback is the same for every concept — it
 * advances to the next in [CockpitConcept.next] order — so every concept's
 * selector pill shares one wiring path.
 */
@Composable
fun cockpitScreen(viewModel: CockpitViewModel) {
    val concept = viewModel.uiState.collectAsStateWithLifecycle().value.concept
    val cycle: () -> Unit = viewModel::cycleConcept

    when (concept) {
        CockpitConcept.A -> crtVectorScreen(viewModel = viewModel, onCycleConcept = cycle)
        CockpitConcept.B -> perspectiveGridScreen(viewModel = viewModel, onCycleConcept = cycle)
        CockpitConcept.C -> motionTrackerScreen(viewModel = viewModel, onCycleConcept = cycle)
        CockpitConcept.D -> instrumentBayScreen(viewModel = viewModel, onCycleConcept = cycle)
    }
}
