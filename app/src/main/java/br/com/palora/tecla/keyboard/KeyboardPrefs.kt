/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.keyboard

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import br.com.palora.tecla.core.Providers
import br.com.palora.tecla.core.TeclaSettings
import com.leanbitlab.leantype.voice.VoiceConstants
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ChecksumCalculator
import helium314.keyboard.latin.utils.GestureLibraryDownloader
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.ProofreadService
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File

/** Um botão que pode ir na barra do teclado, com nome e explicação em português simples. */
data class BarKey(val key: ToolbarKey, val label: String, val description: String, @DrawableRes val icon: Int)

/** Uma opção liga/desliga do teclado, guardada nas preferências do LeanType. */
data class Toggle(val pref: String, val label: String, val description: String, val default: Boolean)

/**
 * Ponte entre a tela do Tecla e as preferências do teclado (LeanType). O Tecla mostra só o que importa,
 * com nomes claros; "Todas as configurações" continua dando acesso ao resto.
 */
object KeyboardPrefs {

    val catalog = listOf(
        BarKey(ToolbarKey.TECLA_AI, "IA do Tecla", "Abre o painel de IA", R.drawable.ic_tecla_ai_rounded),
        BarKey(ToolbarKey.VOICE, "Falar", "Digitar falando (precisa ligar a voz abaixo)", R.drawable.sym_keyboard_voice_rounded),
        BarKey(ToolbarKey.CLIPBOARD, "Copiados", "Histórico do que você copiou", R.drawable.sym_keyboard_clipboard_rounded),
        BarKey(ToolbarKey.EMOJI, "Emoji", "Abre os emojis", R.drawable.sym_keyboard_smiley_rounded),
        BarKey(ToolbarKey.UNDO, "Desfazer", "Volta a última digitação", R.drawable.ic_undo_rounded),
        BarKey(ToolbarKey.REDO, "Refazer", "Refaz o que foi desfeito", R.drawable.ic_redo_rounded),
        BarKey(ToolbarKey.TEXT_EDIT, "Editar texto", "Setas e seleção para mexer no texto com precisão", R.drawable.ic_text_edit),
        BarKey(ToolbarKey.TOUCHPAD, "Touchpad", "Arraste para mover o cursor", R.drawable.ic_touchpad_rounded),
        BarKey(ToolbarKey.SELECT_ALL, "Selecionar tudo", "Seleciona todo o texto do campo", R.drawable.ic_select_all_rounded),
        BarKey(ToolbarKey.COPY, "Copiar", "Copia o que está selecionado", R.drawable.sym_keyboard_copy_rounded),
        BarKey(ToolbarKey.PASTE, "Colar", "Cola o último copiado", R.drawable.sym_keyboard_paste_rounded),
        BarKey(ToolbarKey.NUMPAD, "Números", "Teclado numérico grande", R.drawable.sym_keyboard_numpad_key_lxx),
        BarKey(ToolbarKey.INCOGNITO, "Anônimo", "O teclado para de aprender palavras novas", R.drawable.ic_incognito_final),
        BarKey(ToolbarKey.ONE_HANDED, "Uma mão", "Teclado menor, de um lado da tela", R.drawable.sym_keyboard_start_onehanded_rounded),
        BarKey(ToolbarKey.FLOATING, "Flutuante", "Teclado em janela que dá para mover", R.drawable.ic_drag_indicator),
        BarKey(ToolbarKey.AUTOCORRECT, "Autocorreção", "Liga e desliga a correção automática", R.drawable.ic_autocorrect_rounded),
        BarKey(ToolbarKey.FONT_STYLE, "Letras estilizadas", "𝐍𝐞𝐠𝐫𝐢𝐭𝐨, 𝘪𝘵á𝘭𝘪𝘤𝘰 e outras", R.drawable.ic_font_style_rounded),
        BarKey(ToolbarKey.OCR, "Ler texto da câmera", "Aponta a câmera e o texto vira digitação", R.drawable.ic_ocr),
        BarKey(ToolbarKey.SETTINGS, "Configurações", "Abre as configurações do teclado", R.drawable.sym_keyboard_settings_rounded),
    )

    enum class Preset(val label: String, val keys: List<ToolbarKey>, val pinned: List<ToolbarKey>) {
        ESSENCIAL(
            "Essencial",
            listOf(ToolbarKey.TECLA_AI, ToolbarKey.VOICE, ToolbarKey.CLIPBOARD, ToolbarKey.UNDO),
            listOf(ToolbarKey.TECLA_AI, ToolbarKey.CLIPBOARD),
        ),
        EQUILIBRADO(
            "Equilibrado",
            listOf(
                ToolbarKey.TECLA_AI, ToolbarKey.VOICE, ToolbarKey.CLIPBOARD, ToolbarKey.EMOJI, ToolbarKey.UNDO,
                ToolbarKey.TEXT_EDIT, ToolbarKey.SELECT_ALL, ToolbarKey.COPY, ToolbarKey.PASTE, ToolbarKey.SETTINGS,
            ),
            listOf(ToolbarKey.TECLA_AI, ToolbarKey.CLIPBOARD),
        ),
        COMPLETO("Completo", catalog.map { it.key }, listOf(ToolbarKey.TECLA_AI, ToolbarKey.CLIPBOARD, ToolbarKey.VOICE)),
    }

