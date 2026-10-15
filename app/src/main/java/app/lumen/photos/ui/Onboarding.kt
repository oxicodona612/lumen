package app.lumen.photos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType

@Composable
fun Onboarding(onContinue: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(
                Brush.radialGradient(
                    listOf(Lumen.Ember.copy(alpha = 0.22f), Lumen.Rose.copy(alpha = 0.06f), Color.Transparent),
                    radius = 1100f,
                ),
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 36.dp)
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1f - rise.value) * 60f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LumenMark(112.dp)
            Spacer(Modifier.height(28.dp))
            Text("Lumen", style = LumenType.Display)
            Spacer(Modifier.height(8.dp))
            Text(
                "Every photo, beautifully kept.",
                style = LumenType.Body.copy(color = Lumen.TextDim),
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .graphicsLayer { alpha = rise.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SunsetButton("Open My Library", Modifier.fillMaxWidth(), onClick = onContinue)
            Spacer(Modifier.height(14.dp))
            Text(
                "Lumen works entirely on this device. Nothing is uploaded, ever.",
                style = LumenType.Footnote.copy(color = Lumen.TextFaint),
                textAlign = TextAlign.Center,
            )
        }
    }
}
