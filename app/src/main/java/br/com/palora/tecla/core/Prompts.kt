/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.core

/** De onde vem o texto que vai junto com o pedido. */
enum class SourceKind(val label: String) {
    SELECTION("Seleção"),
    CLIPBOARD("Copiado"),
    FIELD("Texto do campo"),
    NONE("Só o pedido"),
}

data class Source(val kind: SourceKind, val text: String) {
    companion object {
        val NONE = Source(SourceKind.NONE, "")
    }
}

/** Uma rodada de ajuste depois da primeira resposta ("mais curto", "sem emoji"...). */
data class Turn(val answer: String, val followUp: String)

object Prompts {
    /** Limite de texto enviado, para um "copiar tudo" gigante não estourar custo nem contexto. */
    const val MAX_SOURCE_CHARS = 12_000

    fun system(personal: String): String = buildString {
        append(
            "Você é a IA do Tecla, um teclado de celular. O que você escrever será inserido direto num campo de texto " +
                "de outro aplicativo (WhatsApp, e-mail, navegador...).\n" +
                "Regras:\n" +
                "- Responda só com o texto final, pronto para usar. Nada de introdução, comentário ou explicação sobre o que fez.\n" +
                "- Não use Markdown (sem **, #, ``` ou tabelas). Listas só se o pedido pedir, com hífen simples.\n" +
                "- Não coloque aspas em volta da resposta.\n" +
                "- Se o pedido for ambíguo, faça a interpretação mais provável em vez de perguntar.\n" +
                "- Escreva em português do Brasil, a não ser que o pedido ou o texto indiquem outro idioma."
        )
        if (personal.isNotBlank()) {
            append("\n\nSobre quem está usando o teclado (use quando fizer diferença):\n")
            append(personal.trim())
        }
    }

    fun firstUser(instruction: String, source: Source): String {
        val text = source.text.take(MAX_SOURCE_CHARS)
        if (source.kind == SourceKind.NONE || text.isBlank()) return instruction.trim()
        val where = when (source.kind) {
            SourceKind.SELECTION -> "texto selecionado"
            SourceKind.CLIPBOARD -> "texto copiado"
            SourceKind.FIELD -> "texto do campo"
            SourceKind.NONE -> "texto"
        }
        return "Pedido: ${instruction.trim()}\n\nO $where está entre <<< e >>>:\n<<<\n$text\n>>>"
    }

    fun build(personal: String, instruction: String, source: Source, turns: List<Turn>): List<ChatMessage> {
        val list = mutableListOf(
            ChatMessage("system", system(personal)),
            ChatMessage("user", firstUser(instruction, source)),
        )
        turns.forEach {
            list += ChatMessage("assistant", it.answer)
            list += ChatMessage("user", "Ajuste a sua última resposta: ${it.followUp.trim()}\nResponda de novo só com o texto final.")
        }
        return list
    }

    /** Limpa o que alguns modelos insistem em mandar: aspas em volta e cercas de código. */
    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").substringAfter('\n', s)
            s = s.removeSuffix("```").trim()
        }
        val quotes = listOf('"' to '"', '“' to '”', '«' to '»')
        for ((open, close) in quotes) {
            if (s.length >= 2 && s.first() == open && s.last() == close && s.count { it == open || it == close } == 2) {
                s = s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }
}
