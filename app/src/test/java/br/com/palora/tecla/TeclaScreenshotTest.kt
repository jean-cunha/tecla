/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.ColorUtils
import br.com.palora.tecla.app.HomeActions
import br.com.palora.tecla.app.HomeState
import br.com.palora.tecla.app.TeclaHome
import br.com.palora.tecla.app.TeclaTheme
import br.com.palora.tecla.app.TestState
import br.com.palora.tecla.core.DefaultActions
import br.com.palora.tecla.core.HistoryEntry
import br.com.palora.tecla.core.Source
import br.com.palora.tecla.core.SourceKind
import br.com.palora.tecla.panel.PanelPalette
import br.com.palora.tecla.panel.TeclaAiPanelView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Capturas de tela do painel e da tela do app, para olhar o visual antes de entregar.
 * A CI publica os PNGs na branch "capturas".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h915dp-xxhdpi")
class TeclaScreenshotTest {

    private val dir: File by lazy {
        File(System.getProperty("capturas.dir") ?: "build/capturas").also { it.mkdirs() }
    }

    private val question = "Qual a diferença entre juros simples e juros compostos? Preciso explicar pro meu primo amanhã."
    private val answer = "Nos juros simples, os juros são calculados sempre sobre o valor inicial. Nos compostos, " +
        "eles são calculados sobre o valor inicial mais os juros já acumulados, por isso crescem mais rápido com o tempo.\n\n" +
        "Exemplo: R$ 1.000 a 10% ao mês por 2 meses dá R$ 1.200 nos simples e R$ 1.210 nos compostos."

    // ------------------------------------------------------------ painel do teclado

    @Test
    fun painel_compor_copiado() = panel("painel-compor-copiado") {
        it.showPreview(PanelPalette.DARK, Source(SourceKind.CLIPBOARD, question))
    }

    @Test
    fun painel_compor_selecao_digitando() = panel("painel-compor-selecao-digitando", PanelPalette.LIGHT) {
        it.showPreview(PanelPalette.LIGHT, Source(SourceKind.SELECTION, "oi, vc pode me mandar o relatorio hj? preciso pra reuniao"), prompt = "deixa mais educado")
    }

    @Test
    fun painel_gerando() = panel("painel-gerando") {
        it.showPreview(PanelPalette.DARK, Source(SourceKind.CLIPBOARD, question), result = "", streaming = true)
    }

    @Test
    fun painel_resposta_inserir() = panel("painel-resposta-inserir") {
        it.showPreview(PanelPalette.DARK, Source(SourceKind.CLIPBOARD, question), result = answer)
    }

    @Test
    fun painel_resposta_substituir_claro() = panel("painel-resposta-substituir-claro", PanelPalette.LIGHT) {
        it.showPreview(
            PanelPalette.LIGHT, Source(SourceKind.FIELD, "eu vai chegar atrasado amanha pq o onibus quebro"),
            result = "Vou chegar atrasado amanhã porque o ônibus quebrou.", label = "Corrigir", icon = "check",
        )
    }

    @Test
    fun painel_erro() = panel("painel-erro") {
        it.showPreview(
            PanelPalette.DARK, Source(SourceKind.CLIPBOARD, question),
            error = "A chave da API foi recusada (401). Confira a chave nas configurações.",
        )
    }

    @Test
    fun painel_historico() = panel("painel-historico") {
        val now = System.currentTimeMillis()
        it.setPreviewHistory(
            listOf(
                HistoryEntry(now - 60_000, "Traduzir", SourceKind.CLIPBOARD, "", "Could you send me the report today? I need it for the meeting.", "gpt-6-luna"),
                HistoryEntry(now - 3_600_000, "Responder", SourceKind.CLIPBOARD, "", answer, "gpt-6-luna"),
                HistoryEntry(now - 7_200_000, "Corrigir", SourceKind.FIELD, "", "Vou chegar atrasado amanhã porque o ônibus quebrou.", "gpt-6-luna"),
            )
        )
        it.showPreview(PanelPalette.DARK, Source.NONE, history = true)
    }

    @Test
    fun painel_sem_configurar() = panel("painel-sem-configurar") {
        it.showPreview(PanelPalette.DARK, Source.NONE, notReady = true)
    }

    @Test
    fun painel_tema_verde() {
        // Um tema de teclado qualquer (verde), para ver o painel puxando as cores do tema.
        val green = PanelPalette.fromTheme(
            background = 0xFF10201A.toInt(), text = 0xFFE6F2EC.toInt(), hint = 0xFF8FA99C.toInt(),
            icon = 0xFFCFE3D8.toInt(), themeAccent = 0xFF3DDC97.toInt(), themeOnAccent = 0xFF06261A.toInt(),
        )
        panel("painel-tema-verde", green) { it.showPreview(green, Source(SourceKind.CLIPBOARD, question), result = answer, label = "Responder") }
    }

