/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma troca da sessão: o que foi pedido (já com o texto de apoio) e a resposta final. */
data class Exchange(val time: Long, val label: String, val user: String, val answer: String) {
    fun toJson(): JSONObject = JSONObject().put("t", time).put("l", label).put("u", user).put("a", answer)

    companion object {
        fun fromJson(o: JSONObject) = Exchange(o.optLong("t"), o.optString("l"), o.optString("u"), o.optString("a"))
    }
}

/**
 * Sessão de contexto: enquanto está ligada, cada pedido leva junto as últimas trocas, para a IA
 * lembrar do que já foi falado. Liga e desliga pelo painel; termina sozinha depois de um tempo sem uso.
 * Fica num arquivo privado, para sobreviver ao teclado ser fechado pelo sistema.
 */
class TeclaSession(context: Context, private val timeoutMinutes: () -> Int) {
    private val file = File(context.applicationContext.filesDir, "tecla_session.json")

    private data class State(val active: Boolean, val lastUse: Long, val exchanges: List<Exchange>)

    @Synchronized
    private fun read(): State = try {
        if (!file.exists()) State(false, 0, emptyList()) else {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("x") ?: JSONArray()
            State(o.optBoolean("on"), o.optLong("last"), List(arr.length()) { Exchange.fromJson(arr.getJSONObject(it)) })
        }
    } catch (e: Exception) {
        State(false, 0, emptyList())
    }

    @Synchronized
    private fun write(s: State) {
        val arr = JSONArray()
        s.exchanges.forEach { arr.put(it.toJson()) }
        file.writeText(JSONObject().put("on", s.active).put("last", s.lastUse).put("x", arr).toString())
    }

    /** Ligada e ainda dentro do prazo. Uma sessão vencida é encerrada aqui mesmo. */
    @Synchronized
    fun isActive(now: Long = System.currentTimeMillis()): Boolean {
        val s = read()
        if (!s.active) return false
        if (expired(s, now)) {
            end()
            return false
        }
        return true
    }

    private fun expired(s: State, now: Long): Boolean {
        val minutes = timeoutMinutes()
        return minutes > 0 && now - s.lastUse > minutes * 60_000L
    }

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()) = write(State(true, now, emptyList()))

    @Synchronized
    fun end() {
        file.delete()
    }

    @Synchronized
    fun exchanges(): List<Exchange> = if (isActive()) read().exchanges else emptyList()

    @Synchronized
    fun count(): Int = exchanges().size

    @Synchronized
    fun add(e: Exchange, now: Long = System.currentTimeMillis()) {
        val s = read()
        if (!s.active) return
        write(State(true, now, (s.exchanges + e).takeLast(MAX_STORED)))
    }

    /** "Gerar de novo" e os ajustes trocam a resposta da última troca, em vez de criar outra. */
    @Synchronized
    fun replaceLastAnswer(answer: String, now: Long = System.currentTimeMillis()) {
        val s = read()
        if (!s.active || s.exchanges.isEmpty()) return
        val list = s.exchanges.toMutableList()
        list[list.lastIndex] = list.last().copy(answer = answer)
        write(State(true, now, list))
    }

    companion object {
        const val MAX_STORED = 20

        /** Texto de apoio guardado em cada troca (o pedido atual vai inteiro; o passado, resumido). */
        const val MAX_SOURCE_IN_HISTORY = 3000

        /** Teto de contexto enviado: as trocas mais antigas saem primeiro. */
        const val MAX_CONTEXT_CHARS = 16_000

        /** As trocas mais recentes que cabem no teto, na ordem em que aconteceram. */
        fun fit(all: List<Exchange>, maxChars: Int = MAX_CONTEXT_CHARS): List<Exchange> {
            val out = ArrayDeque<Exchange>()
            var total = 0
            for (e in all.asReversed()) {
                val size = e.user.length + e.answer.length
                if (total + size > maxChars && out.isNotEmpty()) break
                out.addFirst(e)
                total += size
            }
            return out.toList()
        }

        val timeoutOptions = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hora", 180 to "3 horas", 0 to "Sem limite")
    }
}
