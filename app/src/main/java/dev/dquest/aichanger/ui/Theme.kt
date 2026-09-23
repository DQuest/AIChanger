package dev.dquest.aichanger.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dquest.aichanger.R

/** Схема, если системных цветов (Android 12+) нет: в тон фиолетово-синему центру иконки. */
private val LightFallback = lightColorScheme(primary = Color(0xFF4A5BD6), secondary = Color(0xFF6B5CC4))
private val DarkFallback = darkColorScheme(primary = Color(0xFFBAC3FF), secondary = Color(0xFFCDBEFF))

/** Статуса «всё хорошо» в Material нет, поэтому зелёные цвета свои. */
@Immutable
data class StatusColors(val successContainer: Color, val onSuccessContainer: Color)

private val LightStatus = StatusColors(Color(0xFFD5F2DC), Color(0xFF0B3D1A))
private val DarkStatus = StatusColors(Color(0xFF1F4A2B), Color(0xFFB8EFC5))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun AIChangerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Иконка приложения внутри экрана: painterResource не умеет адаптивные иконки, поэтому берём видимую часть рисунка. */
@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.ic_app_art),
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)),
    )
}

val ScreenPadding = 20.dp
