/*
 * Tecla — teclado com IA (fork pessoal do LeanType)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package br.com.palora.tecla.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import br.com.palora.tecla.core.Providers
import br.com.palora.tecla.core.TeclaAction
import br.com.palora.tecla.core.TeclaIcons
import br.com.palora.tecla.panel.TeclaAiPanelView
import helium314.keyboard.latin.R

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val ms: Long, val reply: String) : TestState
    data class Fail(val message: String) : TestState
}

data class HomeState(
    val imeEnabled: Boolean,
    val imeCurrent: Boolean,
    val providerId: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val reasoning: String,
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
    val test: TestState = TestState.Idle,
    val actions: List<TeclaAction>,
    val personal: String,
    val historyEnabled: Boolean,
    val historyCount: Int,
    val preferClipboard: Boolean,
    val version: String,
    /** -1 = sem sessão de contexto ligada; senão, quantas trocas ela tem. */
    val sessionCount: Int = -1,
    val sessionTimeout: Int = 30,
    val sessionName: String = br.com.palora.tecla.core.TeclaSession.DEFAULT_NAME,
) {
    val provider get() = Providers.byId(providerId)
    val aiReady get() = baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || !provider.needsKey)
    val allReady get() = imeEnabled && imeCurrent && aiReady
}

class HomeActions(
    val enableIme: () -> Unit = {},
    val chooseIme: () -> Unit = {},
    val selectProvider: (String) -> Unit = {},
    val setApiKey: (String) -> Unit = {},
    val setModel: (String) -> Unit = {},
    val loadModels: () -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setReasoning: (String) -> Unit = {},
    val testConnection: () -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val saveActions: (List<TeclaAction>) -> Unit = {},
    val resetActions: () -> Unit = {},
    val setPersonal: (String) -> Unit = {},
    val setHistoryEnabled: (Boolean) -> Unit = {},
    val clearHistory: () -> Unit = {},
    val setPreferClipboard: (Boolean) -> Unit = {},
    val openKeyboardSettings: () -> Unit = {},
    val setSessionTimeout: (Int) -> Unit = {},
    val toggleSession: () -> Unit = {},
    val renameSession: (String) -> Unit = {},
)

@Composable
fun TeclaHome(state: HomeState, actions: HomeActions, initialModelPicker: Boolean = false, initialEditAction: Int? = null) {
    val c = LocalTecla.current
    var showModels by remember { mutableStateOf(initialModelPicker) }
    var editing by remember { mutableStateOf(initialEditAction) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp, bottom = 36.dp)
        ) {
            Hero()
            Spacer(Modifier.height(14.dp))
            if (state.allReady) ReadyBanner() else SetupCard(state, actions)

            SectionTitle("Inteligência artificial")
            AiCard(state, actions, openModels = { showModels = true; if (state.models.isEmpty()) actions.loadModels() })

            SectionTitle("Sessão de contexto")
            SessionCard(state, actions)

            SectionTitle("Ações rápidas")
            ActionsCard(state.actions, onEdit = { editing = it }, onAdd = { editing = -1 }, onMove = { from, to ->
                val list = state.actions.toMutableList()
                if (to in list.indices) {
                    val item = list.removeAt(from)
                    list.add(to, item)
                    actions.saveActions(list)
                }
            }, onReset = actions.resetActions)

            SectionTitle("Sobre você")
            PersonalCard(state.personal, actions.setPersonal)

            SectionTitle("Experimente")
            TryCard()

            SectionTitle("Privacidade")
            PrivacyCard(state, actions)

            SectionTitle("Teclado")
            Card {
                LinkRow(
                    icon = R.drawable.tecla_ic_keyboard,
                    title = "Configurações do teclado",
                    subtitle = "Temas, idiomas, layout, digitação por gesto e a barra de ferramentas.",
                    onClick = actions.openKeyboardSettings,
                )
            }

            Text(
                "Tecla ${state.version} · feito a partir do LeanType e do HeliBoard (GPL-3.0). " +
                    "Fontes Inter e Space Grotesk (SIL OFL 1.1).",
                style = TeclaType.small, color = c.muted,
                modifier = Modifier.padding(top = 28.dp, start = 4.dp, end = 4.dp),
            )
        }
    }

    if (showModels) {
        ModelPicker(state, actions, onDismiss = { showModels = false })
    }
    editing?.let { index ->
        val existing = state.actions.getOrNull(index)
        ActionEditor(
            initial = existing,
            onDismiss = { editing = null },
            onSave = { a ->
                val list = state.actions.toMutableList()
                if (existing != null) list[index] = a else list += a
                actions.saveActions(list)
                editing = null
            },
            onDelete = if (existing != null) {
                {
                    actions.saveActions(state.actions.filterIndexed { i, _ -> i != index })
                    editing = null
                }
            } else null,
        )
    }
}

