/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Campo de texto "de mentira" do painel. O teclado não pode digitar num EditText dele mesmo
 * (as teclas vão para o app de fora), então o painel recebe as teclas pelo [TeclaAiPanelView.onHostKey]
 * e só desenha o texto e o cursor piscando aqui.
 */
@SuppressLint("ViewConstructor")
class CaretField(context: Context) : TextView(context) {
    private val buffer = StringBuilder()
    var caret = 0
        private set

    private var selStart = -1
    private var selEnd = -1

    private var caretOn = true
    var blinking = false
        set(value) {
            field = value
            removeCallbacks(blink)
            caretOn = true
            if (value) postDelayed(blink, BLINK_MS)
            invalidate()
        }

    var caretColor: Int = 0
        set(value) {
            field = value
            caretPaint.color = value
            invalidate()
        }

    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caretRect = RectF()
    private val caretWidth = resources.displayMetrics.density * 2f

    private val blink = object : Runnable {
        override fun run() {
            if (!blinking || !isAttachedToWindow) return
            caretOn = !caretOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    val value: String get() = buffer.toString()

    fun isEmpty() = buffer.isEmpty()

    fun setValue(text: String, caretAt: Int = text.length) {
        buffer.setLength(0)
        buffer.append(text)
        caret = caretAt.coerceIn(0, buffer.length)
        clearSelection()
        render()
    }

    fun insert(text: String) {
        if (hasSelectionRange()) deleteSelection()
        buffer.insert(caret, text)
        caret += text.length
        render()
    }

    /** Apaga um caractere (code point) antes do cursor. */
    fun deleteBack(): Boolean {
        if (hasSelectionRange()) {
            deleteSelection()
            render()
            return true
        }
        if (caret <= 0) return false
        val prev = buffer.offsetByCodePoints(caret, -1)
        buffer.delete(prev, caret)
        caret = prev
        render()
        return true
    }

    fun moveCaret(steps: Int): Boolean {
        if (buffer.isEmpty() || steps == 0) return false
        var pos = caret
        var count = kotlin.math.abs(steps)
        while (count > 0) {
            pos = if (steps > 0) {
                if (pos >= buffer.length) break
                buffer.offsetByCodePoints(pos, 1)
            } else {
                if (pos <= 0) break
                buffer.offsetByCodePoints(pos, -1)
            }
            count--
        }
        if (pos == caret) return false
        caret = pos
        render()
        return true
    }

    /** Usado no arrastar da tecla apagar: marca de [start] até [end] para apagar ao soltar. */
    fun markForDeletion(start: Int, end: Int) {
        selStart = start.coerceIn(0, buffer.length)
        selEnd = end.coerceIn(0, buffer.length)
        render()
    }

    fun commitMarkedDeletion() {
        if (hasSelectionRange()) deleteSelection()
        clearSelection()
        render()
    }

    fun offsetFrom(start: Int, steps: Int): Int {
        var pos = start.coerceIn(0, buffer.length)
        var count = kotlin.math.abs(steps)
        while (count > 0) {
            pos = if (steps > 0) {
                if (pos >= buffer.length) break
                buffer.offsetByCodePoints(pos, 1)
            } else {
                if (pos <= 0) break
                buffer.offsetByCodePoints(pos, -1)
            }
            count--
        }
        return pos
    }

    private fun hasSelectionRange() = selStart >= 0 && selEnd >= 0 && selStart != selEnd

    private fun deleteSelection() {
        val a = minOf(selStart, selEnd)
        val b = maxOf(selStart, selEnd)
        buffer.delete(a, b)
        caret = a
        clearSelection()
    }

    private fun clearSelection() {
        selStart = -1
        selEnd = -1
    }

    private fun render() {
        if (hasSelectionRange()) {
            val s = SpannableString(buffer.toString())
            s.setSpan(
                BackgroundColorSpan(ColorUtils.setAlphaComponent(caretColor, 90)),
                minOf(selStart, selEnd), maxOf(selStart, selEnd), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text = s
        } else {
            text = buffer.toString()
        }
        caretOn = true
        if (blinking) {
            removeCallbacks(blink)
            postDelayed(blink, BLINK_MS)
        }
        post { scrollToCaret() }
        invalidate()
    }

    private fun scrollToCaret() {
        val l = layout ?: return
        val line = l.getLineForOffset(caret.coerceIn(0, text.length))
        val visible = height - totalPaddingTop - totalPaddingBottom
        val lineBottom = l.getLineBottom(line)
        val lineTop = l.getLineTop(line)
        val target = when {
            lineBottom - scrollY > visible -> lineBottom - visible
            lineTop < scrollY -> lineTop
            else -> scrollY
        }
        if (target != scrollY) scrollTo(0, target.coerceAtLeast(0))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!blinking || !caretOn) return
        val l = layout ?: return
        val pos = caret.coerceIn(0, text.length)
        val line = l.getLineForOffset(pos)
        val x = if (text.isEmpty()) 0f else l.getPrimaryHorizontal(pos)
        val left = totalPaddingLeft + x
        // Com o campo vazio, o cursor fica na altura da dica (que o TextView desenha como texto).
        val top = totalPaddingTop + l.getLineTop(line) + lineHeight * 0.12f
        val bottom = totalPaddingTop + l.getLineBottom(line) - lineHeight * 0.12f
        caretRect.set(left, top, left + caretWidth, bottom)
        canvas.drawRoundRect(caretRect, caretWidth, caretWidth, caretPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val BLINK_MS = 530L
    }
}
