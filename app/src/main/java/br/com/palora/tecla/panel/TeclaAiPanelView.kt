/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.panel

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import br.com.palora.tecla.core.ChatClient
import br.com.palora.tecla.core.ChatEvent
import br.com.palora.tecla.core.Exchange
import br.com.palora.tecla.core.HistoryEntry
import br.com.palora.tecla.core.Prompts
import br.com.palora.tecla.core.Source
import br.com.palora.tecla.core.SourceKind
import br.com.palora.tecla.core.TeclaAction
import br.com.palora.tecla.core.TeclaException
import br.com.palora.tecla.core.TeclaHistory
import br.com.palora.tecla.core.TeclaSession
import br.com.palora.tecla.core.TeclaSettings
import br.com.palora.tecla.core.Turn
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/**
 * Painel de IA que aparece no lugar da barra de sugestões, com o teclado normal embaixo.
 * As teclas digitadas enquanto ele está aberto vêm por [onHostKey] / [onHostText] e vão para o
 * campo de pedido do próprio painel (o mesmo esquema da busca de GIFs do LeanType).
 */
class TeclaAiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** O que o painel precisa do teclado. */
    interface Host {
        fun inputConnection(): InputConnection?
        fun editorInfo(): EditorInfo?
        /** Muda sempre que o teclado passa para outro campo de texto. */
        fun session(): Long
        fun palette(): PanelPalette?
        fun closePanel()
        fun openApp()
    }

    var host: Host? = null

    private enum class Mode { COMPOSE, RESULT, HISTORY, SESSION }

    private val settings by lazy { TeclaSettings(context) }
    private val historyStore by lazy { TeclaHistory(context) }
    private val session by lazy { TeclaSession(context) { settings.sessionTimeout } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private var palette = PanelPalette.DARK
    private var mode = Mode.COMPOSE
    var isOpen = false
        private set

    // Origem do texto
    private var sources: List<Source> = listOf(Source.NONE)
    private var source: Source = Source.NONE

    // Pedido em andamento ou concluído
    private var runLabel = ""
    private var runIcon = "spark"
    private var runInstruction = ""
    private val turns = mutableListOf<Turn>()
    private var resultText = ""
    private var running = false
    private var thinking = false
    private var stopped = false
    private var error: TeclaException? = null
    private var startedAt = 0L
    private var elapsedMs = 0L
    private var runSession = -1L
    private var runModel = ""
    private var fromHistory = false

    // Sessão de contexto: as trocas anteriores que vão junto neste pedido (fotografadas no início,
    // para "Gerar de novo" e os ajustes não mandarem a própria troca como contexto).
    private var sessionHistory: List<Exchange> = emptyList()
    private var exchangeRecorded = false
    private var previewSession: Int? = null
    private var previewSessionName: String? = null

    // Arrastar da tecla apagar
    private var deleteDragStart = -1
    private var deleteDragPos = -1

    // Views que mudam durante o streaming (o resto é refeito em render())
    private val field = CaretField(context)
    private var resultView: TextView? = null
    private var statusView: TextView? = null
    private var dotsView: DotsView? = null
    private var resultScroll: ScrollView? = null
    private var sendButton: FrameLayout? = null
    private var sendIcon: ImageView? = null

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init {
        orientation = VERTICAL
        // As fileiras de chips usam margem negativa para ir até a borda da tela.
        clipToPadding = false
        clipChildren = false
        field.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            maxLines = 3
            isVerticalScrollBarEnabled = false
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // Recuo para o cursor caber antes da dica quando o campo está vazio.
            setPadding(dp(6), 0, 0, 0)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    fun open() {
        isOpen = true
        mode = Mode.COMPOSE
        stopJob()
        resetRun()
        field.setValue("")
        host?.palette()?.let { palette = it }
        loadSources()
        render()
        field.blinking = true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        stopJob()
        field.blinking = false
        deleteDragStart = -1
        deleteDragPos = -1
    }

    override fun onDetachedFromWindow() {
        close()
        scope.coroutineContext.cancelChildren()
        super.onDetachedFromWindow()
    }

    /** Só para as capturas de tela da CI: monta um estado sem rede nem campo de texto. */
    fun showPreview(
        palette: PanelPalette,
        source: Source,
        prompt: String = "",
        result: String? = null,
        label: String = "Responder",
        icon: String = "answer",
        streaming: Boolean = false,
        error: String? = null,
        history: Boolean = false,
        notReady: Boolean = false,
        sessionCount: Int? = null,
        usedContext: Boolean = false,
        sessionName: String? = null,
        sessionCard: Boolean = false,
    ) {
        this.palette = palette
        isOpen = true
        previewNotReady = notReady
        previewSession = sessionCount
        previewSessionName = sessionName
        sessionHistory = if (usedContext) listOf(Exchange(0, "", "", "")) else emptyList()
        sources = listOf(source, Source.NONE).distinctBy { it.kind }
        this.source = source
        field.setValue(prompt)
        field.caretColor = palette.accent
        runLabel = label
        runIcon = icon
        resultText = result.orEmpty()
        running = streaming
        this.error = error?.let { TeclaException(it, TeclaException.Kind.KEY) }
        elapsedMs = 1400
        runModel = "gpt-6-luna"
        mode = when {
            sessionCard -> Mode.SESSION
            history -> Mode.HISTORY
            result != null || error != null || streaming -> Mode.RESULT
            else -> Mode.COMPOSE
        }
        render()
        field.blinking = true
    }

    private var previewNotReady: Boolean? = null
    private val ready: Boolean get() = previewNotReady?.not() ?: settings.isReady

    private fun resetRun() {
        turns.clear()
        resultText = ""
        running = false
        thinking = false
        stopped = false
        error = null
        fromHistory = false
        runLabel = ""
        runInstruction = ""
        sessionHistory = emptyList()
        exchangeRecorded = false
    }

    // ---------------------------------------------------------------- origem do texto

    private fun loadSources() {
        val ic = host?.inputConnection()
        val selected = runCatching { ic?.getSelectedText(0)?.toString() }.getOrNull().orEmpty()
        val before = runCatching { ic?.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.toString() }.getOrNull().orEmpty()
        val after = runCatching { ic?.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.toString() }.getOrNull().orEmpty()
        val fieldText = before + selected + after
        val clip = readClipboard()

        val list = mutableListOf<Source>()
        if (selected.isNotBlank()) list += Source(SourceKind.SELECTION, selected)
        if (clip != null && clip.first.isNotBlank()) list += Source(SourceKind.CLIPBOARD, clip.first)
        if (fieldText.isNotBlank() && fieldText.trim() != selected.trim()) list += Source(SourceKind.FIELD, fieldText)
        list += Source.NONE
        sources = list

        val clipRecent = clip != null && clip.second > 0 && System.currentTimeMillis() - clip.second < RECENT_CLIP_MS
        source = when {
            selected.isNotBlank() -> list.first { it.kind == SourceKind.SELECTION }
            clip != null && clip.first.isNotBlank() && settings.preferClipboard && (fieldText.isBlank() || clipRecent) ->
                list.first { it.kind == SourceKind.CLIPBOARD }
            fieldText.isNotBlank() -> list.first { it.kind == SourceKind.FIELD }
            clip != null && clip.first.isNotBlank() -> list.first { it.kind == SourceKind.CLIPBOARD }
            else -> Source.NONE
        }
    }

    /** Texto copiado e quando foi copiado (0 se o Android não informa). */
    private fun readClipboard(): Pair<String, Long>? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) null else {
            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clip.description.timestamp else 0L
            text to time
        }
    } catch (e: Exception) {
        null
    }

    private fun cycleSource() {
        if (sources.size <= 1) return
        val i = sources.indexOfFirst { it.kind == source.kind }
        source = sources[(i + 1) % sources.size]
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        render()
    }

    // ---------------------------------------------------------------- entrada vinda do teclado

    fun onHostKey(code: Int): Boolean {
        if (!isOpen || mode == Mode.HISTORY) return isOpen
        when {
            code == KeyCode.DELETE || code == -5 -> {
                deleteDragStart = -1
                field.deleteBack()
                updateSendButton()
                return true
            }
            code == Constants.CODE_ENTER || code == '\n'.code -> {
                submitField()
                return true
            }
            code == KeyCode.ARROW_LEFT -> return field.moveCaret(-1)
            code == KeyCode.ARROW_RIGHT -> return field.moveCaret(1)
            code >= 32 -> {
                if (field.value.length < maxFieldChars()) field.insert(String(Character.toChars(code)))
                updateSendButton()
                return true
            }
            else -> return false
        }
    }

    fun onHostText(text: String): Boolean {
        if (!isOpen) return false
        if (mode == Mode.HISTORY) return true
        val room = maxFieldChars() - field.value.length
        if (room > 0) field.insert(text.take(room))
        updateSendButton()
        return true
    }

    fun onMoveCursor(steps: Int): Boolean = isOpen && field.moveCaret(steps)

    fun onMoveDeletePointer(stepsX: Int, stepsY: Int) {
        if (field.isEmpty()) return
        if (deleteDragStart == -1) {
            deleteDragStart = field.caret
            deleteDragPos = field.caret
        }
        deleteDragPos = when {
            stepsY < 0 -> 0
            stepsY > 0 -> deleteDragStart
            else -> field.offsetFrom(deleteDragPos, stepsX)
        }
        field.markForDeletion(minOf(deleteDragStart, deleteDragPos), maxOf(deleteDragStart, deleteDragPos))
    }

    fun onUpWithDeletePointerActive() {
        if (deleteDragStart != -1) field.commitMarkedDeletion()
        deleteDragStart = -1
        deleteDragPos = -1
        updateSendButton()
    }

    private fun maxFieldChars() = if (mode == Mode.SESSION) TeclaSession.MAX_NAME else MAX_PROMPT_CHARS

    private fun submitField() {
        val text = field.value.trim()
        if (mode == Mode.SESSION) {
            if (previewSession == null) session.rename(text)
            mode = Mode.COMPOSE
            field.setValue("")
            render()
            return
        }
        if (running) return
        if (text.isEmpty()) return
        if (mode == Mode.RESULT && resultText.isNotBlank() && error == null) {
            refine(text)
        } else {
            startRun(text.take(48), "spark", text)
        }
    }

    // ---------------------------------------------------------------- pedidos

    private fun startRun(label: String, icon: String, instruction: String) {
        if (!ready) {
            host?.openApp()
            return
        }
        turns.clear()
        runLabel = label
        runIcon = icon
        runInstruction = instruction
        fromHistory = false
        exchangeRecorded = false
        sessionHistory = if (session.isActive()) TeclaSession.fit(session.exchanges()) else emptyList()
        request()
    }

    private fun refine(followUp: String) {
        if (resultText.isBlank() || running) return
        turns += Turn(resultText, followUp)
        request()
    }

    private fun request() {
        stopJob()
        mode = Mode.RESULT
        running = true
        thinking = false
        stopped = false
        error = null
        resultText = ""
        startedAt = SystemClock.elapsedRealtime()
        runSession = host?.session() ?: -1L
        field.setValue("")
        render()

        val cfg = settings.snapshot()
        runModel = cfg.model
        val messages = Prompts.build(settings.personal, runInstruction, source, turns, sessionHistory)
        val buffer = StringBuilder()
        job = scope.launch {
            try {
                ChatClient.stream(cfg, messages).collect { ev ->
                    when (ev) {
                        is ChatEvent.Thinking -> {
                            thinking = true
                            updateStatus()
                        }
                        is ChatEvent.Delta -> {
                            thinking = false
                            buffer.append(ev.text)
                            resultText = buffer.toString()
                            updateStreaming()
                        }
                        is ChatEvent.Done -> Unit
                    }
                }
                resultText = Prompts.clean(buffer.toString())
                if (resultText.isBlank()) {
                    error = TeclaException("A IA não devolveu texto. Tente de novo ou escolha outro modelo.")
                } else {
                    saveHistory()
                    recordExchange()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TeclaException) {
                error = e
            } catch (e: Exception) {
                error = TeclaException(e.message ?: "Erro inesperado.")
            }
            running = false
            thinking = false
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            render()
        }
    }

    /** Botão de parar: o que já chegou fica na tela e pode ser usado. */
    private fun stopRun() {
        if (!running) return
        stopJob()
        running = false
        thinking = false
        elapsedMs = SystemClock.elapsedRealtime() - startedAt
        resultText = Prompts.clean(resultText)
        if (resultText.isBlank()) {
            mode = Mode.COMPOSE
            if (turns.isNotEmpty()) {
                resultText = turns.removeAt(turns.lastIndex).answer
                mode = Mode.RESULT
            }
        } else {
            stopped = true
        }
        render()
    }

    private fun stopJob() {
        job?.cancel()
        job = null
    }

    /** Guarda a troca na sessão (se houver uma ligada). Ajustes e "Gerar de novo" só trocam a resposta. */
    private fun recordExchange() {
        if (fromHistory || previewSession != null) return
        runCatching {
            if (!session.isActive()) return
            if (!exchangeRecorded) {
                session.add(Exchange(System.currentTimeMillis(), runLabel, Prompts.userForHistory(runInstruction, source), resultText))
                exchangeRecorded = true
            } else {
                session.replaceLastAnswer(resultText)
            }
        }
    }

    /** -1 = sem sessão; senão, quantas trocas ela já tem. */
    private fun sessionCount(): Int = previewSession ?: runCatching { if (session.isActive()) session.count() else -1 }.getOrDefault(-1)

    private fun startSession() {
        if (previewSession != null) return
        session.start()
        toast("Sessão iniciada: os próximos pedidos lembram do que já foi falado.")
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        render()
    }

    private fun endSession() {
        if (previewSession == null) session.end()
        sessionHistory = emptyList()
        mode = Mode.COMPOSE
        field.setValue("")
        toast("Sessão encerrada. Os próximos pedidos começam do zero.")
        render()
    }

    /** Toque no selo: abre o cartão da sessão, com o nome no campo (o teclado de baixo edita) e o botão de encerrar. */
    private fun openSessionCard() {
        stopJob()
        running = false
        mode = Mode.SESSION
        field.setValue(sessionName())
        render()
    }

    private fun sessionName(): String = previewSessionName ?: runCatching { session.name() }.getOrDefault(TeclaSession.DEFAULT_NAME)

    private fun saveHistory() {
        if (!settings.historyEnabled) return
        val entry = HistoryEntry(
            time = System.currentTimeMillis(),
            action = runLabel,
            sourceKind = source.kind,
            sourcePreview = source.text.take(200),
            result = resultText,
            model = runModel,
        )
        scope.launch(Dispatchers.IO) { runCatching { historyStore.add(entry) } }
    }

    // ---------------------------------------------------------------- aplicar a resposta

    /** Substituir só faz sentido quando o texto veio do próprio campo. */
    private val canReplace: Boolean
        get() = !fromHistory && (source.kind == SourceKind.SELECTION || source.kind == SourceKind.FIELD)

    private fun applyResult(replace: Boolean) {
        val text = resultText.trim()
        if (text.isEmpty()) return
        val h = host
        val ic = h?.inputConnection()
        if (h == null || ic == null || (runSession != -1L && h.session() != runSession && !fromHistory)) {
            copy(text)
            toast("O campo de texto mudou. A resposta foi copiada.")
            h?.closePanel()
            return
        }
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            if (replace && source.kind == SourceKind.SELECTION) {
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic.commitText(text, 1) else insertSmart(ic, text)
            } else if (replace && source.kind == SourceKind.FIELD) {
                val selected = ic.performContextMenuAction(android.R.id.selectAll)
                if (selected) {
                    ic.commitText(text, 1)
                } else {
                    val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
                    val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
                    ic.deleteSurroundingText(before, after)
                    ic.commitText(text, 1)
                }
            } else {
                insertSmart(ic, text)
            }
        } finally {
            ic.endBatchEdit()
        }
        h.closePanel()
    }

    private fun insertSmart(ic: InputConnection, text: String) {
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && !text.first().isWhitespace()
        ic.commitText(if (needsSpace) " $text" else text, 1)
    }

    private fun copy(text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Tecla", text))
        }
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- desenho

    private fun render() {
        removeAllViews()
        resultView = null
        statusView = null
        dotsView = null
        resultScroll = null
        setBackgroundColor(palette.background)
        setPadding(dp(10), dp(4), dp(10), dp(8))
        field.setTextColor(palette.text)
        field.setHintTextColor(palette.muted)
        field.caretColor = palette.accent

        addView(header())
        when (mode) {
            Mode.COMPOSE -> {
                addView(if (ready) sourceCard() else setupCard())
                if (ready) addView(chipsRow(settings.actionsOrPreview().map { Chip(it.label, it.icon) { startRun(it.label, it.icon, it.instruction) } }))
            }
            Mode.RESULT -> {
                addView(resultCard())
                if (!running && error == null && resultText.isNotBlank()) {
                    addView(chipsRow(REFINE.map { (label, prompt) -> Chip(label, null) { refine(prompt) } }))
                }
            }
            Mode.HISTORY -> addView(historyList())
            Mode.SESSION -> addView(sessionCard())
        }
        if (mode != Mode.HISTORY && ready) addView(fieldRow())
    }

    private fun TeclaSettings.actionsOrPreview(): List<TeclaAction> =
        if (previewNotReady != null) br.com.palora.tecla.core.DefaultActions.list else actions

    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        }
        val leftIcon = if (mode == Mode.COMPOSE) R.drawable.tecla_ic_chevron_down else R.drawable.tecla_ic_back
        val leftDesc = if (mode == Mode.COMPOSE) "Fechar o painel de IA" else "Voltar"
        row.addView(iconButton(leftIcon, leftDesc, palette.icon) {
            if (mode == Mode.COMPOSE) host?.closePanel() else {
                stopJob()
                running = false
                mode = Mode.COMPOSE
                field.setValue("")
                render()
            }
        })
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.tecla_ic_spark)
            imageTintList = ColorStateList.valueOf(PanelPalette.SPARK)
            layoutParams = LayoutParams(dp(18), dp(18)).apply { marginStart = dp(2); marginEnd = dp(8) }
        })
        row.addView(text(if (mode == Mode.HISTORY) "Histórico" else "Tecla IA", 15.5f, palette.text, bold = true))
        row.addView(View(context), LayoutParams(0, 1, 1f))

        if (mode == Mode.HISTORY) {
            if (historyEntries().isNotEmpty()) {
                row.addView(pill("Limpar", palette.surface, palette.muted) {
                    historyStore.clear()
                    render()
                })
            }
            return row
        }
        val model = if (previewNotReady != null) runModel.ifBlank { "gpt-6-luna" } else settings.model
        val count = if (ready) sessionCount() else -1
        if (ready && model.isNotBlank()) {
            row.addView(pill(model, palette.surface, palette.muted, maxWidthDp = if (count >= 0) 88 else 140) { host?.openApp() }.apply {
                contentDescription = "Modelo: $model. Toque para abrir as configurações"
            })
        }
        if (ready) {
            if (count >= 0) {
                row.addView(sessionPill(count))
            } else {
                row.addView(iconButton(R.drawable.tecla_ic_session, "Iniciar sessão de contexto", palette.icon) { startSession() })
            }
        }
        row.addView(iconButton(R.drawable.tecla_ic_history, "Histórico", palette.icon) {
            stopJob()
            running = false
            mode = Mode.HISTORY
            render()
        })
        return row
    }

    /** Selo da sessão ligada: ponto, nome e o número de trocas. Toque abre o cartão da sessão. */
    private fun sessionPill(count: Int): View = LinearLayout(context).apply {
        val name = sessionName()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), 0, dp(10), 0)
        background = rippled(round(palette.accentSoft, 13f), 13f)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(26)).apply { marginStart = dp(4); marginEnd = dp(2) }
        contentDescription = "Sessão $name ligada, $count trocas. Toque para renomear ou encerrar"
        setOnClickListener { openSessionCard() }
        addView(View(context).apply {
            background = round(palette.accent, 4f)
            layoutParams = LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        })
        addView(text(name, 12f, palette.onAccentSoft, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(96)
        })
        if (count > 0) addView(text(" $count", 12f, palette.onAccentSoft))
    }

    private fun sessionCard(): View {
        val card = card()
        val count = sessionCount().coerceAtLeast(0)
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(context).apply {
            background = round(palette.accentSoft, 20f)
            layoutParams = LayoutParams(dp(40), dp(40))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.tecla_ic_session)
                imageTintList = ColorStateList.valueOf(palette.onAccentSoft)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
        })
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        col.addView(text("Sessão de contexto", 15f, palette.text, bold = true))
        val minutes = if (previewSession != null) 30 else settings.sessionTimeout
        val trocas = when (count) {
            0 -> "nenhuma troca ainda"
            1 -> "1 troca"
            else -> "$count trocas"
        }
        val prazo = if (minutes > 0) "termina após ${if (minutes >= 60) "${minutes / 60} h" else "$minutes min"} sem uso" else "não termina sozinha"
        col.addView(text("$trocas · $prazo", 13f, palette.muted).apply { setPadding(0, dp(2), 0, 0) })
        top.addView(col)
        card.addView(top)
        card.addView(text("Mude o nome no campo abaixo (até ${TeclaSession.MAX_NAME} caracteres) e toque em ✓.", 13.5f, palette.muted).apply {
            setPadding(dp(2), dp(12), dp(2), 0)
        })
        card.addView(button("Encerrar sessão", primary = false, iconRes = R.drawable.tecla_ic_close) { endSession() }.apply {
            (layoutParams as LayoutParams).topMargin = dp(12)
        })
        return card
    }

    private fun setupCard(): View {
        val card = card()
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(context).apply {
            background = round(palette.accentSoft, 20f)
            layoutParams = LayoutParams(dp(40), dp(40))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.tecla_ic_key)
                imageTintList = ColorStateList.valueOf(palette.onAccentSoft)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
        })
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        col.addView(text("Conecte a IA para começar", 15f, palette.text, bold = true))
        col.addView(text("Escolha o provedor, cole a chave da API e pronto.", 13f, palette.muted).apply {
            setPadding(0, dp(2), 0, 0)
        })
        top.addView(col)
        card.addView(top)
        card.addView(button("Configurar", primary = true) { host?.openApp() }.apply {
            (layoutParams as LayoutParams).topMargin = dp(12)
        })
        return card
    }

    private fun sourceCard(): View {
        val card = card()
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val chip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            background = rippled(round(palette.accentSoft, 14f), 14f)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(28))
            isClickable = sources.size > 1
            contentDescription = "Origem do texto: ${source.kind.label}. Toque para trocar"
            setOnClickListener { cycleSource() }
        }
        chip.addView(icon(sourceIcon(source.kind), palette.onAccentSoft, 15))
        chip.addView(text(source.kind.label, 13f, palette.onAccentSoft, bold = true).apply {
            setPadding(dp(6), 0, dp(2), 0)
        })
        if (sources.size > 1) chip.addView(icon(R.drawable.tecla_ic_chevron_down, palette.onAccentSoft, 16))
        row.addView(chip)
        row.addView(View(context), LayoutParams(0, 1, 1f))
        if (source.kind != SourceKind.NONE) {
            row.addView(text(charCount(source.text), 12f, palette.muted))
        }
        card.addView(row)

        val preview = if (source.kind == SourceKind.NONE) {
            val withSession = sessionCount() > 0
            text(
                if (withSession) "Sem texto de apoio: vai o seu pedido e o contexto da sessão."
                else "A IA responde só ao que você escrever abaixo.",
                13.5f, palette.muted,
            )
        } else {
            text(source.text.trim().replace(Regex("\\s+"), " "), 14f, ColorUtils.setAlphaComponent(palette.text, 225)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        preview.setPadding(dp(2), dp(8), dp(2), 0)
        card.addView(preview)
        return card
    }

    private fun resultCard(): View {
        val card = card()
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(actionIcon(runIcon), palette.accent, 16))
        top.addView(text(runLabel.ifBlank { "Pedido" }, 13f, palette.muted, bold = true).apply {
            setPadding(dp(6), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val status = text(statusText(), 12f, palette.muted).apply { setPadding(dp(8), 0, 0, 0) }
        statusView = status
        top.addView(status)
        card.addView(top)

        val err = error
        if (err != null) {
            val errRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            errRow.addView(icon(R.drawable.tecla_ic_warning, palette.error, 18).apply {
                (layoutParams as LayoutParams).topMargin = dp(1)
            })
            errRow.addView(text(err.userMessage, 14f, palette.text).apply {
                setPadding(dp(10), 0, 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            card.addView(errRow)
            val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            buttons.addView(button("Tentar de novo", primary = true) { request() }, LayoutParams(0, dp(42), 1f))
            if (err.kind != TeclaException.Kind.NETWORK && err.kind != TeclaException.Kind.LIMIT) {
                buttons.addView(button("Configurar", primary = false) { host?.openApp() }, LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
            }
            card.addView(buttons)
            return card
        }

        val scroll = BoundedScrollView(context, maxResultHeight()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(18))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        val body = text(resultText, 15.5f, palette.text).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(false)
        }
        resultView = body
        val bodyWrap = FrameLayout(context)
        bodyWrap.addView(body)
        val dots = DotsView(context, palette.accent).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(20), Gravity.START or Gravity.TOP)
            visibility = if (running && resultText.isEmpty()) VISIBLE else GONE
        }
        dotsView = dots
        bodyWrap.addView(dots)
        scroll.addView(bodyWrap)
        resultScroll = scroll
        card.addView(scroll)

        if (!running && resultText.isNotBlank()) {
            val buttons = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            if (canReplace) {
                buttons.addView(button("Substituir", primary = true, iconRes = R.drawable.tecla_ic_replace) { applyResult(true) }, LayoutParams(0, dp(42), 1f))
                buttons.addView(button("Inserir", primary = false, iconRes = R.drawable.tecla_ic_insert) { applyResult(false) }, LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
            } else {
                buttons.addView(button("Inserir", primary = true, iconRes = R.drawable.tecla_ic_insert) { applyResult(false) }, LayoutParams(0, dp(42), 1f))
            }
            buttons.addView(roundIconButton(R.drawable.tecla_ic_copy, "Copiar") { v ->
                copy(resultText)
                (v as? FrameLayout)?.let { flashCheck(it) }
            }, LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
            if (!fromHistory) {
                buttons.addView(roundIconButton(R.drawable.tecla_ic_refresh, "Gerar de novo") { request() },
                    LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
            }
            card.addView(buttons)
        }
        // Gerando: acompanha o fim do texto. Pronto: mostra desde o começo.
        if (running) post { resultScroll?.fullScroll(View.FOCUS_DOWN) } else post { resultScroll?.scrollTo(0, 0) }
        return card
    }

    private fun historyList(): View {
        val entries = historyEntries()
        val scroll = BoundedScrollView(context, maxHistoryHeight()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        if (entries.isEmpty()) {
            val empty = card()
            empty.addView(text("Nada por aqui ainda.", 15f, palette.text, bold = true))
            empty.addView(text("As respostas que a IA der aparecem aqui, guardadas só no seu celular.", 13.5f, palette.muted).apply {
                setPadding(0, dp(4), 0, 0)
            })
            col.addView(empty)
        }
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        val dayFmt = DateFormat.getDateInstance(DateFormat.SHORT)
        entries.forEach { e ->
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
                background = rippled(round(palette.surface, 16f), 16f)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                setOnClickListener { openHistory(e) }
            }
            val sameDay = System.currentTimeMillis() - e.time < 20 * 3600_000L
            val whenText = if (sameDay) fmt.format(Date(e.time)) else dayFmt.format(Date(e.time))
            item.addView(text("${e.action}  ·  $whenText", 12.5f, palette.muted, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            item.addView(text(e.result.replace(Regex("\\s+"), " "), 14.5f, palette.text).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            col.addView(item)
        }
        scroll.addView(col)
        return scroll
    }

    private var previewHistory: List<HistoryEntry>? = null

    /** Só para as capturas de tela da CI. */
    fun setPreviewHistory(entries: List<HistoryEntry>) {
        previewHistory = entries
    }

    private fun historyEntries(): List<HistoryEntry> = previewHistory ?: historyStore.all()

    private fun openHistory(e: HistoryEntry) {
        stopJob()
        resetRun()
        fromHistory = true
        runLabel = e.action
        runIcon = "spark"
        runModel = e.model
        resultText = e.result
        elapsedMs = 0
        mode = Mode.RESULT
        render()
    }

    private fun fieldRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        (field.parent as? ViewGroup)?.removeView(field)
        field.hint = when {
            mode == Mode.SESSION -> "Nome da sessão"
            mode == Mode.RESULT && error == null && resultText.isNotBlank() -> "Ajustar: “mais curto”, “sem emoji”…"
            source.kind == SourceKind.NONE && sessionCount() > 0 -> "Continue a conversa…"
            source.kind == SourceKind.NONE -> "Pergunte ou peça qualquer coisa…"
            else -> "Diga o que fazer com o texto…"
        }
        val box = FrameLayout(context).apply {
            background = round(palette.surface, 22f)
            minimumHeight = dp(46)
            setPadding(dp(10), dp(11), dp(12), dp(11))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        row.addView(box)

        val send = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (running) stopRun() else submitField()
            }
        }
        val sendImg = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        }
        send.addView(sendImg)
        sendButton = send
        sendIcon = sendImg
        row.addView(send)
        updateSendButton()
        return row
    }

    private fun updateSendButton() {
        val send = sendButton ?: return
        val img = sendIcon ?: return
        val active = running || field.value.isNotBlank() || mode == Mode.SESSION
        send.background = rippled(round(if (active) palette.accent else palette.surface, 23f), 23f)
        img.setImageResource(
            when {
                running -> R.drawable.tecla_ic_stop
                mode == Mode.SESSION -> R.drawable.tecla_ic_check
                else -> R.drawable.tecla_ic_send
            }
        )
        img.imageTintList = ColorStateList.valueOf(if (active) palette.onAccent else palette.muted)
        send.contentDescription = when {
            running -> "Parar"
            mode == Mode.SESSION -> "Salvar o nome"
            else -> "Enviar"
        }
        send.isEnabled = active
    }

    private data class Chip(val label: String, val icon: String?, val onClick: () -> Unit)

    private fun chipsRow(chips: List<Chip>): View {
        // A fileira vai de borda a borda da tela (margem negativa) e o recuo fica dentro da rolagem,
        // para o último chip sumir na borda do celular e não cortado no meio do painel.
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                marginStart = -dp(10)
                marginEnd = -dp(10)
            }
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        chips.forEachIndexed { i, c ->
            val chip = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (c.icon != null) 11 else 14), 0, dp(14), 0)
                background = rippled(round(palette.surface, 18f), 18f)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).apply { if (i > 0) marginStart = dp(6) }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    c.onClick()
                }
            }
            if (c.icon != null) chip.addView(icon(actionIcon(c.icon), palette.icon, 17))
            chip.addView(text(c.label, 13.5f, palette.text, bold = true).apply {
                if (c.icon != null) setPadding(dp(7), 0, 0, 0)
            })
            row.addView(chip)
        }
        scroll.addView(row)
        return scroll
    }

    private fun updateStreaming() {
        val view = resultView ?: return
        view.text = resultText
        dotsView?.visibility = if (resultText.isEmpty()) VISIBLE else GONE
        updateStatus()
        resultScroll?.post { resultScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateStatus() {
        statusView?.text = statusText()
    }

    private fun statusText(): String = when {
        error != null -> "erro"
        running && thinking -> "pensando…"
        running -> "gerando…"
        fromHistory -> runModel
        stopped -> "interrompido"
        sessionHistory.isNotEmpty() -> "com contexto · " + "%.1f s".format(elapsedMs / 1000.0).replace('.', ',')
        else -> "%.1f s".format(elapsedMs / 1000.0).replace('.', ',')
    }

    private fun flashCheck(button: FrameLayout) {
        val img = button.getChildAt(0) as? ImageView ?: return
        img.setImageResource(R.drawable.tecla_ic_check)
        img.imageTintList = ColorStateList.valueOf(palette.accent)
        button.postDelayed({
            img.setImageResource(R.drawable.tecla_ic_copy)
            img.imageTintList = ColorStateList.valueOf(palette.icon)
        }, 1400)
    }

    // ---------------------------------------------------------------- peças visuais

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = round(palette.surface, 18f)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun text(value: String, sp: Float, @ColorInt color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = medium
    }

    private fun icon(@DrawableRes res: Int, @ColorInt tint: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconButton(@DrawableRes res: Int, desc: String, @ColorInt tint: Int, onClick: () -> Unit) = FrameLayout(context).apply {
        layoutParams = LayoutParams(dp(40), dp(40))
        background = rippled(null, 20f)
        contentDescription = desc
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
    }

    private fun roundIconButton(@DrawableRes res: Int, desc: String, onClick: (View) -> Unit) = FrameLayout(context).apply {
        background = rippled(round(palette.surfaceHigh, 21f), 21f)
        contentDescription = desc
        setOnClickListener { onClick(it) }
        addView(ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(palette.icon)
            layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
        })
    }

    private fun button(label: String, primary: Boolean, @DrawableRes iconRes: Int? = null, onClick: () -> Unit): View {
        val bg = if (primary) palette.accent else palette.surfaceHigh
        val fg = if (primary) palette.onAccent else palette.text
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(14), 0)
            background = rippled(round(bg, 21f), 21f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(42))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            if (iconRes != null) addView(icon(iconRes, fg, 17).apply { (layoutParams as LayoutParams).marginEnd = dp(6) })
            addView(text(label, 14.5f, fg, bold = true).apply { maxLines = 1 })
        }
    }

    private fun pill(label: String, @ColorInt bg: Int, @ColorInt fg: Int, maxWidthDp: Int = 0, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(fg)
        typeface = medium
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        if (maxWidthDp > 0) maxWidth = dp(maxWidthDp)
        setPadding(dp(10), 0, dp(10), 0)
        background = rippled(round(bg, 13f), 13f)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(2) }
        setOnClickListener { onClick() }
    }

    private fun round(@ColorInt color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
    }

    private fun rippled(content: Drawable?, radiusDp: Float): Drawable {
        val mask = round(0xFFFFFFFF.toInt(), radiusDp)
        return RippleDrawable(ColorStateList.valueOf(palette.ripple), content, mask)
    }

    @DrawableRes
    private fun sourceIcon(kind: SourceKind) = when (kind) {
        SourceKind.SELECTION -> R.drawable.tecla_ic_selection
        SourceKind.CLIPBOARD -> R.drawable.tecla_ic_clipboard
        SourceKind.FIELD -> R.drawable.tecla_ic_field
        SourceKind.NONE -> R.drawable.tecla_ic_chat
    }

    private fun charCount(text: String): String {
        val n = text.length
        val shown = if (n > Prompts.MAX_SOURCE_CHARS) "${Prompts.MAX_SOURCE_CHARS}+" else n.toString()
        return if (n == 1) "1 caractere" else "$shown caracteres"
    }

    private fun maxResultHeight(): Int = min(dp(150), (resources.displayMetrics.heightPixels * 0.19f).toInt())

    private fun maxHistoryHeight(): Int = min(dp(300), (resources.displayMetrics.heightPixels * 0.34f).toInt())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val MAX_FIELD_CHARS = 8000
        private const val MAX_PROMPT_CHARS = 1500
        private const val RECENT_CLIP_MS = 10 * 60 * 1000L

        private val REFINE = listOf(
            "Mais curto" to "Deixe mais curto.",
            "Outra versão" to "Escreva outra versão, diferente da anterior.",
            "Mais formal" to "Deixe mais formal.",
            "Mais informal" to "Deixe mais informal e descontraído.",
            "Mais longo" to "Deixe um pouco mais longo e completo.",
        )

        @DrawableRes
        fun actionIcon(name: String) = when (name) {
            "answer" -> R.drawable.tecla_ic_answer
            "translate" -> R.drawable.tecla_ic_translate
            "check" -> R.drawable.tecla_ic_check
            "reply" -> R.drawable.tecla_ic_reply
            "tie" -> R.drawable.tecla_ic_tie
            "list" -> R.drawable.tecla_ic_list
            "smile" -> R.drawable.tecla_ic_smile
            "bolt" -> R.drawable.tecla_ic_bolt
            "pencil" -> R.drawable.tecla_ic_pencil
            else -> R.drawable.tecla_ic_spark
        }
    }
}

/** ScrollView que cresce com o conteúdo até [maxHeight] e daí rola. */
private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }
}

/** Três pontos pulsando enquanto a primeira palavra não chega. */
private class DotsView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 6f
        val gap = r * 3.2f
        val cy = height / 2f
        for (i in 0 until 3) {
            val t = ((phase - i * 0.18f) % 1f + 1f) % 1f
            val pulse = if (t < 0.5f) t * 2f else (1f - t) * 2f
            paint.alpha = (90 + 165 * pulse).toInt()
            canvas.drawCircle(r + i * gap, cy - pulse * r * 0.6f, r, paint)
        }
    }
}
