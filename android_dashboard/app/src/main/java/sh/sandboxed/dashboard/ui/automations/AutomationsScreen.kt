package sh.sandboxed.dashboard.ui.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.Automation
import sh.sandboxed.dashboard.data.AutomationCommandSource
import sh.sandboxed.dashboard.data.AutomationRetryConfig
import sh.sandboxed.dashboard.data.AutomationStopPolicy
import sh.sandboxed.dashboard.data.AutomationTrigger
import sh.sandboxed.dashboard.data.CreateAutomationRequest
import sh.sandboxed.dashboard.data.UpdateAutomationRequest
import sh.sandboxed.dashboard.ui.TestTags
import sh.sandboxed.dashboard.ui.tag
import sh.sandboxed.dashboard.ui.components.ErrorBanner
import sh.sandboxed.dashboard.ui.components.GlassCard
import sh.sandboxed.dashboard.ui.theme.Palette

private data class AutoState(
    val items: List<Automation> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

private class AutomationsViewModel(private val container: AppContainer, private val missionId: String) : ViewModel() {
    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state.asStateFlow()
    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { container.api.listAutomations(missionId) }
                .onSuccess { list -> _state.update { it.copy(items = list, loading = false) } }
                .onFailure { e -> _state.update { it.copy(error = e.message, loading = false) } }
        }
    }

    fun create(req: CreateAutomationRequest) {
        viewModelScope.launch {
            runCatching { container.api.createAutomation(missionId, req) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun toggle(a: Automation) {
        viewModelScope.launch {
            runCatching { container.api.updateAutomation(a.id, UpdateAutomationRequest(active = !a.active)) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun delete(a: Automation) {
        viewModelScope.launch {
            runCatching { container.api.deleteAutomation(a.id) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

@Composable
fun AutomationsScreen(container: AppContainer, missionId: String, onBack: () -> Unit) {
    val vm = remember(missionId) { AutomationsViewModel(container, missionId) }
    val state by vm.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.TextPrimary) }
            Text("Automations", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { showCreate = true }, modifier = Modifier.tag(TestTags.AUTOMATIONS_ADD)) { Icon(Icons.Filled.Add, "Add", tint = Palette.Accent) }
        }
        state.error?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorBanner(it) } }
        if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Accent) }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.id }) { a -> AutomationRow(a, { vm.toggle(a) }, { vm.delete(a) }) }
            }
        }
    }

    if (showCreate) {
        CreateAutomationDialog(
            onCancel = { showCreate = false },
            onCreate = { req -> vm.create(req); showCreate = false }
        )
    }
}

