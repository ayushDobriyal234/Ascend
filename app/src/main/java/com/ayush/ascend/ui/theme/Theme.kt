package com.ayush.ascend.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ayush.ascend.R

// Palette sampled from the logo
object Palette {
    val Forest900 = Color(0xFF062A1E)
    val Forest800 = Color(0xFF0A3626)
    val Forest700 = Color(0xFF0D4A33)
    val Forest500 = Color(0xFF2F6E4D)
    val Sage = Color(0xFF6E9878)
    val Sun = Color(0xFFF2AE24)
    val SunSoft = Color(0xFFF6CB6B)
    val Cream = Color(0xFFFBF0D2)
    val Sand = Color(0xFFF3DFAE)
    val Paper = Color(0xFFFFF8E8)
    val Ink = Color(0xFF10221A)
    val Ember = Color(0xFFC4552D)
}

val Montserrat = FontFamily(
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
)

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
)

private val AscendType = Typography(
    displayLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

private val Light = lightColorScheme(
    primary = Palette.Forest700, onPrimary = Palette.Cream,
    primaryContainer = Palette.Sand, onPrimaryContainer = Palette.Forest900,
    secondary = Palette.Sun, onSecondary = Palette.Forest900,
    secondaryContainer = Palette.SunSoft, onSecondaryContainer = Palette.Forest900,
    tertiary = Palette.Forest500,
    // background = the icon's off-white (sky/trail cream); cards sit a shade lighter on top
    background = Palette.Cream, onBackground = Palette.Ink,
    surface = Palette.Cream, onSurface = Palette.Ink,
    surfaceVariant = Palette.Paper, onSurfaceVariant = Color(0xFF4A5A50),
    surfaceContainer = Palette.Paper, surfaceContainerHigh = Palette.Sand,
    outline = Color(0xFFD9C79A), outlineVariant = Color(0xFFEADBB2),
    error = Palette.Ember,
)

private val Dark = darkColorScheme(
    primary = Palette.Sun, onPrimary = Palette.Forest900,
    primaryContainer = Palette.Forest700, onPrimaryContainer = Palette.Cream,
    secondary = Palette.SunSoft, onSecondary = Palette.Forest900,
    secondaryContainer = Palette.Forest700, onSecondaryContainer = Palette.Cream,
    tertiary = Palette.Sage,
    background = Palette.Forest900, onBackground = Palette.Cream,
    surface = Palette.Forest900, onSurface = Palette.Cream,
    surfaceVariant = Palette.Forest800, onSurfaceVariant = Color(0xFFC9D6C4),
    surfaceContainer = Palette.Forest800, surfaceContainerHigh = Palette.Forest700,
    outline = Color(0xFF2E5A44), outlineVariant = Color(0xFF1C4433),
    error = Color(0xFFE88A64),
)

@Composable
fun AscendTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AscendType, content = content)
}