    private fun panel(name: String, palette: PanelPalette = PanelPalette.DARK, setup: (TeclaAiPanelView) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val panelView = TeclaAiPanelView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(AppMock(activity, palette), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 150)))
            addView(panelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(KeyboardMock(activity, palette), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 236)))
        }
        activity.setContentView(column)
        setup(panelView)
        shadowOf(Looper.getMainLooper()).idle()
        save(column, name)
    }

    // ------------------------------------------------------------ tela do app

    private fun home(ready: Boolean) = HomeState(
        imeEnabled = true,
        imeCurrent = ready,
        providerId = "openai",
        apiKey = if (ready) "sk-proj-abc123def456" else "",
        model = "gpt-6-luna",
        baseUrl = "https://api.openai.com/v1",
        reasoning = "low",
        models = listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6.1-terra"),
        test = if (ready) TestState.Ok(820, "ok") else TestState.Idle,
        actions = DefaultActions.list,
        personal = if (ready) "Me chamo Ana. Escrevo de um jeito informal e sem emoji." else "",
        historyEnabled = true,
        historyCount = if (ready) 12 else 0,
        preferClipboard = true,
        version = "1.0.0",
    )

    @Test
    fun app_configurar_escuro() = screen("app-configurar-escuro", dark = true, state = home(ready = false))

    @Test
    fun app_pronto_escuro() = screen("app-pronto-escuro", dark = true, state = home(ready = true))

    @Test
    fun app_pronto_claro() = screen("app-pronto-claro", dark = false, state = home(ready = true))

    @Config(qualifiers = "w411dp-h2900dp-xxhdpi")
    @Test
    fun app_pagina_inteira() = screen("app-pagina-inteira", dark = true, state = home(ready = true))

    private fun screen(name: String, dark: Boolean, state: HomeState) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent { TeclaTheme(dark = dark) { TeclaHome(state, HomeActions()) } }
        shadowOf(Looper.getMainLooper()).idle()
        save(activity.window.decorView, name)
    }

    // ------------------------------------------------------------ apoio

    private fun save(view: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = view.rootView
        val w = root.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
        if (view === root) {
            val h = root.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
        } else {
            // Só o conteúdo: altura do que foi desenhado, sem sobra de tela embaixo.
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dp(activity: ComponentActivity, v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** Um pedaço de conversa por cima do painel, só para dar contexto na captura. */
    private class AppMock(context: android.content.Context, private val p: PanelPalette) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val dark = p.isDark
            canvas.drawColor(if (dark) 0xFF0B141A.toInt() else 0xFFEFE7DE.toInt())
            paint.color = if (dark) 0xFF1F2C34.toInt() else 0xFFFFFFFF.toInt()
            val r = RectF(14 * d, 18 * d, width * 0.78f, 92 * d)
            canvas.drawRoundRect(r, 14 * d, 14 * d, paint)
            paint.color = if (dark) 0xFFE9EDEF.toInt() else 0xFF111B21.toInt()
            paint.textSize = 14.5f * d
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Qual a diferença entre juros simples", 26 * d, 46 * d, paint)
            canvas.drawText("e juros compostos?", 26 * d, 68 * d, paint)
            paint.color = ColorUtils.setAlphaComponent(paint.color, 120)
            paint.textSize = 11 * d
            canvas.drawText("22:41", r.right - 40 * d, 84 * d, paint)
            // campo de digitação
            paint.color = if (dark) 0xFF1F2C34.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(RectF(10 * d, height - 52 * d, width - 10 * d, height - 8 * d), 22 * d, 22 * d, paint)
            paint.color = if (dark) 0xFF8696A0.toInt() else 0xFF667781.toInt()
            paint.textSize = 15 * d
            canvas.drawText("Mensagem", 28 * d, height - 24 * d, paint)
        }
    }

    /** Teclado desenhado à mão, com as cores da paleta, só para a captura. */
    private class KeyboardMock(context: android.content.Context, private val p: PanelPalette) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            canvas.drawColor(p.background)
            val keyH = 46 * d
            val gap = 5 * d
            val pad = 4 * d
            val keyW = (width - pad * 2 - gap * 9) / 10f
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 21 * d
            rows.forEachIndexed { i, row ->
                val y = pad + i * (keyH + gap * 2)
                val offset = (width - (row.length * keyW + (row.length - 1) * gap)) / 2f
                row.forEachIndexed { j, ch ->
                    val x = offset + j * (keyW + gap)
                    paint.color = p.surface
                    canvas.drawRoundRect(RectF(x, y, x + keyW, y + keyH), 7 * d, 7 * d, paint)
                    paint.color = p.text
                    canvas.drawText(ch.toString(), x + keyW / 2, y + keyH * 0.66f, paint)
                }
            }
            val y = pad + 3 * (keyH + gap * 2)
            paint.color = p.surface
            canvas.drawRoundRect(RectF(width * 0.22f, y, width * 0.74f, y + keyH), 7 * d, 7 * d, paint)
            canvas.drawRoundRect(RectF(pad, y, width * 0.2f, y + keyH), 7 * d, 7 * d, paint)
            paint.color = p.accent
            canvas.drawRoundRect(RectF(width * 0.8f, y, width - pad, y + keyH), 7 * d, 7 * d, paint)
            paint.color = p.text
            paint.textSize = 15 * d
            canvas.drawText("?123", width * 0.11f, y + keyH * 0.64f, paint)
            paint.color = p.muted
            canvas.drawText("Tecla", width * 0.48f, y + keyH * 0.64f, paint)
            paint.color = p.onAccent
            paint.textSize = 22 * d
            canvas.drawText("↵", width * 0.9f, y + keyH * 0.66f, paint)
        }
    }
}
