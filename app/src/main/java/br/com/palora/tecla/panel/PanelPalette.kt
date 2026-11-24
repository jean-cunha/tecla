/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.panel

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Cores do painel. Saem do tema do teclado (para o painel parecer parte dele) e as superfícies
 * são misturas do texto sobre o fundo, o que funciona em qualquer tema, claro ou escuro.
 */
data class PanelPalette(
    @ColorInt val background: Int,
    @ColorInt val text: Int,
    @ColorInt val muted: Int,
    @ColorInt val icon: Int,
    @ColorInt val accent: Int,
    @ColorInt val onAccent: Int,
) {
    val isDark: Boolean get() = ColorUtils.calculateLuminance(opaque(background)) < 0.4

    /** Cartões e campos. */
    @ColorInt val surface: Int = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(text, if (isDarkBg(background)) 22 else 14), opaque(background))

    /** Botões secundários e chips pressionados. */
    @ColorInt val surfaceHigh: Int = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(text, if (isDarkBg(background)) 38 else 26), opaque(background))

    @ColorInt val outline: Int = ColorUtils.setAlphaComponent(text, 40)

    @ColorInt val ripple: Int = ColorUtils.setAlphaComponent(text, 40)

    @ColorInt val error: Int = if (isDarkBg(background)) Color.parseColor("#FF8A80") else Color.parseColor("#C62828")

    /** Tom suave do destaque, para o fundo do chip de origem e do selo do modelo. */
    @ColorInt val accentSoft: Int = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, if (isDarkBg(background)) 60 else 40), opaque(background))

    @ColorInt val onAccentSoft: Int = if (isDarkBg(background)) ColorUtils.blendARGB(accent, Color.WHITE, 0.55f) else ColorUtils.blendARGB(accent, Color.BLACK, 0.45f)

    companion object {
        /** Ponto de partida da marca: índigo elétrico com faísca âmbar. */
        @ColorInt val BRAND = Color.parseColor("#7B61FF")
        @ColorInt val SPARK = Color.parseColor("#FFC24B")

        val DARK = PanelPalette(
            background = Color.parseColor("#1B1B1F"),
            text = Color.parseColor("#ECEBF2"),
            muted = Color.parseColor("#9C9AA8"),
            icon = Color.parseColor("#CFCDD8"),
            accent = BRAND,
            onAccent = Color.WHITE,
        )
        val LIGHT = PanelPalette(
            background = Color.parseColor("#F1F0F5"),
            text = Color.parseColor("#1C1B21"),
            muted = Color.parseColor("#6B6977"),
            icon = Color.parseColor("#45434F"),
            accent = Color.parseColor("#6A4FF5"),
            onAccent = Color.WHITE,
        )

        private fun opaque(@ColorInt c: Int): Int =
            if (Color.alpha(c) == 255) c else ColorUtils.compositeColors(c, Color.BLACK)

        private fun isDarkBg(@ColorInt c: Int) = ColorUtils.calculateLuminance(opaque(c)) < 0.4

        /**
         * Monta a paleta a partir das cores do tema do teclado. Se a cor de destaque do tema quase
         * some no fundo, usa o índigo da marca.
         */
        fun fromTheme(
            @ColorInt background: Int,
            @ColorInt text: Int,
            @ColorInt hint: Int,
            @ColorInt icon: Int,
            @ColorInt themeAccent: Int,
            @ColorInt themeOnAccent: Int,
        ): PanelPalette {
            val bg = opaque(background)
            val accentOk = ColorUtils.calculateContrast(opaque(themeAccent), bg) >= 1.6 && Color.alpha(themeAccent) > 200
            val accent = if (accentOk) opaque(themeAccent) else BRAND
            val onAccent = if (accentOk && ColorUtils.calculateContrast(opaque(themeOnAccent), accent) >= 3.0) opaque(themeOnAccent)
                else if (ColorUtils.calculateLuminance(accent) > 0.5) Color.parseColor("#141218") else Color.WHITE
            val muted = if (Color.alpha(hint) > 120 && ColorUtils.calculateContrast(opaque(hint), bg) >= 2.5) hint
                else ColorUtils.blendARGB(text, bg, 0.38f)
            return PanelPalette(
                background = background,
                text = text,
                muted = muted,
                icon = icon,
                accent = accent,
                onAccent = onAccent,
            )
        }
    }
}
