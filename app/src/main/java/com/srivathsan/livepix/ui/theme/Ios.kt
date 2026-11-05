package com.srivathsan.livepix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** iOS system colors (UIKit semantic colors), light and dark variants. */
@Immutable
data class IosColors(
    val background: Color,
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val blue: Color,
    val yellow: Color,
    val red: Color,
    val glass: Color,
    val glassBorder: Color,
    val isDark: Boolean,
)

val LightIos = IosColors(
    background = Color.White,
    groupedBackground = Color(0xFFF2F2F7),
    secondaryGroupedBackground = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F787880),
    blue = Color(0xFF007AFF),
    yellow = Color(0xFFFFCC00),
    red = Color(0xFFFF3B30),
    glass = Color(0xD9F9F9F9),
    glassBorder = Color(0x33FFFFFF),
    isDark = false,
)

val DarkIos = IosColors(
    background = Color.Black,
    groupedBackground = Color.Black,
    secondaryGroupedBackground = Color(0xFF1C1C1E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x3D787880),
    blue = Color(0xFF0A84FF),
    yellow = Color(0xFFFFD60A),
    red = Color(0xFFFF453A),
    glass = Color(0xCC2C2C2E),
    glassBorder = Color(0x1FFFFFFF),
    isDark = true,
)

/** iOS Dynamic Type sizes at the default "Large" setting. */
object IosType {
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.011.em, lineHeight = 41.sp)
    val title1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.013.em)
    val title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.016).em)
    val title3 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.022).em)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.026).em)
    val body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.026).em)
    val callout = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em)
    val subheadline = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.016).em)
    val footnote = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.006).em)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val caption2 = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em)
}

val LocalIos = staticCompositionLocalOf { LightIos }

object Ios {
    val colors: IosColors @Composable get() = LocalIos.current
}

@Composable
fun LivePixTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ios = if (dark) DarkIos else LightIos
    val material = if (dark) darkColorScheme(primary = ios.blue, background = ios.background, surface = ios.secondaryGroupedBackground)
    else lightColorScheme(primary = ios.blue, background = ios.background, surface = ios.secondaryGroupedBackground)
    CompositionLocalProvider(LocalIos provides ios) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
