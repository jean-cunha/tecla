/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

/**
 * Provedores que falam o formato de Chat Completions da OpenAI. Todos usam o mesmo cliente:
 * só muda a URL base, o formato da chave e o modelo sugerido.
 */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyHint: String,
    val keyUrl: String?,
    /** Modelo usado quando a pessoa ainda não escolheu um da lista. */
    val suggestedModel: String,
    /** Valor de `reasoning_effort` enviado por padrão; vazio = não enviar o campo. */
    val defaultReasoning: String = "",
    val needsKey: Boolean = true,
)

object Providers {
    val OPENAI = Provider(
        id = "openai", label = "OpenAI", baseUrl = "https://api.openai.com/v1",
        keyHint = "sk-...", keyUrl = "https://platform.openai.com/api-keys",
        suggestedModel = "gpt-6-luna", defaultReasoning = "low",
    )
    val OPENROUTER = Provider(
        id = "openrouter", label = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1",
        keyHint = "sk-or-...", keyUrl = "https://openrouter.ai/keys",
        suggestedModel = "openai/gpt-6-luna",
    )
    val GROQ = Provider(
        id = "groq", label = "Groq", baseUrl = "https://api.groq.com/openai/v1",
        keyHint = "gsk_...", keyUrl = "https://console.groq.com/keys",
        suggestedModel = "llama-3.3-70b-versatile",
    )
    val GEMINI = Provider(
        id = "gemini", label = "Google Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        keyHint = "AIza...", keyUrl = "https://aistudio.google.com/apikey",
        suggestedModel = "gemini-flash-latest",
    )
    val ANTHROPIC = Provider(
        id = "anthropic", label = "Anthropic (Claude)", baseUrl = "https://api.anthropic.com/v1",
        keyHint = "sk-ant-...", keyUrl = "https://console.anthropic.com/settings/keys",
        suggestedModel = "claude-haiku-5-5",
    )
    val DEEPSEEK = Provider(
        id = "deepseek", label = "DeepSeek", baseUrl = "https://api.deepseek.com/v1",
        keyHint = "sk-...", keyUrl = "https://platform.deepseek.com/api_keys",
        suggestedModel = "deepseek-chat",
    )
    val OLLAMA = Provider(
        id = "ollama", label = "Ollama (no seu PC)", baseUrl = "http://192.168.0.10:11434/v1",
        keyHint = "não precisa", keyUrl = null, suggestedModel = "", needsKey = false,
    )
    val CUSTOM = Provider(
        id = "custom", label = "Outro compatível", baseUrl = "",
        keyHint = "chave do serviço", keyUrl = null, suggestedModel = "", needsKey = false,
    )

    val all = listOf(OPENAI, OPENROUTER, GEMINI, ANTHROPIC, GROQ, DEEPSEEK, OLLAMA, CUSTOM)

    fun byId(id: String?): Provider = all.firstOrNull { it.id == id } ?: OPENAI

    /** Opções de `reasoning_effort`. A primeira (vazia) não envia o campo, para não quebrar provedores que não o aceitam. */
    val reasoningOptions = listOf(
        "" to "Não enviar",
        "none" to "Nenhum (mais rápido)",
        "minimal" to "Mínimo",
        "low" to "Baixo",
        "medium" to "Médio",
        "high" to "Alto",
    )

    /**
     * Escolhe um modelo bom para teclado (rápido e barato) dentro da lista devolvida pela API.
     * Só é usado quando a pessoa ainda não escolheu nenhum.
     */
    fun recommend(provider: Provider, models: List<String>): String? {
        if (models.isEmpty()) return null
        models.firstOrNull { it == provider.suggestedModel }?.let { return it }
        val preferences = listOf("luna", "flash-lite", "flash", "haiku", "mini", "instant", "chat", "small")
        for (p in preferences) {
            models.firstOrNull { it.contains(p, ignoreCase = true) }?.let { return it }
        }
        return models.first()
    }

    /** Tira da lista o que não conversa por texto (voz, imagem, embeddings, moderação...). */
    fun chatModelsOnly(models: List<String>): List<String> {
        val blocked = listOf(
            "embed", "whisper", "tts", "transcribe", "dall-e", "image", "moderation", "audio",
            "realtime", "davinci", "babbage", "rerank", "guard", "sora", "live", "speech", "vision-preview",
        )
        val filtered = models.filterNot { m -> blocked.any { m.contains(it, ignoreCase = true) } }
        return (filtered.ifEmpty { models }).distinct().sortedWith(naturalOrder())
    }

    /** Ordena "gpt-5.10" depois de "gpt-5.9", e não antes. */
    private fun naturalOrder() = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                (x.toBigIntegerOrNull() ?: 0.toBigInteger()).compareTo(y.toBigIntegerOrNull() ?: 0.toBigInteger())
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }
}