    /** Quantos botões ficam sempre à vista, ao lado das sugestões. */
    const val MAX_PINNED = 3

    // ------------------------------------------------------------------ barra

    private fun parse(raw: String?): List<Pair<String, Boolean>> = raw.orEmpty()
        .split(Separators.ENTRY)
        .filter { it.contains(Separators.KV) }
        .map { it.substringBefore(Separators.KV) to (it.substringAfter(Separators.KV) == "true") }

    private fun toKeys(list: List<Pair<String, Boolean>>): List<ToolbarKey> =
        list.filter { it.second }.mapNotNull { (name, _) -> runCatching { ToolbarKey.valueOf(name) }.getOrNull() }

    fun enabledKeys(ctx: Context): List<ToolbarKey> =
        toKeys(parse(ctx.prefs().getString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)))

    fun pinnedKeys(ctx: Context): List<ToolbarKey> =
        toKeys(parse(ctx.prefs().getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)))

    /**
     * Grava a barra: [enabled] na ordem dada, depois todo o resto desligado (para o LeanType não
     * reativar nada sozinho). Os fixos são os de [pinned] que também estão ligados.
     */
    fun setKeys(ctx: Context, enabled: List<ToolbarKey>, pinned: List<ToolbarKey>) {
        fun encode(on: List<ToolbarKey>, base: String): String {
            val all = parse(base).map { it.first }
            val rest = all.filterNot { name -> on.any { it.name == name } }
            return (on.map { it.name + Separators.KV + true } + rest.map { it + Separators.KV + false })
                .joinToString(Separators.ENTRY)
        }
        val pinnedOn = pinned.filter { it in enabled }.take(MAX_PINNED)
        ctx.prefs().edit {
            putString(Settings.PREF_TOOLBAR_KEYS, encode(enabled.distinct(), defaultToolbarPref))
            putString(Settings.PREF_PINNED_TOOLBAR_KEYS, encode(pinnedOn, defaultPinnedToolbarPref))
        }
    }

    fun applyPreset(ctx: Context, preset: Preset) = setKeys(ctx, preset.keys, preset.pinned)

    /** Na primeira abertura do Tecla, a barra começa enxuta (o padrão do LeanType liga quase tudo). */
    fun applyFirstRunDefaults(ctx: Context) {
        val tecla = ctx.getSharedPreferences("tecla", Context.MODE_PRIVATE)
        if (tecla.getBoolean("keyboard_defaults_applied", false)) return
        if (!ctx.prefs().contains(Settings.PREF_TOOLBAR_KEYS)) applyPreset(ctx, Preset.ESSENCIAL)
        tecla.edit { putBoolean("keyboard_defaults_applied", true) }
    }

    // ------------------------------------------------------------------ liga/desliga

    val toggles = listOf(
        Toggle(Settings.PREF_SHOW_SUGGESTIONS, "Sugestões de palavras", "Mostra palavras enquanto você digita", Defaults.PREF_SHOW_SUGGESTIONS),
        Toggle(Settings.PREF_AUTO_CORRECTION, "Correção automática", "Troca a palavra errada ao apertar espaço", Defaults.PREF_AUTO_CORRECTION),
        Toggle(Settings.PREF_BIGRAM_PREDICTIONS, "Prever a próxima palavra", "Sugere a palavra seguinte antes de você começar", Defaults.PREF_BIGRAM_PREDICTIONS),
        Toggle(Settings.PREF_AUTO_CAP, "Maiúscula automática", "Começa frases com letra maiúscula", Defaults.PREF_AUTO_CAP),
        Toggle(Settings.PREF_SHOW_NUMBER_ROW, "Linha de números", "Números sempre visíveis em cima das letras", Defaults.PREF_SHOW_NUMBER_ROW),
        Toggle(Settings.PREF_VIBRATE_ON, "Vibrar ao tocar", "Uma vibração curta a cada tecla", Defaults.PREF_VIBRATE_ON),
        Toggle(Settings.PREF_SOUND_ON, "Som ao tocar", "Clique a cada tecla", Defaults.PREF_SOUND_ON),
        Toggle(Settings.PREF_POPUP_ON, "Ampliar a tecla tocada", "Mostra a letra maior em cima do dedo", Defaults.PREF_POPUP_ON),
    )

    fun getToggle(ctx: Context, t: Toggle): Boolean = ctx.prefs().getBoolean(t.pref, t.default)

    fun setToggle(ctx: Context, t: Toggle, value: Boolean) = ctx.prefs().edit { putBoolean(t.pref, value) }

    // ------------------------------------------------------------------ digitar deslizando

    /** A biblioteca de gestos já está instalada e carregada? */
    fun glideInstalled(ctx: Context): Boolean =
        JniUtils.sHaveNativeGestureLib || File(ctx.filesDir, JniUtils.JNI_LIB_IMPORT_FILE_NAME).isFile

    fun glideEnabled(ctx: Context): Boolean = ctx.prefs().getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)

    fun setGlideEnabled(ctx: Context, on: Boolean) = ctx.prefs().edit { putBoolean(Settings.PREF_GESTURE_INPUT, on) }

    fun glideAvailableForDevice(): Boolean = GestureLibraryDownloader.isDownloadAvailable()

    /**
     * Baixa a biblioteca de gestos (a mesma que o LeanType oferece), confere a assinatura do arquivo
     * contra a esperada para este celular e instala. Depois disso o processo precisa reiniciar.
     */
    suspend fun installGlide(ctx: Context, onProgress: (Int) -> Unit): Result<Unit> {
        val downloaded = GestureLibraryDownloader.downloadLibrary(ctx, onProgress).getOrElse { return Result.failure(it) }
        val checksum = ChecksumCalculator.checksum(downloaded) ?: ""
        if (checksum != JniUtils.expectedDefaultChecksum()) {
            downloaded.delete()
            return Result.failure(IllegalStateException("o arquivo baixado não é o esperado para este celular"))
        }
        val libFile = File(ctx.filesDir, JniUtils.JNI_LIB_IMPORT_FILE_NAME)
        libFile.setWritable(true)
        libFile.delete()
        ctx.protectedPrefs().edit(commit = true) {
            putString(Settings.PREF_LIBRARY_CHECKSUM, checksum)
            putBoolean("pref_gesture_lib_just_installed", true)
        }
        ctx.prefs().edit(commit = true) { putBoolean(Settings.PREF_GESTURE_INPUT, true) }
        downloaded.copyTo(libFile)
        libFile.setReadOnly()
        downloaded.delete()
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ voz

    /** Provedores do Tecla que também transcrevem voz com a mesma chave. */
    fun voiceSupported(providerId: String) = providerId in listOf(Providers.OPENAI.id, Providers.GROQ.id, Providers.GEMINI.id)

    fun voiceEnabled(ctx: Context): Boolean =
        ctx.prefs().getString(VoiceConstants.PREF_VOICE_PROVIDER, null) == VoiceConstants.VOICE_PROVIDER_ONLINE

    /**
     * Liga a voz usando a chave e o provedor do Tecla: o LeanType já grava o áudio e manda para a
     * transcrição; aqui só configuramos para ele usar a mesma conta.
     */
    fun enableVoice(ctx: Context, tecla: TeclaSettings): Boolean {
        val key = tecla.apiKey
        if (key.isBlank() || !voiceSupported(tecla.providerId)) return false
        val svc = ProofreadService(ctx)
        when (tecla.providerId) {
            Providers.OPENAI.id -> {
                svc.setProvider(ProofreadService.AIProvider.OPENAI)
                svc.setHuggingFaceToken(key)
                svc.setHuggingFaceEndpoint(tecla.baseUrl.trimEnd('/') + "/chat/completions")
                svc.setVoiceHuggingFaceModel(OPENAI_VOICE_MODEL)
            }
            Providers.GROQ.id -> {
                svc.setProvider(ProofreadService.AIProvider.GROQ)
                svc.setGroqToken(key)
            }
            Providers.GEMINI.id -> {
                svc.setProvider(ProofreadService.AIProvider.GEMINI)
                svc.setApiKey(key)
            }
        }
        ctx.prefs().edit {
            putString(VoiceConstants.PREF_VOICE_PROVIDER, VoiceConstants.VOICE_PROVIDER_ONLINE)
            putBoolean(VoiceConstants.PREF_VOICE_ONLINE_ENABLED, true)
        }
        return true
    }

    fun disableVoice(ctx: Context) = ctx.prefs().edit {
        putString(VoiceConstants.PREF_VOICE_PROVIDER, VoiceConstants.VOICE_PROVIDER_NONE)
        putBoolean(VoiceConstants.PREF_VOICE_ONLINE_ENABLED, false)
    }

    /** Modelo de transcrição da OpenAI (o whisper-1 sai do ar em fevereiro de 2027). */
    const val OPENAI_VOICE_MODEL = "gpt-transcribe"
}
