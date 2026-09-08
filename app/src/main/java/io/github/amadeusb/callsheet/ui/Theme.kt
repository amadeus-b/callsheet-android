package io.github.amadeusb.callsheet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Calm teal as the lead colour, a strong green for the dial button, a muted
// red for blocking and overdue items. Deliberately NO dynamic colour scheme
// from the system: the app has to stay equally readable in sunlight.

private val Teal = Color(0xFF00504F)
private val TealLight = Color(0xFF6FF7F3)
private val TealContainerLight = Color(0xFFB9F2EF)
private val TealContainerDark = Color(0xFF003938)

private val Sand = Color(0xFFFDFCF7)
private val Ink = Color(0xFF151C1C)

/** Green of the dial button — the main handle, the same colour everywhere. */
val DialLight = Color(0xFF1B6B31)
val DialDark = Color(0xFF7EDC96)

/** Warning colour for overdue items and for the do-not-call button. */
val WarningLight = Color(0xFF8C1D18)
val WarningDark = Color(0xFFFFB4AB)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4A6362),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E6),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    error = WarningLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    background = Sand,
    onBackground = Ink,
    surface = Sand,
    onSurface = Ink,
    surfaceVariant = Color(0xFFDBE5E3),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7978),
    outlineVariant = Color(0xFFBFC9C7),
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003736),
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealContainerLight,
    secondary = Color(0xFFB0CCCA),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4A),
    onSecondaryContainer = Color(0xFFCCE8E6),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    error = WarningDark,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBFC9C7),
    outline = Color(0xFF899392),
    outlineVariant = Color(0xFF3F4948),
)

/** A little larger than the Material default — this gets read standing up, at arm's length. */
private val LargeType = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 38.sp, lineHeight = 46.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 38.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 26.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 18.sp,
    ),
)

@Composable
fun CallsheetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = LargeType,
        content = content,
    )
}

/** Colour of the dial button, matching the active scheme. */
val dialColor: Color
    @Composable get() = if (isSystemInDarkTheme()) DialDark else DialLight

/** Text colour on the dial button. */
val onDialColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF00391A) else Color.White
