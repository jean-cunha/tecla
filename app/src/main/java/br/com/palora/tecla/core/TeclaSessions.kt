/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma troca dentro de uma sessão: o que foi pedido (já com o texto de apoio) e a resposta final. */
data class Exchange(val time: Long, val label: String, val user: String, val answer: String) {
    fun toJson(): JSONObject = JSONObject().put("t", time).put("l", label).put("u", user).put("a", answer)

    companion object {
        fun fromJson(o: JSONObject) = Exchange(o.optLong("t"), o.optString("l"), o.optString("u"), o.optString("a"))
    }
}

/** Uma conversa salva. Só a IA lê as trocas antigas; a tela mostra o nome e quantas trocas há. */
data class Session(val id: String, val name: String, val created: Long, val updated: Long, val exchanges: List<Exchange>) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        exchanges.forEach { arr.put(it.toJson()) }
        return JSONObject().put("id", id).put("n", name).put("c", created).put("u", updated).put("x", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val arr = o.optJSONArray("x") ?: JSONArray()
            return Session(
                id = o.optString("id"),
                name = o.optString("n").ifBlank { TeclaSessions.DEFAULT_NAME },
                created = o.optLong("c"),
                updated = o.optLong("u"),
                exchanges = List(arr.length()) { Exchange.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

/**
 * Sessões de contexto, como conversas: cada uma guarda as suas trocas, e a sessão escolhida vai junto
 * em todo pedido até você escolher outra ou "Sem sessão". Nada é criado sozinho, nada expira sozinho:
 * criar, escolher, renomear e apagar são sempre um toque seu.
 */
class TeclaSessions(context: Context) {
    private val file = File(context.applicationContext.filesDir, "tecla_sessions.json")

    private data class Store(val activeId: String?, val items: List<Session>)

    @Synchronized
    private fun read(): Store = try {
        if (!file.exists()) Store(null, emptyList()) else {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = List(arr.length()) { Session.fromJson(arr.getJSONObject(it)) }.filter { it.id.isNotBlank() }
            val active = o.optString("active").takeIf { id -> id.isNotBlank() && items.any { it.id == id } }
            Store(active, items)
        }
    } catch (e: Exception) {
        Store(null, emptyList())
    }

    @Synchronized
    private fun write(s: Store) {
        val arr = JSONArray()
        s.items.forEach { arr.put(it.toJson()) }
        val json = JSONObject().put("active", s.activeId ?: "").put("items", arr).toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    /** Todas, da usada mais recentemente para a mais antiga. */
    @Synchronized
    fun all(): List<Session> = read().items.sortedByDescending { it.updated }

    @Synchronized
    fun active(): Session? = read().let { s -> s.items.firstOrNull { it.id == s.activeId } }

    /** Escolhe a sessão que vai junto nos pedidos (null = sem sessão). */
    @Synchronized
    fun select(id: String?) {
        val s = read()
        write(s.copy(activeId = id?.takeIf { wanted -> s.items.any { it.id == wanted } }))
    }

    /** Cria uma sessão vazia e já a escolhe. */
    @Synchronized
    fun create(name: String, now: Long = System.currentTimeMillis()): Session {
        val s = read()
        val session = Session("s$now", cleanName(name), now, now, emptyList())
        write(Store(session.id, listOf(session) + s.items))
        return session
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val s = read()
        write(s.copy(items = s.items.map { if (it.id == id) it.copy(name = cleanName(name)) else it }))
    }

    @Synchronized
    fun delete(id: String) {
        val s = read()
        write(Store(if (s.activeId == id) null else s.activeId, s.items.filterNot { it.id == id }))
    }

    @Synchronized
    fun add(id: String, e: Exchange, now: Long = System.currentTimeMillis()) {
        val s = read()
        write(s.copy(items = s.items.map {
            if (it.id == id) it.copy(updated = now, exchanges = (it.exchanges + e).takeLast(MAX_STORED)) else it
        }))
    }

    /** "Gerar de novo" e os ajustes trocam a resposta da última troca, em vez de criar outra. */
    @Synchronized
    fun replaceLastAnswer(id: String, answer: String, now: Long = System.currentTimeMillis()) {
        val s = read()
        write(s.copy(items = s.items.map {
            if (it.id == id && it.exchanges.isNotEmpty()) {
                val list = it.exchanges.toMutableList()
                list[list.lastIndex] = list.last().copy(answer = answer)
                it.copy(updated = now, exchanges = list)
            } else it
        }))
    }

    companion object {
        const val DEFAULT_NAME = "Sessão"

        /** Limite do nome: é o que cabe no selo do topo do painel. */
        const val MAX_NAME = 16

        /** Trocas guardadas por sessão (as mais antigas saem). */
        const val MAX_STORED = 80

        /** Texto de apoio guardado em cada troca (o pedido atual vai inteiro; o passado, resumido). */
        const val MAX_SOURCE_IN_HISTORY = 3000

        /** Teto de contexto enviado por pedido: as trocas mais antigas ficam de fora primeiro. */
        const val MAX_CONTEXT_CHARS = 20_000

        /** "hoje 14:30", "ontem", "24/11" ou "24/11/25": sempre no formato brasileiro. */
        fun whenLabel(time: Long, now: Long = System.currentTimeMillis()): String {
            val zone = java.time.ZoneId.systemDefault()
            val t = java.time.Instant.ofEpochMilli(time).atZone(zone)
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val day = t.toLocalDate()
            fun two(n: Int) = n.toString().padStart(2, '0')
            return when {
                day == today -> "hoje ${two(t.hour)}:${two(t.minute)}"
                day == today.minusDays(1) -> "ontem"
                day.year == today.year -> "${two(day.dayOfMonth)}/${two(day.monthValue)}"
                else -> "${two(day.dayOfMonth)}/${two(day.monthValue)}/${two(day.year % 100)}"
            }
        }

        fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).ifBlank { DEFAULT_NAME }

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
    }
}