@Composable
private fun AutomationRow(a: Automation, onToggle: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(triggerLabel(a.trigger), color = Palette.AccentLight, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = a.active,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Palette.Accent),
                )
            }
            when (a.commandSource.kind) {
                "inline" -> a.commandSource.content?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Palette.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                }
                "library" -> {
                    Spacer(Modifier.height(4.dp))
                    Text("library: ${a.commandSource.name}", color = Palette.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                "local_file" -> {
                    Spacer(Modifier.height(4.dp))
                    Text("file: ${a.commandSource.path}", color = Palette.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val policyBits = buildList {
                a.stopPolicy?.let { add(stopPolicyLabel(it)) }
                a.retryConfig?.takeIf { it.maxRetries > 0 }?.let { add("retry ×${it.maxRetries} (${it.retryDelaySeconds}s ×${it.backoffMultiplier})") }
                a.freshSession?.takeIf { it != "keep" }?.let { add("fresh: $it") }
                if (a.variables.isNotEmpty()) add("${a.variables.size} vars")
            }
            if (policyBits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(policyBits.joinToString(" · "), color = Palette.TextTertiary, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                a.lastTriggeredAt?.let {
                    Text("last: " + it.take(19).replace('T', ' '), color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = Palette.Error) }
            }
        }
    }
}

private fun stopPolicyLabel(p: AutomationStopPolicy): String = when (p.kind) {
    "never" -> "never stops"
    "when_failing_consecutively" -> "stop after ${p.count ?: 2} failures"
    "when_all_issues_closed_and_prs_merged" -> "stop when ${p.repo} is done"
    "after_first_fire" -> "one-shot"
    else -> p.kind
}

private fun triggerLabel(t: AutomationTrigger): String = when (t.kind) {
    "interval" -> "every ${t.seconds ?: 0}s"
    "agentFinished", "agent_finished" -> "on agent finish"
    "webhook" -> "on webhook"
    else -> t.kind
}

@Composable
private fun CreateAutomationDialog(onCancel: () -> Unit, onCreate: (CreateAutomationRequest) -> Unit) {
    var sourceKind by remember { mutableStateOf("inline") }
    var content by remember { mutableStateOf("") }
    var libraryName by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("") }
    var triggerKind by remember { mutableStateOf("interval") }
    var seconds by remember { mutableStateOf("60") }
    var variablesText by remember { mutableStateOf("") }
    var freshSession by remember { mutableStateOf("keep") }
    var stopKind by remember { mutableStateOf("when_failing_consecutively") }
    var stopCount by remember { mutableStateOf("2") }
    var stopRepo by remember { mutableStateOf("") }
    var maxRetries by remember { mutableStateOf("0") }
    var retryDelay by remember { mutableStateOf("60") }
    var backoff by remember { mutableStateOf("2.0") }

    val sourceValid = when (sourceKind) {
        "inline" -> content.isNotBlank()
        "library" -> libraryName.isNotBlank()
        else -> filePath.isNotBlank()
    }
    val triggerValid = triggerKind != "interval" || (seconds.toIntOrNull() ?: 0) > 0
    val stopValid = stopKind != "when_all_issues_closed_and_prs_merged" || stopRepo.contains('/')

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("New automation") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SectionLabel("Command") }
                item {
                    ChipRow(
                        options = listOf("inline" to "Inline", "library" to "Library", "local_file" to "File"),
                        selected = sourceKind,
                        onSelect = { sourceKind = it },
                        tagPrefix = "automations.new.source",
                    )
                }
                item {
                    when (sourceKind) {
                        "inline" -> OutlinedTextField(
                            value = content, onValueChange = { content = it },
                            label = { Text("Command (sent to agent)") },
                            modifier = Modifier.fillMaxWidth().tag(TestTags.AUTOMATIONS_NEW_COMMAND),
                            colors = autoFieldColors(), maxLines = 4,
                        )
                        "library" -> OutlinedTextField(
                            value = libraryName, onValueChange = { libraryName = it },
                            label = { Text("Library command name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().tag(TestTags.AUTOMATIONS_NEW_LIBRARY_NAME),
                            colors = autoFieldColors(),
                        )
                        else -> OutlinedTextField(
                            value = filePath, onValueChange = { filePath = it },
                            label = { Text("File path (relative to workspace)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().tag(TestTags.AUTOMATIONS_NEW_FILE_PATH),
                            colors = autoFieldColors(),
                        )
                    }
                }

                item { SectionLabel("Trigger") }
                item {
                    ChipRow(
                        options = listOf("interval" to "Interval", "agent_finished" to "On finish", "webhook" to "Webhook"),
                        selected = triggerKind,
                        onSelect = { triggerKind = it },
                        tagPrefix = "automations.new.trigger",
                    )
                }
                if (triggerKind == "interval") item {
                    OutlinedTextField(
                        value = seconds, onValueChange = { seconds = it.filter { c -> c.isDigit() } },
                        label = { Text("Seconds") }, singleLine = true,
                        modifier = Modifier.tag(TestTags.AUTOMATIONS_NEW_INTERVAL_SECS),
                        colors = autoFieldColors(),
                    )
                }

                item { SectionLabel("Variables") }
                item {
                    OutlinedTextField(
                        value = variablesText, onValueChange = { variablesText = it },
                        label = { Text("One per line: name=value") },
                        modifier = Modifier.fillMaxWidth().tag(TestTags.AUTOMATIONS_NEW_VARIABLES),
                        colors = autoFieldColors(), maxLines = 4,
                    )
                }

                item { SectionLabel("Session") }
                item {
                    ChipRow(
                        options = listOf("keep" to "Keep", "always" to "Fresh", "switch" to "Switch"),
                        selected = freshSession,
                        onSelect = { freshSession = it },
                        tagPrefix = "automations.new.fresh",
                    )
                }

                item { SectionLabel("Stop policy") }
                item {
                    ChipRow(
                        options = listOf(
                            "never" to "Never",
                            "when_failing_consecutively" to "On failures",
                            "when_all_issues_closed_and_prs_merged" to "Repo done",
                        ),
                        selected = stopKind,
                        onSelect = { stopKind = it },
                        tagPrefix = "automations.new.stop",
                    )
                }
                when (stopKind) {
                    "when_failing_consecutively" -> item {
                        OutlinedTextField(
                            value = stopCount, onValueChange = { stopCount = it.filter { c -> c.isDigit() } },
                            label = { Text("Consecutive failures") }, singleLine = true,
                            modifier = Modifier.tag(TestTags.AUTOMATIONS_NEW_STOP_COUNT),
                            colors = autoFieldColors(),
                        )
                    }
                    "when_all_issues_closed_and_prs_merged" -> item {
                        OutlinedTextField(
                            value = stopRepo, onValueChange = { stopRepo = it },
                            label = { Text("GitHub repo (owner/repo)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().tag(TestTags.AUTOMATIONS_NEW_STOP_REPO),
                            colors = autoFieldColors(),
                        )
                    }
                }

                item { SectionLabel("Retry") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = maxRetries, onValueChange = { maxRetries = it.filter { c -> c.isDigit() } },
                            label = { Text("Retries") }, singleLine = true,
                            modifier = Modifier.weight(1f).tag(TestTags.AUTOMATIONS_NEW_RETRIES),
                            colors = autoFieldColors(),
                        )
                        OutlinedTextField(
                            value = retryDelay, onValueChange = { retryDelay = it.filter { c -> c.isDigit() } },
                            label = { Text("Delay s") }, singleLine = true,
                            modifier = Modifier.weight(1f).tag(TestTags.AUTOMATIONS_NEW_RETRY_DELAY),
                            colors = autoFieldColors(),
                        )
                        OutlinedTextField(
                            value = backoff, onValueChange = { backoff = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Backoff ×") }, singleLine = true,
                            modifier = Modifier.weight(1f).tag(TestTags.AUTOMATIONS_NEW_BACKOFF),
                            colors = autoFieldColors(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val variables = variablesText.lines()
                        .mapNotNull { line ->
                            val idx = line.indexOf('=')
                            if (idx <= 0) null else line.take(idx).trim() to line.drop(idx + 1).trim()
                        }
                        .toMap()
                    val retries = maxRetries.toIntOrNull() ?: 0
                    onCreate(
                        CreateAutomationRequest(
                            commandSource = when (sourceKind) {
                                "library" -> AutomationCommandSource(kind = "library", name = libraryName.trim())
                                "local_file" -> AutomationCommandSource(kind = "local_file", path = filePath.trim())
                                else -> AutomationCommandSource(kind = "inline", content = content)
                            },
                            trigger = AutomationTrigger(kind = triggerKind, seconds = seconds.toIntOrNull().takeIf { triggerKind == "interval" }),
                            variables = variables,
                            active = true,
                            stopPolicy = when (stopKind) {
                                "when_failing_consecutively" -> AutomationStopPolicy(kind = stopKind, count = stopCount.toIntOrNull() ?: 2)
                                "when_all_issues_closed_and_prs_merged" -> AutomationStopPolicy(kind = stopKind, repo = stopRepo.trim())
                                else -> AutomationStopPolicy(kind = "never")
                            },
                            freshSession = freshSession.takeIf { it != "keep" },
                            retryConfig = if (retries > 0) {
                                AutomationRetryConfig(
                                    maxRetries = retries,
                                    retryDelaySeconds = retryDelay.toIntOrNull() ?: 60,
                                    backoffMultiplier = backoff.toDoubleOrNull() ?: 2.0,
                                )
                            } else null,
                        )
                    )
                },
                enabled = sourceValid && triggerValid && stopValid,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                modifier = Modifier.tag(TestTags.AUTOMATIONS_NEW_CREATE),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.tag(TestTags.AUTOMATIONS_NEW_CANCEL)) { Text("Cancel") } },
        containerColor = Palette.Card,
    )
}

@Composable
private fun SectionLabel(title: String) {
    Text(title.uppercase(), color = Palette.TextTertiary, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    tagPrefix: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, label) ->
            FilterChip(
                selected = selected == k, onClick = { onSelect(k) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.tag("$tagPrefix.$k"),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Palette.Card,
                    selectedContainerColor = Palette.Accent.copy(alpha = 0.18f),
                    labelColor = Palette.TextSecondary,
                    selectedLabelColor = Palette.Accent,
                ),
            )
        }
    }
}

@Composable
private fun autoFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Palette.Card,
    unfocusedContainerColor = Palette.Card,
    focusedTextColor = Palette.TextPrimary,
    unfocusedTextColor = Palette.TextPrimary,
    cursorColor = Palette.Accent,
)
