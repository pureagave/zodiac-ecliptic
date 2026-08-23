package org.pureagave.zodiac.control.ui.concepts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared chip styles for the cockpit's chrome — [themedChip] (selectable) and
 * [actionChip] (a one-shot action) — used by `bayMapTile`'s TOP/TILT toggle,
 * `driveToBar`, and `ui/debug/DevControlPanel.kt`'s bench controls (formerly
 * `conceptControlStrip`, which lived here before COCKPIT-TLM Phase A moved
 * every manual/bench control behind the hidden dev panel).
 */
@Composable
fun themedChip(
    label: String,
    selected: Boolean,
    theme: ConceptTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, if (selected) theme.secondary else theme.primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = if (selected) theme.secondary else theme.primary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
fun actionChip(
    label: String,
    theme: ConceptTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, theme.secondary)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = theme.secondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}
