package app.lumen.photos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.photos.R
import app.lumen.photos.ui.theme.Lumen
import app.lumen.photos.ui.theme.LumenType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

val GlassStyle = HazeBlurStyle {
    blurRadius(26.dp)
    noiseFactor(0.05f)
    colorEffects(listOf(HazeColorEffect.tint(Color(0xFF17161C).copy(alpha = 0.55f))))
    fallbackColorEffect(HazeColorEffect.tint(Color(0xFF1C1C1E).copy(alpha = 0.94f)))
}

/** The iOS "scroll edge" treatment: heavy blur at the very top, melting to nothing. */
val TopEdgeStyle = HazeBlurStyle {
    blurRadius(18.dp)
    colorEffects(listOf(HazeColorEffect.tint(Color.Black.copy(alpha = 0.42f))))
    fallbackColorEffect(HazeColorEffect.tint(Color.Black.copy(alpha = 0.7f)))
    progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
}

val BottomEdgeStyle = HazeBlurStyle {
    blurRadius(18.dp)
    colorEffects(listOf(HazeColorEffect.tint(Color.Black.copy(alpha = 0.42f))))
    fallbackColorEffect(HazeColorEffect.tint(Color.Black.copy(alpha = 0.7f)))
    progressive(HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f))
}

private val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f)))

fun Modifier.glass(haze: HazeState?, shape: Shape): Modifier {
    val base = this.clip(shape)
    val fill = if (haze != null) {
        base.hazeBlur(input = HazeInput.Sources(haze), style = GlassStyle)
    } else {
        base.background(Color(0xF01C1C1E))
    }
    return fill
        .background(Color.White.copy(alpha = 0.05f))
        .border(0.7.dp, rim, shape)
}

fun Modifier.pressable(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick,
)

@Composable
fun GlassCircleButton(
    icon: ImageVector,
    haze: HazeState?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Color.White,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .glass(haze, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun GlassPill(
    text: String,
    haze: HazeState?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(36.dp)
            .then(
                if (highlighted) Modifier.clip(CircleShape).background(Color.White)
                else Modifier.glass(haze, CircleShape),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LumenType.Callout.copy(color = if (highlighted) Color.Black else Color.White))
    }
}

/** The app icon, drawn live: launcher field + foreground mark, cropped to the visible safe zone. */
@Composable
fun LumenMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .border(0.7.dp, rim, RoundedCornerShape(size * 0.27f)),
    ) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize().scale(1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
    }
}

@Composable
fun SunsetButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Lumen.SunsetH)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LumenType.Headline.copy(color = Color(0xFF1A0A06)))
    }
}
