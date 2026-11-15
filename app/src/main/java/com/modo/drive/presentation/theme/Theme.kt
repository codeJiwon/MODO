package com.modo.drive.presentation.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.modo.domain.engine.VehicleDisplayStatus

object ModoColors {
    val Navy = Color(0xFF070D1A)
    val Surface = Color(0xFF0E182B)
    val SurfaceHigh = Color(0xFF15223A)
    val SurfaceHighest = Color(0xFF1C2C4A)
    val Outline = Color(0xFF26365A)
    val Text = Color(0xFFEAF0FF)
    val TextMuted = Color(0xFF93A3C6)
    val TextFaint = Color(0xFF5E6E91)
    val Blue = Color(0xFF3D8BFF)
    val BlueDeep = Color(0xFF1F5FD6)
    val Mint = Color(0xFF3DDBB5)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5C6C)

    fun tone(t: VehicleDisplayStatus.Tone): Color = when (t) {
        VehicleDisplayStatus.Tone.ACTIVE -> Blue
        VehicleDisplayStatus.Tone.SUCCESS -> Mint
        VehicleDisplayStatus.Tone.WARNING -> Amber
        VehicleDisplayStatus.Tone.ERROR -> Red
        VehicleDisplayStatus.Tone.NEUTRAL -> TextMuted
    }
}

private val ModoTypography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = ModoColors.Text),
    displayMedium = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, color = ModoColors.Text),
    headlineLarge = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp, color = ModoColors.Text),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, color = ModoColors.Text),
    titleLarge = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp, color = ModoColors.Text),
    titleMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, color = ModoColors.Text),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, color = ModoColors.Text),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = ModoColors.TextMuted),
    labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = ModoColors.Text),
    labelMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ModoColors.TextMuted),
    labelSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, color = ModoColors.TextFaint),
)

@Composable
fun ModoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ModoColors.Blue,
            onPrimary = Color.White,
            secondary = ModoColors.Mint,
            tertiary = ModoColors.Amber,
            error = ModoColors.Red,
            background = ModoColors.Navy,
            onBackground = ModoColors.Text,
            surface = ModoColors.Surface,
            onSurface = ModoColors.Text,
            surfaceVariant = ModoColors.SurfaceHigh,
            onSurfaceVariant = ModoColors.TextMuted,
            outline = ModoColors.Outline,
        ),
        typography = ModoTypography,
        content = content,
    )
}

/**
 * 기준 캔버스(가로 1280×720dp, 세로 720×1280dp)로 설계한 화면을 실제 창 크기에 맞춰 비례 확대/축소한다.
 *
 * Pleos Connect Emulator v2.1.0 기본 화면은 2560×1440px @160dpi(= 2560×1440dp)이며,
 * 앱 창은 전체 화면, 1:1 분할, portrait 등으로 바뀔 수 있다 (Pleos Design > Window Policy).
 */
@Composable
fun ScaledCanvas(content: @Composable (isWide: Boolean) -> Unit) {
    BoxWithConstraints {
        val w = maxWidth.value
        val h = maxHeight.value
        val landscape = w >= h
        val scale = if (landscape) minOf(w / 1280f, h / 720f) else minOf(w / 720f, h / 1280f)
        val base = LocalDensity.current
        val density = Density(base.density * scale.coerceIn(0.6f, 2.6f), base.fontScale)
        val wideEnough = (w / scale.coerceIn(0.6f, 2.6f)) >= 1000f
        CompositionLocalProvider(LocalDensity provides density) {
            content(landscape && wideEnough)
        }
    }
}
