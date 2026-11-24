/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import br.com.palora.tecla.core.ChatClient
import br.com.palora.tecla.core.ChatEvent
import br.com.palora.tecla.core.ChatMessage
import br.com.palora.tecla.core.Providers
import br.com.palora.tecla.core.TeclaException
import br.com.palora.tecla.core.TeclaHistory
import br.com.palora.tecla.core.TeclaSession
import br.com.palora.tecla.core.TeclaSettings
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tela inicial do app: configura a IA e mostra se o teclado já está ativo. */
class TeclaActivity : ComponentActivity() {
    private lateinit var settings: TeclaSettings
    private lateinit var history: TeclaHistory
    private lateinit var session: TeclaSession
    private var state by mutableStateOf<HomeState?>(null)
    private var modelsJob: Job? = null
    private var testJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = TeclaSettings(this)
        history = TeclaHistory(this)
        session = TeclaSession(this) { settings.sessionTimeout }
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
        refreshIme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Escolher o teclado abre um seletor por cima da tela, sem passar pelo onResume.
        if (hasFocus) refreshIme()
    }

    private fun load(): HomeState {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return HomeState(
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
            sessionCount = sessionCount(),
            sessionTimeout = settings.sessionTimeout,
        )
    }

    private fun update(block: (HomeState) -> HomeState) {
        state = state?.let(block)
    }

    private fun refreshIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        update {
            it.copy(
                imeEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm),
                imeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm),
                historyCount = history.all().size,
                sessionCount = sessionCount(),
            )
        }
    }

    private fun sessionCount(): Int = if (session.isActive()) session.count() else -1

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
        openKeyboardSettings = {
            startActivity(Intent(this, SettingsActivity::class.java))
        },
        setSessionTimeout = { minutes ->
            settings.sessionTimeout = minutes
            update { it.copy(sessionTimeout = minutes, sessionCount = sessionCount()) }
        },
        toggleSession = {
            if (session.isActive()) session.end() else session.start()
            update { it.copy(sessionCount = sessionCount()) }
        },
    )

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
                withContext(Dispatchers.Main) {
                    ChatClient.stream(
                        cfg,
                        listOf(ChatMessage("user", "Responda só com a palavra: ok")),
                    ).collect { ev -> if (ev is ChatEvent.Delta) reply.append(ev.text) }
                }
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
