package kr.neptune.pocketoffice.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kr.neptune.pocketoffice.core.DocKind

private val Brand = Color(0xFF3B5BDB)

private val Light = lightColorScheme(
    primary = Brand,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BB0FF),
    background = Color(0xFF121316),
    surface = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF22242A),
)

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // 안드로이드 12 이상이면 배경화면 색을 따른다
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 형식별 색. 한눈에 구분되도록 오피스에서 흔히 쓰는 계열을 따랐다 */
fun DocKind.color(): Color = when (this) {
    DocKind.WORD -> Color(0xFF2F6BD8)
    DocKind.SHEET -> Color(0xFF1E8E4E)
    DocKind.SLIDE -> Color(0xFFE0622A)
    DocKind.PDF -> Color(0xFFD93A3A)
}
