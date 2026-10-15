package app.lumen.photos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lumen.photos.R

object Lumen {
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF0B0A12)
    val Raised = Color(0xFF1C1C1E)
    val Raised2 = Color(0xFF2C2C2E)
    val Hairline = Color(0x26FFFFFF)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFF98989F)
    val TextFaint = Color(0xFF636366)

    val Gold = Color(0xFFFFD073)
    val Ember = Color(0xFFFF7A45)
    val Rose = Color(0xFFFF3D7F)
    val Accent = Color(0xFFFF8A4C)
    val Danger = Color(0xFFFF453A)

    val Sunset = Brush.linearGradient(listOf(Gold, Ember, Rose))
    val SunsetH = Brush.horizontalGradient(listOf(Gold, Ember, Rose))
}

private fun inter(weight: Int) = Font(
    resId = R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(
    inter(400),
    inter(500),
    inter(600),
    inter(700),
    inter(800),
)

object LumenType {
    val Display = TextStyle(fontFamily = Inter, fontWeight = FontWeight(800), fontSize = 44.sp, letterSpacing = (-0.035).em, color = Lumen.Text)
    val LargeTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.025).em, color = Lumen.Text)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.02).em, color = Lumen.Text)
    val Headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.01).em, color = Lumen.Text)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, color = Lumen.Text)
    val Callout = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Lumen.Text)
    val Subhead = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = Lumen.TextDim)
    val Footnote = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Lumen.TextDim)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.01.em, color = Lumen.Text)
}

private val scheme = darkColorScheme(
    primary = Lumen.Accent,
    onPrimary = Color.Black,
    background = Lumen.Black,
    onBackground = Lumen.Text,
    surface = Lumen.Raised,
    onSurface = Lumen.Text,
    surfaceContainer = Lumen.Raised,
    surfaceContainerHigh = Lumen.Raised2,
    onSurfaceVariant = Lumen.TextDim,
    outline = Lumen.Hairline,
    error = Lumen.Danger,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
            bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
            labelLarge = base.labelLarge.copy(fontFamily = Inter),
            titleMedium = base.titleMedium.copy(fontFamily = Inter),
        ),
        content = content,
    )
}
