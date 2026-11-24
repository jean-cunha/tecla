/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.app

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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

/** Cores próprias do Tecla (o app não usa o esquema padrão do Material para ter cara própria). */
@Immutable
data class TeclaColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val spark: Color,
    val success: Color,
    val error: Color,
    val isDark: Boolean,
)

val TeclaDark = TeclaColors(
    background = Color(0xFF0E0E12),
    surface = Color(0xFF17171D),
    surfaceHigh = Color(0xFF22222A),
    outline = Color(0xFF2A2A34),
    text = Color(0xFFF3F2F8),
    muted = Color(0xFF9D9BAB),
    accent = Color(0xFF8B74FF),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF2A2545),
    onAccentSoft = Color(0xFFC9BEFF),
    spark = Color(0xFFFFC24B),
    success = Color(0xFF5BD6A0),
    error = Color(0xFFFF8A80),
    isDark = true,
)

val TeclaLight = TeclaColors(
    background = Color(0xFFF6F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEFEDF6),
    outline = Color(0xFFE4E2EC),
    text = Color(0xFF17161C),
    muted = Color(0xFF6A6876),
    accent = Color(0xFF6A4FF5),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDE8FF),
    onAccentSoft = Color(0xFF4A33C9),
    spark = Color(0xFFE39A12),
    success = Color(0xFF1E9E6A),
    error = Color(0xFFC62828),
    isDark = false,
)

val Grotesk = FontFamily(
    Font(R.font.tecla_grotesk_medium, FontWeight.Medium),
    Font(R.font.tecla_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.tecla_grotesk_bold, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.tecla_inter_regular, FontWeight.Normal),
    Font(R.font.tecla_inter_medium, FontWeight.Medium),
    Font(R.font.tecla_inter_semibold, FontWeight.SemiBold),
)

/** Escala de texto: Space Grotesk nos títulos, Inter no resto. */
object TeclaType {
    val hero = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.03).em)
    val title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
}

val LocalTecla = staticCompositionLocalOf { TeclaDark }

@Composable
fun TeclaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) TeclaDark else TeclaLight
    // O MaterialTheme fica alinhado às mesmas cores, para diálogos e componentes que leem dele.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.text, onBackground = colors.text,
            surfaceVariant = colors.surfaceHigh, onSurfaceVariant = colors.muted, outline = colors.outline,
            error = colors.error, secondaryContainer = colors.accentSoft, onSecondaryContainer = colors.onAccentSoft,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.text, onBackground = colors.text,
            surfaceVariant = colors.surfaceHigh, onSurfaceVariant = colors.muted, outline = colors.outline,
            error = colors.error, secondaryContainer = colors.accentSoft, onSecondaryContainer = colors.onAccentSoft,
        )
    }
    CompositionLocalProvider(LocalTecla provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