// ------------------------------------------------------------------------- topo

@Composable
private fun Hero() {
    val c = LocalTecla.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.34f else 0.20f), c.surface, c.surface)))
            .border(1.dp, c.outline, RoundedCornerShape(30.dp))
            .padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text("Tecla", style = TeclaType.hero, color = c.text)
                Icon(
                    painterResource(R.drawable.tecla_ic_spark), contentDescription = null, tint = c.spark,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp).size(26.dp),
                )
            }
            Text(
                "Responde, traduz e corrige com IA, direto na barra do teclado, em qualquer aplicativo.",
                style = TeclaType.body, color = c.muted, modifier = Modifier.padding(top = 8.dp, end = 12.dp),
            )
            Row(Modifier.padding(top = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MockChip("Responder", "answer")
                MockChip("Traduzir", "translate")
                MockChip("Corrigir", "check")
                MockChip("Resumir", "list")
            }
        }
    }
}

@Composable
private fun MockChip(label: String, icon: String) {
    val c = LocalTecla.current
    Row(
        Modifier.clip(CircleShape).background(c.background.copy(alpha = 0.55f)).border(1.dp, c.outline, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(TeclaAiPanelView.actionIcon(icon)), null, tint = c.accent, modifier = Modifier.size(16.dp))
        Text(label, style = TeclaType.label, color = c.text, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ReadyBanner() {
    val c = LocalTecla.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.success.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.tecla_ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(
            "Tudo pronto. Em qualquer campo de texto, toque no ✦ da barra do teclado.",
            style = TeclaType.bodyStrong, color = c.text, modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun SetupCard(state: HomeState, actions: HomeActions) {
    val c = LocalTecla.current
    Card {
        Text("Falta pouco", style = TeclaType.title, color = c.text)
        Text("Três passos e o Tecla já funciona em qualquer app.", style = TeclaType.small, color = c.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Step(1, "Ativar o Tecla", "Ligue o Tecla na lista de teclados do Android.", state.imeEnabled, "Ativar", actions.enableIme)
        Step(2, "Usar como teclado", "Escolha o Tecla como teclado atual.", state.imeEnabled && state.imeCurrent, "Escolher",
            if (state.imeEnabled) actions.chooseIme else null)
        Step(3, "Conectar a IA", "Escolha o provedor e cole a chave da API, logo abaixo.", state.aiReady, null, null, last = true)
    }
}

@Composable
private fun Step(n: Int, title: String, subtitle: String, done: Boolean, button: String?, onClick: (() -> Unit)?, last: Boolean = false) {
    val c = LocalTecla.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (done) c.success else c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(painterResource(R.drawable.tecla_ic_check), null, tint = Color.White, modifier = Modifier.size(18.dp))
            else Text("$n", style = TeclaType.label, color = c.text)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(title, style = TeclaType.bodyStrong, color = if (done) c.muted else c.text)
            Text(subtitle, style = TeclaType.small, color = c.muted)
        }
        if (!done && button != null && onClick != null) {
            PillButton(button, primary = true, onClick = onClick)
        }
    }
    if (!last) Box(Modifier.padding(start = 14.dp).width(2.dp).height(6.dp).background(c.outline))
}

// ------------------------------------------------------------------------- IA

@Composable
private fun AiCard(state: HomeState, actions: HomeActions, openModels: () -> Unit) {
    val c = LocalTecla.current
    var advanced by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    Card {
        FieldLabel("Provedor")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Providers.all.forEach { p ->
                val selected = p.id == state.providerId
                Text(
                    p.label,
                    style = TeclaType.label,
                    color = if (selected) c.onAccent else c.text,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) c.accent else c.surfaceHigh)
                        .clickable { actions.selectProvider(p.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        val p = state.provider
        if (p.needsKey || p.id == Providers.CUSTOM.id) {
            FieldLabel("Chave da API", top = 18.dp)
            TeclaField(
                value = state.apiKey,
                onChange = actions.setApiKey,
                placeholder = p.keyHint,
                mono = true,
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password,
                trailing = {
                    Text(
                        if (showKey) "Ocultar" else "Mostrar",
                        style = TeclaType.label, color = c.accent,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showKey = !showKey }.padding(8.dp),
                    )
                },
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.tecla_ic_shield), null, tint = c.muted, modifier = Modifier.size(15.dp))
                Text("Fica cifrada no aparelho.", style = TeclaType.small, color = c.muted, modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                p.keyUrl?.let { url ->
                    Text(
                        "Criar chave", style = TeclaType.label, color = c.accent,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { actions.openUrl(url) }.padding(6.dp),
                    )
                }
            }
        }

        FieldLabel("Modelo", top = 18.dp)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh)
                .clickable(onClick = openModels).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.model.ifBlank { "Nenhum escolhido" },
                style = TeclaType.mono, color = if (state.model.isBlank()) c.muted else c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            PillButton("Trocar", primary = false, onClick = openModels)
        }

        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                if (state.test is TestState.Running) "Testando…" else "Testar conexão",
                primary = true,
                enabled = state.aiReady && state.test !is TestState.Running,
                onClick = actions.testConnection,
            )
            Spacer(Modifier.width(12.dp))
            when (val t = state.test) {
                is TestState.Ok -> StatusDot(c.success, "Funcionou · ${"%.1f".format(t.ms / 1000.0).replace('.', ',')} s")
                is TestState.Fail -> StatusDot(c.error, "Não funcionou")
                is TestState.Running -> CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                TestState.Idle -> if (!state.aiReady) Text("Preencha a chave e o modelo.", style = TeclaType.small, color = c.muted)
            }
        }
        (state.test as? TestState.Fail)?.let {
            Text(it.message, style = TeclaType.small, color = c.error, modifier = Modifier.padding(top = 10.dp))
        }

        Row(
            Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { advanced = !advanced }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.tecla_ic_tune), null, tint = c.muted, modifier = Modifier.size(18.dp))
            Text(if (advanced) "Ocultar avançado" else "Avançado", style = TeclaType.label, color = c.muted, modifier = Modifier.padding(start = 8.dp))
        }
        AnimatedVisibility(advanced) {
            Column {
                FieldLabel("URL base", top = 10.dp)
                TeclaField(value = state.baseUrl, onChange = actions.setBaseUrl, placeholder = "https://…/v1", mono = true, keyboardType = KeyboardType.Uri)
                FieldLabel("Raciocínio (reasoning_effort)", top = 16.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Providers.reasoningOptions.forEach { (value, label) ->
                        val selected = value == state.reasoning
                        Text(
                            label, style = TeclaType.label,
                            color = if (selected) c.onAccentSoft else c.muted,
                            modifier = Modifier.clip(CircleShape)
                                .background(if (selected) c.accentSoft else c.surfaceHigh)
                                .clickable { actions.setReasoning(value) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Text(
                    "Menos raciocínio = resposta mais rápida. Se o provedor reclamar desse campo, use \"Não enviar\".",
                    style = TeclaType.small, color = c.muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = TeclaType.label, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ModelPicker(state: HomeState, actions: HomeActions, onDismiss: () -> Unit) {
    val c = LocalTecla.current
    var query by remember { mutableStateOf("") }
    val recommended = Providers.recommend(state.provider, state.models)
    val filtered = state.models.filter { it.contains(query.trim(), ignoreCase = true) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                .border(1.dp, c.outline, RoundedCornerShape(28.dp)).padding(20.dp)
        ) {
            Text("Escolher modelo", style = TeclaType.title, color = c.text)
            Text(state.provider.label, style = TeclaType.small, color = c.muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            TeclaField(value = query, onChange = { query = it }, placeholder = "Buscar ou digitar o nome…", mono = true)
            Spacer(Modifier.height(10.dp))
            when {
                state.loadingModels -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.5.dp)
                }
                state.modelsError != null -> Text(state.modelsError, style = TeclaType.small, color = c.error, modifier = Modifier.padding(vertical = 10.dp))
                state.models.isEmpty() -> Text("Toque em Atualizar para buscar os modelos da sua conta.", style = TeclaType.small, color = c.muted, modifier = Modifier.padding(vertical = 10.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(filtered, key = { it }) { m ->
                    val selected = m == state.model
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (selected) c.accentSoft else Color.Transparent)
                            .clickable { actions.setModel(m); onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m, style = TeclaType.mono, color = if (selected) c.onAccentSoft else c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m == recommended) {
                            Text(
                                "rápido", style = TeclaType.small, color = c.spark,
                                modifier = Modifier.clip(CircleShape).background(c.spark.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        if (selected) Icon(painterResource(R.drawable.tecla_ic_check), null, tint = c.onAccentSoft, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
            val typed = query.trim()
            if (typed.isNotEmpty() && typed !in state.models) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceHigh)
                        .clickable { actions.setModel(typed); onDismiss() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.tecla_ic_add), null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Text("Usar \"$typed\"", style = TeclaType.label, color = c.text, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton("Atualizar", primary = false, enabled = !state.loadingModels, onClick = actions.loadModels)
                Spacer(Modifier.width(8.dp))
                PillButton("Fechar", primary = true, onClick = onDismiss)
            }
        }
    }
}

// ------------------------------------------------------------------------- ações

@Composable
private fun ActionsCard(
    list: List<TeclaAction>,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onReset: () -> Unit,
) {
    val c = LocalTecla.current
    Card(padding = 8.dp) {
        Text(
            "Aparecem como atalhos no painel, nesta ordem. Toque para editar a instrução.",
            style = TeclaType.small, color = c.muted, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        )
        list.forEachIndexed { i, a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onEdit(i) }.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(painterResource(TeclaAiPanelView.actionIcon(a.icon)), null, tint = c.onAccentSoft, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(a.label, style = TeclaType.bodyStrong, color = c.text)
                    Text(a.instruction, style = TeclaType.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                SmallIcon(R.drawable.tecla_ic_arrow_up_small, "Subir", enabled = i > 0) { onMove(i, i - 1) }
                SmallIcon(R.drawable.tecla_ic_chevron_down, "Descer", enabled = i < list.lastIndex) { onMove(i, i + 1) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Nova ação", primary = false, icon = R.drawable.tecla_ic_add, onClick = onAdd)
            Spacer(Modifier.weight(1f))
            Text(
                "Restaurar padrão", style = TeclaType.label, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onReset).padding(8.dp),
            )
        }
    }
}

@Composable
private fun SmallIcon(res: Int, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalTecla.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(res), desc, tint = if (enabled) c.muted else c.outline, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ActionEditor(initial: TeclaAction?, onDismiss: () -> Unit, onSave: (TeclaAction) -> Unit, onDelete: (() -> Unit)?) {
    val c = LocalTecla.current
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var instruction by remember { mutableStateOf(initial?.instruction.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon ?: "spark") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                .border(1.dp, c.outline, RoundedCornerShape(28.dp)).padding(20.dp)
        ) {
            Text(if (initial == null) "Nova ação" else "Editar ação", style = TeclaType.title, color = c.text)
            FieldLabel("Nome no botão", top = 14.dp)
            TeclaField(value = label, onChange = { label = it.take(24) }, placeholder = "Ex.: Emoji")
            FieldLabel("Instrução para a IA", top = 14.dp)
            TeclaField(
                value = instruction, onChange = { instruction = it }, singleLine = false, minLines = 4,
                placeholder = "Ex.: Reescreva o texto com emojis que combinem, sem exagero.",
            )
            FieldLabel("Ícone", top = 14.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TeclaIcons.names.forEach { name ->
                    val selected = name == icon
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (selected) c.accent else c.surfaceHigh).clickable { icon = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(TeclaAiPanelView.actionIcon(name)), name, tint = if (selected) c.onAccent else c.muted, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    Text(
                        "Excluir", style = TeclaType.label, color = c.error,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDelete).padding(8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                PillButton("Cancelar", primary = false, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                PillButton("Salvar", primary = true, enabled = label.isNotBlank() && instruction.isNotBlank()) {
                    onSave(TeclaAction(initial?.id ?: ("a" + System.currentTimeMillis()), label.trim(), instruction.trim(), icon))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------- outras seções

@Composable
private fun SessionCard(state: HomeState, actions: HomeActions) {
    val c = LocalTecla.current
    val active = state.sessionCount >= 0
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (active) c.accentSoft else c.surfaceHigh), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.tecla_ic_session), null, tint = if (active) c.onAccentSoft else c.muted, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    when {
                        !active -> "Desligada"
                        state.sessionCount == 0 -> "${state.sessionName} · sem trocas"
                        state.sessionCount == 1 -> "${state.sessionName} · 1 troca"
                        else -> "${state.sessionName} · ${state.sessionCount} trocas"
                    },
                    style = TeclaType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (active) "Os próximos pedidos levam o que já foi falado." else "Cada pedido começa do zero.",
                    style = TeclaType.small, color = c.muted,
                )
            }
            PillButton(if (active) "Encerrar" else "Iniciar", primary = !active, onClick = actions.toggleSession)
        }
        if (active) {
            FieldLabel("Nome (até ${br.com.palora.tecla.core.TeclaSession.MAX_NAME} caracteres)", top = 16.dp)
            var name by remember { mutableStateOf(state.sessionName) }
            TeclaField(
                value = name,
                onChange = {
                    name = it.take(br.com.palora.tecla.core.TeclaSession.MAX_NAME)
                    actions.renameSession(name)
                },
                placeholder = br.com.palora.tecla.core.TeclaSession.DEFAULT_NAME,
                trailing = {
                    Text(
                        "${name.length}/${br.com.palora.tecla.core.TeclaSession.MAX_NAME}",
                        style = TeclaType.small, color = c.muted, modifier = Modifier.padding(end = 10.dp),
                    )
                },
            )
        }
        Text(
            "Com a sessão ligada, a IA lembra das últimas trocas, mesmo em outro app: dá para pedir \"responde ele\" " +
                "ou \"traduz de volta\" sem copiar de novo. No painel do teclado, o botão de balões liga a sessão e um toque no selo dela renomeia ou encerra.",
            style = TeclaType.small, color = c.muted, modifier = Modifier.padding(top = 14.dp),
        )
        FieldLabel("Encerrar sozinha depois de", top = 16.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            br.com.palora.tecla.core.TeclaSession.timeoutOptions.forEach { (minutes, label) ->
                val selected = minutes == state.sessionTimeout
                Text(
                    label, style = TeclaType.label,
                    color = if (selected) c.onAccentSoft else c.muted,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (selected) c.accentSoft else c.surfaceHigh)
                        .clickable { actions.setSessionTimeout(minutes) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            "Vão junto as trocas mais recentes, até cerca de 16 mil caracteres. Quanto maior a sessão, mais tokens por pedido.",
            style = TeclaType.small, color = c.muted, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun PersonalCard(value: String, onChange: (String) -> Unit) {
    val c = LocalTecla.current
    Card {
        Text(
            "Vai junto em todo pedido, para a IA escrever do seu jeito. Não coloque senhas nem dados sensíveis.",
            style = TeclaType.small, color = c.muted, modifier = Modifier.padding(bottom = 10.dp),
        )
        TeclaField(
            value = value, onChange = onChange, singleLine = false, minLines = 3,
            placeholder = "Ex.: Me chamo Ana. Escrevo de um jeito informal, sem emoji, e trabalho com vendas.",
        )
    }
}

@Composable
private fun TryCard() {
    val c = LocalTecla.current
    var text by remember { mutableStateOf("") }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(painterResource(R.drawable.tecla_ic_spark), null, tint = c.spark, modifier = Modifier.size(18.dp))
            Text("Toque no campo e use o ✦ na barra do teclado.", style = TeclaType.small, color = c.muted, modifier = Modifier.padding(start = 8.dp))
        }
        TeclaField(value = text, onChange = { text = it }, singleLine = false, minLines = 3, placeholder = "Escreva algo aqui para testar…")
    }
}

@Composable
private fun PrivacyCard(state: HomeState, actions: HomeActions) {
    val c = LocalTecla.current
    Card {
        ToggleRow("Guardar histórico", "As últimas ${br.com.palora.tecla.core.TeclaHistory.MAX} respostas, só neste celular.", state.historyEnabled, actions.setHistoryEnabled)
        ToggleRow("Começar pelo texto copiado", "Abre o painel já usando o que você copiou (se for recente).", state.preferClipboard, actions.setPreferClipboard)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.historyCount == 0) "Histórico vazio" else "${state.historyCount} no histórico",
                style = TeclaType.small, color = c.muted, modifier = Modifier.weight(1f),
            )
            if (state.historyCount > 0) PillButton("Apagar histórico", primary = false, icon = R.drawable.tecla_ic_delete, onClick = actions.clearHistory)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(c.outline))
        Text(
            "O texto só sai do celular quando você toca numa ação ou envia um pedido, e vai direto para o provedor escolhido. " +
                "A IA fica desligada em campos de senha.",
            style = TeclaType.small, color = c.muted,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalTecla.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = TeclaType.bodyStrong, color = c.text)
            Text(subtitle, style = TeclaType.small, color = c.muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.outline,
            ),
        )
    }
}

@Composable
private fun LinkRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val c = LocalTecla.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = TeclaType.bodyStrong, color = c.text)
            Text(subtitle, style = TeclaType.small, color = c.muted)
        }
        Icon(painterResource(R.drawable.tecla_ic_open), null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

// ------------------------------------------------------------------------- peças

@Composable
private fun SectionTitle(text: String) {
    val c = LocalTecla.current
    Text(text.uppercase(), style = TeclaType.section, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 26.dp, bottom = 10.dp))
}

@Composable
private fun Card(padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalTecla.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface)
            .border(BorderStroke(1.dp, c.outline), RoundedCornerShape(24.dp)).padding(padding),
        content = content,
    )
}

@Composable
private fun FieldLabel(text: String, top: Dp = 0.dp) {
    val c = LocalTecla.current
    Text(text, style = TeclaType.label, color = c.text, modifier = Modifier.padding(top = top, bottom = 8.dp))
}

@Composable
private fun PillButton(label: String, primary: Boolean, enabled: Boolean = true, icon: Int? = null, onClick: () -> Unit) {
    val c = LocalTecla.current
    val bg = when {
        !enabled -> c.surfaceHigh
        primary -> c.accent
        else -> c.surfaceHigh
    }
    val fg = when {
        !enabled -> c.muted
        primary -> c.onAccent
        else -> c.text
    }
    Row(
        Modifier.clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = fg, modifier = Modifier.padding(end = 6.dp).size(17.dp))
        Text(label, style = TeclaType.label, color = fg)
    }
}

@Composable
private fun TeclaField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    mono: Boolean = false,
    visual: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalTecla.current
    val style: TextStyle = (if (mono) TeclaType.mono else TeclaType.body).copy(color = c.text)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = style,
        cursorBrush = SolidColor(c.accent),
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = !mono),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh)
                    .padding(start = 16.dp, end = if (trailing != null) 6.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.muted))
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}
