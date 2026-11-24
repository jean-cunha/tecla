/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Uma ação rápida do painel: um nome curto e a instrução que vai para a IA. */
data class TeclaAction(
    val id: String,
    val label: String,
    val instruction: String,
    /** Nome do ícone (ver [TeclaIcons]). */
    val icon: String = "spark",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("instruction", instruction).put("icon", icon)

    companion object {
        fun fromJson(o: JSONObject) = TeclaAction(
            id = o.optString("id").ifBlank { "a" + System.nanoTime() },
            label = o.optString("label"),
            instruction = o.optString("instruction"),
            icon = o.optString("icon", "spark"),
        )
    }
}

object DefaultActions {
    val list = listOf(
        TeclaAction(
            "responder", "Responder",
            "Responda à pergunta ou ao pedido que está no texto. Vá direto à resposta, sem repetir a pergunta.",
            "answer",
        ),
        TeclaAction(
            "traduzir", "Traduzir",
            "Traduza o texto. Se ele estiver em português, traduza para o inglês; se estiver em qualquer outro idioma, " +
                "traduza para o português do Brasil. Mantenha o tom e a formatação.",
            "translate",
        ),
        TeclaAction(
            "corrigir", "Corrigir",
            "Corrija ortografia, gramática, acentuação e pontuação. Mantenha o sentido, o tom e o idioma. " +
                "Não reescreva o que já está certo.",
            "check",
        ),
        TeclaAction(
            "melhorar", "Melhorar",
            "Reescreva o texto de forma mais clara e natural, mantendo o sentido, o tom e o idioma.",
            "spark",
        ),
        TeclaAction(
            "resposta", "Escrever resposta",
            "O texto é uma mensagem que eu recebi. Escreva a resposta que eu mandaria: curta, natural, " +
                "no mesmo idioma e no mesmo tom da mensagem. Escreva só a resposta.",
            "reply",
        ),
        TeclaAction(
            "formal", "Formal",
            "Reescreva o texto num tom formal e educado, adequado para trabalho, mantendo o sentido e o idioma.",
            "tie",
        ),
        TeclaAction(
            "resumir", "Resumir",
            "Resuma o texto em poucas linhas, no mesmo idioma, mantendo os pontos importantes.",
            "list",
        ),
    )
}

/** Ícones disponíveis para as ações (o nome é o que fica salvo). */
object TeclaIcons {
    val names = listOf("spark", "answer", "translate", "check", "reply", "tie", "list", "smile", "bolt", "pencil")
}

/**
 * Tudo o que a pessoa configura. Fica num SharedPreferences próprio ("tecla"), separado das
 * preferências do teclado, e a chave da API vai cifrada pelo [KeyVault].
 */
class TeclaSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var providerId: String
        get() = prefs.getString(K_PROVIDER, Providers.OPENAI.id) ?: Providers.OPENAI.id
        set(v) = prefs.edit().putString(K_PROVIDER, v).apply()

    val provider: Provider get() = Providers.byId(providerId)

    // URL, modelo, raciocínio e chave ficam guardados por provedor: trocar de provedor e voltar não perde nada.
    private fun k(base: String) = base + "_" + providerId

    var baseUrl: String
        get() = prefs.getString(k(K_BASE_URL), null)?.takeIf { it.isNotBlank() } ?: provider.baseUrl
        set(v) = prefs.edit().putString(k(K_BASE_URL), v.trim().trimEnd('/')).apply()

    var model: String
        get() = prefs.getString(k(K_MODEL), null) ?: provider.suggestedModel
        set(v) = prefs.edit().putString(k(K_MODEL), v.trim()).apply()

    var reasoning: String
        get() = prefs.getString(k(K_REASONING), null) ?: provider.defaultReasoning
        set(v) = prefs.edit().putString(k(K_REASONING), v).apply()

    var personal: String
        get() = prefs.getString(K_PERSONAL, "") ?: ""
        set(v) = prefs.edit().putString(K_PERSONAL, v.trim()).apply()

    var historyEnabled: Boolean
        get() = prefs.getBoolean(K_HISTORY, true)
        set(v) = prefs.edit().putBoolean(K_HISTORY, v).apply()

    /** Ao abrir o painel com texto copiado, já mostra a área de transferência como origem. */
    var preferClipboard: Boolean
        get() = prefs.getBoolean(K_PREFER_CLIP, true)
        set(v) = prefs.edit().putBoolean(K_PREFER_CLIP, v).apply()

    /** Minutos sem uso até a sessão de contexto terminar sozinha (0 = não termina). */
    var sessionTimeout: Int
        get() = prefs.getInt(K_SESSION_TIMEOUT, 30)
        set(v) = prefs.edit().putInt(K_SESSION_TIMEOUT, v).apply()

    var apiKey: String
        get() = prefs.getString(k(K_API_KEY), null)?.let { KeyVault.decrypt(it) } ?: ""
        set(v) {
            val clean = v.trim()
            if (clean.isEmpty()) prefs.edit().remove(k(K_API_KEY)).apply()
            else KeyVault.encrypt(clean)?.let { prefs.edit().putString(k(K_API_KEY), it).apply() }
        }

    val hasKey: Boolean get() = prefs.contains(k(K_API_KEY))

    /** Pronto para usar: tem URL, modelo e (se o provedor exige) chave. */
    val isReady: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && (hasKey || !provider.needsKey)

    var actions: List<TeclaAction>
        get() {
            val raw = prefs.getString(K_ACTIONS, null) ?: return DefaultActions.list
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) { TeclaAction.fromJson(arr.getJSONObject(it)) }.filter { it.label.isNotBlank() }
            } catch (e: Exception) {
                DefaultActions.list
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            prefs.edit().putString(K_ACTIONS, arr.toString()).apply()
        }

    fun resetActions() = prefs.edit().remove(K_ACTIONS).apply()

    /** Última lista de modelos que a API devolveu, para não buscar de novo toda vez. */
    var cachedModels: List<String>
        get() {
            val raw = prefs.getString(k(K_MODELS), null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) = prefs.edit().putString(k(K_MODELS), JSONArray(v).toString()).apply()

    fun snapshot() = ChatConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        reasoning = reasoning,
        providerId = providerId,
    )

    companion object {
        private const val PREFS = "tecla"
        private const val K_PROVIDER = "provider"
        private const val K_BASE_URL = "base_url"
        private const val K_MODEL = "model"
        private const val K_REASONING = "reasoning"
        private const val K_PERSONAL = "personal"
        private const val K_HISTORY = "history_enabled"
        private const val K_PREFER_CLIP = "prefer_clipboard"
        private const val K_API_KEY = "api_key_enc"
        private const val K_ACTIONS = "actions_v1"
        private const val K_MODELS = "models"
        private const val K_SESSION_TIMEOUT = "session_timeout_min"
    }
}

data class ChatConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoning: String,
    val providerId: String,
)
