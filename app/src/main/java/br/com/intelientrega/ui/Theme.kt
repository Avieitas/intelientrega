package br.com.intelientrega.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.intelientrega.R
import br.com.intelientrega.domain.Signal

/** Paleta da marca: grafite de asfalto + amarelo de sinalização. Semáforo separado da marca. */
object Brand {
    val Bg = Color(0xFF0D0F12)
    val Surface = Color(0xFF16191E)
    val SurfaceHigh = Color(0xFF1F232A)
    val Outline = Color(0xFF2C313A)
    val Text = Color(0xFFF4F5F7)
    val Muted = Color(0xFF9AA3AE)
    val Yellow = Color(0xFFFFC91F)
    val OnYellow = Color(0xFF17130A)

    val Good = Color(0xFF22C55E)
    val Mid = Color(0xFFFF9F0A)
    val Bad = Color(0xFFFF453A)
    val Neutral = Color(0xFF8B95A1)
}

fun Signal.color(): Color = when (this) {
    Signal.BOM -> Brand.Good
    Signal.MEDIO -> Brand.Mid
    Signal.RUIM -> Brand.Bad
    Signal.NEUTRO -> Brand.Neutral
}

val Sora = FontFamily(
    Font(R.font.sora_regular, FontWeight.Normal),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
    Font(R.font.sora_extrabold, FontWeight.ExtraBold),
)

private val Colors = darkColorScheme(
    primary = Brand.Yellow, onPrimary = Brand.OnYellow,
    primaryContainer = Color(0xFF3A2F05), onPrimaryContainer = Brand.Yellow,
    secondary = Brand.Good, onSecondary = Color.Black,
    tertiary = Brand.Mid, onTertiary = Color.Black,
    background = Brand.Bg, onBackground = Brand.Text,
    surface = Brand.Surface, onSurface = Brand.Text,
    surfaceVariant = Brand.SurfaceHigh, onSurfaceVariant = Brand.Muted,
    surfaceContainer = Brand.Surface, surfaceContainerHigh = Brand.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF262B33), surfaceContainerLow = Color(0xFF121418),
    outline = Brand.Outline, outlineVariant = Color(0xFF22262D),
    error = Brand.Bad, onError = Color.White,
    secondaryContainer = Color(0xFF2A2F37), onSecondaryContainer = Brand.Text,
)

private val base = Typography()
private val Type = Typography(
    displaySmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
    bodyLarge = base.bodyLarge, bodyMedium = base.bodyMedium, bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = AppShapes, content = content)
}
