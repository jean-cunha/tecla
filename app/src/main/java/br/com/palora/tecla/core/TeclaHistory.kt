/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val time: Long,
    val action: String,
    val sourceKind: SourceKind,
    val sourcePreview: String,
    val result: String,
    val model: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", time).put("a", action).put("k", sourceKind.name)
        .put("s", sourcePreview).put("r", result).put("m", model)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            time = o.optLong("t"),
            action = o.optString("a"),
            sourceKind = runCatching { SourceKind.valueOf(o.optString("k")) }.getOrDefault(SourceKind.NONE),
            sourcePreview = o.optString("s"),
            result = o.optString("r"),
            model = o.optString("m"),
        )
    }
}

/** Últimas respostas, guardadas só no aparelho (arquivo privado do app). */
class TeclaHistory(context: Context) {
    private val file = File(context.applicationContext.filesDir, "tecla_history.json")

    @Synchronized
    fun all(): List<HistoryEntry> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            List(arr.length()) { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(entry: HistoryEntry) {
        val list = (listOf(entry) + all()).take(MAX)
        write(list)
    }

    @Synchronized
    fun remove(time: Long) = write(all().filterNot { it.time == time })

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        const val MAX = 40
    }
}
