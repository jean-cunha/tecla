/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla

import br.com.palora.tecla.core.Prompts
import br.com.palora.tecla.core.Providers
import br.com.palora.tecla.core.Source
import br.com.palora.tecla.core.SourceKind
import br.com.palora.tecla.core.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeclaCoreTest {
    @Test
    fun limpaAspasECercas() {
        assertEquals("Olá, tudo bem?", Prompts.clean("\"Olá, tudo bem?\""))
        assertEquals("Olá", Prompts.clean("“Olá”"))
        assertEquals("linha 1\nlinha 2", Prompts.clean("```\nlinha 1\nlinha 2\n```"))
        assertEquals("texto", Prompts.clean("```text\ntexto\n```"))
        // Aspas no meio do texto ficam.
        assertEquals("Ele disse \"oi\" e saiu", Prompts.clean("Ele disse \"oi\" e saiu"))
    }

    @Test
    fun pedidoComTextoDeApoio() {
        val msg = Prompts.firstUser("Traduza.", Source(SourceKind.CLIPBOARD, "Bom dia"))
        assertTrue(msg.startsWith("Pedido: Traduza."))
        assertTrue(msg.contains("texto copiado"))
        assertTrue(msg.contains("<<<\nBom dia\n>>>"))
    }

    @Test
    fun pedidoSemTexto() {
        assertEquals("Qual a capital do Chile?", Prompts.firstUser("Qual a capital do Chile?", Source.NONE))
    }

    @Test
    fun conversaComAjustes() {
        val msgs = Prompts.build("", "Corrija.", Source(SourceKind.FIELD, "eu vai"), listOf(Turn("Eu vou.", "mais formal")))
        assertEquals(listOf("system", "user", "assistant", "user"), msgs.map { it.role })
        assertEquals("Eu vou.", msgs[2].content)
        assertTrue(msgs[3].content.contains("mais formal"))
    }

    @Test
    fun instrucoesPessoaisVaoNoSistema() {
        assertTrue(Prompts.system("Escrevo informal.").contains("Escrevo informal."))
        assertTrue(!Prompts.system("").contains("Sobre quem"))
    }

    @Test
    fun filtraEOrdenaModelos() {
        val list = Providers.chatModelsOnly(
            listOf("gpt-5.10-mini", "whisper-1", "text-embedding-3-small", "gpt-5.9-mini", "gpt-6-luna", "tts-1", "gpt-image-1")
        )
        assertEquals(listOf("gpt-5.9-mini", "gpt-5.10-mini", "gpt-6-luna"), list)
    }

    @Test
    fun recomendaModeloRapido() {
        assertEquals("gpt-6-luna", Providers.recommend(Providers.OPENAI, listOf("gpt-6.1-sol", "gpt-6-luna", "gpt-6.1-terra")))
        assertEquals("gemini-2.5-flash-lite", Providers.recommend(Providers.GEMINI, listOf("gemini-2.5-pro", "gemini-2.5-flash-lite")))
        assertEquals(null, Providers.recommend(Providers.OPENAI, emptyList()))
    }
}
