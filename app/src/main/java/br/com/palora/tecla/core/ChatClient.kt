/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ChatMessage(val role: String, val content: String)

sealed interface ChatEvent {
    /** O modelo está raciocinando (alguns provedores mandam isso antes do texto). */
    data object Thinking : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data class Done(val totalTokens: Int?) : ChatEvent
}

/** Erro já traduzido para uma frase que dá para mostrar na tela. */
class TeclaException(val userMessage: String, val kind: Kind = Kind.OTHER, cause: Throwable? = null) :
    Exception(userMessage, cause) {
    enum class Kind { KEY, MODEL, NETWORK, LIMIT, PARAM, OTHER }
}

/**
 * Cliente mínimo do formato Chat Completions (OpenAI e compatíveis), com streaming por SSE.
 * Usa só HttpURLConnection e org.json, sem bibliotecas novas no APK.
 */
object ChatClient {
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 90_000

    fun stream(cfg: ChatConfig, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
        val url = URL(cfg.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        // Cancelar o painel corta a conexão na hora, mesmo no meio de uma leitura bloqueada.
        currentCoroutineContext().job.invokeOnCompletion { runCatching { conn.disconnect() } }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            applyHeaders(conn, cfg)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")

            val body = JSONObject()
                .put("model", cfg.model)
                .put("stream", true)
                .put("messages", JSONArray().apply {
                    messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                })
            if (cfg.reasoning.isNotBlank()) body.put("reasoning_effort", cfg.reasoning)
            if (isOpenAi(cfg)) body.put("stream_options", JSONObject().put("include_usage", true))

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) throw httpError(code, readError(conn))

            val contentType = conn.contentType.orEmpty()
            var tokens: Int? = null
            if (!contentType.contains("event-stream")) {
                // Provedor que ignorou "stream": a resposta veio inteira de uma vez.
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (content.isNotEmpty()) emit(ChatEvent.Delta(content))
                tokens = json.optJSONObject("usage")?.optInt("total_tokens")
                emit(ChatEvent.Done(tokens))
                return@flow
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var announcedThinking = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val json = try { JSONObject(data) } catch (e: Exception) { continue }
                    json.optJSONObject("error")?.let { err ->
                        throw TeclaException(friendlyApiMessage(err.optString("message")), TeclaException.Kind.OTHER)
                    }
                    json.optJSONObject("usage")?.let { u -> if (u.has("total_tokens")) tokens = u.optInt("total_tokens") }
                    val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val content = delta.optStringOrNull("content")
                    if (!content.isNullOrEmpty()) {
                        emit(ChatEvent.Delta(content))
                    } else if (!announcedThinking && (delta.has("reasoning") || delta.has("reasoning_content"))) {
                        announcedThinking = true
                        emit(ChatEvent.Thinking)
                    }
                }
            }
            emit(ChatEvent.Done(tokens))
        } catch (e: TeclaException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw networkError(e)
        } finally {
            runCatching { conn.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    /** Lista os modelos do provedor (GET /models). */
    suspend fun listModels(cfg: ChatConfig): List<String> = withContext(Dispatchers.IO) {
        val conn = URL(cfg.baseUrl.trimEnd('/') + "/models").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = 20_000
            applyHeaders(conn, cfg)
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(code, readError(conn))
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val out = mutableListOf<String>()
            json.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    o.optString("id").takeIf { it.isNotBlank() }?.let { out += it.removePrefix("models/") }
                }
            }
            json.optJSONArray("models")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    (o.optString("id").ifBlank { o.optString("name") }).takeIf { it.isNotBlank() }
                        ?.let { out += it.removePrefix("models/") }
                }
            }
            Providers.chatModelsOnly(out)
        } catch (e: TeclaException) {
            throw e
        } catch (e: Exception) {
            throw networkError(e)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun isOpenAi(cfg: ChatConfig) = cfg.baseUrl.contains("api.openai.com")

    private fun applyHeaders(conn: HttpURLConnection, cfg: ChatConfig) {
        conn.setRequestProperty("User-Agent", "Tecla/1.0 (Android)")
        if (cfg.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            if (cfg.baseUrl.contains("anthropic.com")) {
                conn.setRequestProperty("x-api-key", cfg.apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
        }
        if (cfg.baseUrl.contains("openrouter.ai")) {
            conn.setRequestProperty("HTTP-Referer", "https://github.com/jean-cunha/tecla")
            conn.setRequestProperty("X-Title", "Tecla")
        }
    }

    private fun readError(conn: HttpURLConnection): String = try {
        val raw = (conn.errorStream ?: return "").bufferedReader().use { it.readText() }
        try {
            val json = JSONObject(raw)
            val err = json.opt("error")
            when (err) {
                is JSONObject -> err.optString("message").ifBlank { raw }
                is String -> err
                else -> json.optString("message").ifBlank { raw }
            }
        } catch (e: Exception) {
            raw
        }.take(300)
    } catch (e: Exception) {
        ""
    }

    private fun httpError(code: Int, apiMessage: String): TeclaException {
        val detail = if (apiMessage.isNotBlank()) "\n${apiMessage.trim()}" else ""
        return when (code) {
            401, 403 -> TeclaException("A chave da API foi recusada ($code). Confira a chave nas configurações.$detail", TeclaException.Kind.KEY)
            404 -> TeclaException("Modelo ou endereço não encontrado (404). Confira o modelo e a URL.$detail", TeclaException.Kind.MODEL)
            402, 429 -> TeclaException("Limite de uso atingido ou sem créditos ($code). Espere um pouco ou confira o saldo.$detail", TeclaException.Kind.LIMIT)
            400, 422 -> {
                val aboutReasoning = apiMessage.contains("reasoning", ignoreCase = true)
                val hint = if (aboutReasoning) "\nDica: em Configurações, deixe \"Raciocínio\" como \"Não enviar\"." else ""
                TeclaException("O provedor recusou o pedido ($code).$detail$hint", TeclaException.Kind.PARAM)
            }
            in 500..599 -> TeclaException("O provedor está com problema agora (erro $code). Tente de novo em instantes.", TeclaException.Kind.OTHER)
            else -> TeclaException("Erro $code do provedor.$detail", TeclaException.Kind.OTHER)
        }
    }

    private fun friendlyApiMessage(msg: String) =
        if (msg.isBlank()) "O provedor interrompeu a resposta." else "O provedor interrompeu a resposta: $msg"

    private fun networkError(e: Exception): TeclaException = when (e) {
        is UnknownHostException, is ConnectException ->
            TeclaException("Sem conexão com o provedor. Confira a internet (ou a URL, se for um servidor seu).", TeclaException.Kind.NETWORK, e)
        is SocketTimeoutException ->
            TeclaException("O provedor demorou demais para responder.", TeclaException.Kind.NETWORK, e)
        is SSLException ->
            TeclaException("Falha de segurança na conexão (certificado).", TeclaException.Kind.NETWORK, e)
        else -> TeclaException("Não deu para falar com o provedor: ${e.message ?: e.javaClass.simpleName}", TeclaException.Kind.OTHER, e)
    }

    /** optString devolve "null" quando o valor é JSON null; aqui isso vira null de verdade. */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name)
}
