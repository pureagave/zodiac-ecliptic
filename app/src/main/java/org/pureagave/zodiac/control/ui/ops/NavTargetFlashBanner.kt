package org.pureagave.zodiac.control.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pureagave.zodiac.control.core.ops.NavTargetFlash
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme

private val Backing = Color(0xCC000000)

/**
 * Big top-centre flash of a `$ZNAV` target the fleet just adopted. Purely
 * decorative overlay — no pointer modifiers, so map pan/zoom under it still
 * work. Shown while `CockpitUiState.navTargetFlash` is non-null (the
 * ViewModel clears it on a timer). Clone of [streetCrossingPopup]: same
 * backing, border and padding, `theme.primary` label / `theme.accent` value —
 * never `theme.error`, which the colour system reserves for faults.
 */
@Composable
fun navTargetFlashBanner(
    theme: ConceptTheme,
    flash: NavTargetFlash,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .padding(top = 88.dp)
                    .background(Backing)
                    .border(2.dp, theme.primary)
                    .padding(horizontal = 30.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = flash.label,
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 4.sp,
            )
            Text(
                text = flash.value,
                color = theme.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 44.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
