/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.com.palora.tecla.core.ChatClient
import br.com.palora.tecla.core.ChatEvent
import br.com.palora.tecla.core.ChatMessage
import br.com.palora.tecla.core.Providers
import br.com.palora.tecla.core.TeclaException
import br.com.palora.tecla.core.TeclaHistory
import br.com.palora.tecla.core.TeclaSessions
import br.com.palora.tecla.core.TeclaSettings
import br.com.palora.tecla.keyboard.KeyboardPrefs
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tela inicial do app: IA, sessões e os ajustes do teclado que importam, em português simples. */
class TeclaActivity : ComponentActivity() {
    private lateinit var settings: TeclaSettings
    private lateinit var history: TeclaHistory
    private lateinit var sessions: TeclaSessions
    private var state by mutableStateOf<HomeState?>(null)
    private var modelsJob: Job? = null
    private var testJob: Job? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) turnVoiceOn() else Toast.makeText(this, "Sem o microfone não dá para digitar falando.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = TeclaSettings(this)
        history = TeclaHistory(this)
        sessions = TeclaSessions(this)
        KeyboardPrefs.applyFirstRunDefaults(this)
        state = load()
        val view = ComposeView(this)
        setContentView(view)
        view.setContent {
            TeclaTheme {
                state?.let { TeclaHome(it, actions) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Escolher o teclado abre um seletor por cima da tela, sem passar pelo onResume.
        if (hasFocus) refresh()
    }

    private fun load(): HomeState {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return keyboardState(
            HomeState(
                imeEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm),
                imeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm),
                providerId = settings.providerId,
                apiKey = settings.apiKey,
                model = settings.model,
                baseUrl = settings.baseUrl,
                reasoning = settings.reasoning,
                models = settings.cachedModels,
                actions = settings.actions,
                personal = settings.personal,
                historyEnabled = settings.historyEnabled,
                historyCount = history.all().size,
                preferClipboard = settings.preferClipboard,
                version = BuildConfig.VERSION_NAME,
            )
        )
    }

    /** Sessões e opções do teclado, lidas de novo (o teclado pode ter mudado algo por conta própria). */
    private fun keyboardState(base: HomeState): HomeState {
        val glide = when {
            base.glide is GlideState.Downloading || base.glide == GlideState.Restarting -> base.glide
            KeyboardPrefs.glideInstalled(this) -> GlideState.Installed
            !KeyboardPrefs.glideAvailableForDevice() -> GlideState.Unavailable
            base.glide is GlideState.Failed -> base.glide
            else -> GlideState.NotInstalled
        }
        return base.copy(
            sessions = sessions.all().map { SessionInfo(it.id, it.name, it.exchanges.size, it.updated) },
            activeSessionId = sessions.active()?.id,
            barEnabled = KeyboardPrefs.enabledKeys(this),
            barPinned = KeyboardPrefs.pinnedKeys(this),
            toggles = KeyboardPrefs.toggles.associate { it.pref to KeyboardPrefs.getToggle(this, it) },
            glide = glide,
            glideEnabled = KeyboardPrefs.glideEnabled(this),
            voiceEnabled = KeyboardPrefs.voiceEnabled(this),
        )
    }

    private fun update(block: (HomeState) -> HomeState) {
        state = state?.let(block)
    }

    private fun refresh() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        update {
            keyboardState(
                it.copy(
                    imeEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm),
                    imeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm),
                    historyCount = history.all().size,
                )
            )
        }
    }

    private fun reloadProvider() {
        modelsJob?.cancel()
        testJob?.cancel()
        update {
            it.copy(
                providerId = settings.providerId,
                apiKey = settings.apiKey,
                model = settings.model,
                baseUrl = settings.baseUrl,
                reasoning = settings.reasoning,
                models = settings.cachedModels,
                loadingModels = false,
                modelsError = null,
                test = TestState.Idle,
            )
        }
        // A voz usa a mesma conta: se estava ligada, acompanha a troca (ou desliga, se o provedor não transcreve).
        if (KeyboardPrefs.voiceEnabled(this)) {
            if (!KeyboardPrefs.enableVoice(this, settings)) KeyboardPrefs.disableVoice(this)
            refresh()
        }
    }

    private val actions = HomeActions(
        enableIme = {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        chooseIme = {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        },
        selectProvider = { id ->
            settings.providerId = id
            reloadProvider()
        },
        setApiKey = { key ->
            settings.apiKey = key
            update { it.copy(apiKey = key, test = TestState.Idle) }
            if (KeyboardPrefs.voiceEnabled(this)) KeyboardPrefs.enableVoice(this, settings)
        },
        setModel = { model ->
            settings.model = model
            update { it.copy(model = model, test = TestState.Idle) }
        },
        loadModels = { loadModels() },
        setBaseUrl = { url ->
            settings.baseUrl = url
            update { it.copy(baseUrl = url, test = TestState.Idle) }
        },
        setReasoning = { r ->
            settings.reasoning = r
            update { it.copy(reasoning = r, test = TestState.Idle) }
        },
        testConnection = { testConnection() },
        openUrl = { url ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        saveActions = { list ->
            settings.actions = list
            update { it.copy(actions = list) }
        },
        resetActions = {
            settings.resetActions()
            update { it.copy(actions = settings.actions) }
        },
        setPersonal = { text ->
            settings.personal = text
            update { it.copy(personal = text) }
        },
        setHistoryEnabled = { on ->
            settings.historyEnabled = on
            update { it.copy(historyEnabled = on) }
        },
        clearHistory = {
            history.clear()
            update { it.copy(historyCount = 0) }
        },
        setPreferClipboard = { on ->
            settings.preferClipboard = on
            update { it.copy(preferClipboard = on) }
        },
        openKeyboardScreen = { screen ->
            val intent = Intent(this, SettingsActivity::class.java)
            if (screen != null) intent.putExtra("screen", screen)
            startActivity(intent)
        },
        selectSession = { id ->
            sessions.select(id)
            refresh()
        },
        createSession = { name ->
            sessions.create(name.ifBlank { "Sessão ${sessions.all().size + 1}" })
            refresh()
        },
        renameSession = { id, name ->
            sessions.rename(id, name)
            refresh()
        },
        deleteSession = { id ->
            sessions.delete(id)
            refresh()
        },
        setBar = { enabled, pinned ->
            KeyboardPrefs.setKeys(this, enabled, pinned)
            refresh()
        },
        applyPreset = { preset ->
            KeyboardPrefs.applyPreset(this, preset)
            refresh()
        },
        setToggle = { pref, value ->
            KeyboardPrefs.toggles.firstOrNull { it.pref == pref }?.let { KeyboardPrefs.setToggle(this, it, value) }
            refresh()
        },
        installGlide = { installGlide() },
        setGlideEnabled = { on ->
            KeyboardPrefs.setGlideEnabled(this, on)
            refresh()
        },
        setVoice = { on ->
            if (!on) {
                KeyboardPrefs.disableVoice(this)
                refresh()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                turnVoiceOn()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )

    private fun turnVoiceOn() {
        if (!KeyboardPrefs.enableVoice(this, settings)) {
            Toast.makeText(this, "A voz precisa da chave da OpenAI, do Gemini ou do Groq.", Toast.LENGTH_LONG).show()
        } else {
            // O botão de falar entra na barra, se ainda não estiver.
            val bar = KeyboardPrefs.enabledKeys(this)
            if (helium314.keyboard.latin.utils.ToolbarKey.VOICE !in bar) {
                KeyboardPrefs.setKeys(this, bar + helium314.keyboard.latin.utils.ToolbarKey.VOICE, KeyboardPrefs.pinnedKeys(this))
            }
        }
        refresh()
    }

    private fun installGlide() {
        update { it.copy(glide = GlideState.Downloading(0)) }
        lifecycleScope.launch {
            val result = KeyboardPrefs.installGlide(this@TeclaActivity) { p ->
                runOnUiThread { update { it.copy(glide = GlideState.Downloading(p)) } }
            }
            result.fold(
                onSuccess = {
                    update { it.copy(glide = GlideState.Restarting) }
                    delay(1800)
                    // A biblioteca só carrega com o processo novo: reabre o app e encerra este processo.
                    val intent = Intent(this@TeclaActivity, TeclaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    Runtime.getRuntime().exit(0)
                },
                onFailure = { e ->
                    update { it.copy(glide = GlideState.Failed(e.message ?: "erro ao baixar")) }
                },
            )
        }
    }

    private fun loadModels() {
        modelsJob?.cancel()
        update { it.copy(loadingModels = true, modelsError = null) }
        val cfg = settings.snapshot()
        val provider = settings.provider
        modelsJob = lifecycleScope.launch {
            try {
                val list = ChatClient.listModels(cfg)
                settings.cachedModels = list
                // Primeira vez: se o modelo salvo não existe na conta, já escolhe um rápido.
                if (list.isNotEmpty() && settings.model !in list) {
                    Providers.recommend(provider, list)?.let { settings.model = it }
                }
                update { it.copy(models = list, model = settings.model, loadingModels = false) }
            } catch (e: TeclaException) {
                update { it.copy(loadingModels = false, modelsError = e.userMessage) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(loadingModels = false, modelsError = e.message ?: "Erro ao buscar os modelos.") }
            }
        }
    }

    private fun testConnection() {
        testJob?.cancel()
        update { it.copy(test = TestState.Running) }
        val cfg = settings.snapshot()
        testJob = lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                val reply = StringBuilder()
                ChatClient.stream(cfg, listOf(ChatMessage("user", "Responda só com a palavra: ok")))
                    .collect { ev -> if (ev is ChatEvent.Delta) reply.append(ev.text) }
                val ms = SystemClock.elapsedRealtime() - start
                update { it.copy(test = TestState.Ok(ms, reply.toString().trim())) }
            } catch (e: TeclaException) {
                update { it.copy(test = TestState.Fail(e.userMessage)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(test = TestState.Fail(e.message ?: "Erro desconhecido.")) }
            }
        }
    }
}
