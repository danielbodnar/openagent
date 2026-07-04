package sh.sandboxed.dashboard.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.Mission
import sh.sandboxed.dashboard.data.MissionMomentSearchResult
import sh.sandboxed.dashboard.data.StoredEvent
import sh.sandboxed.dashboard.ui.TestTags
import sh.sandboxed.dashboard.ui.tag
import sh.sandboxed.dashboard.ui.components.ErrorBanner
import sh.sandboxed.dashboard.ui.components.GlassCard
import sh.sandboxed.dashboard.ui.components.StatusBadge
import sh.sandboxed.dashboard.ui.theme.Palette
import sh.sandboxed.dashboard.util.boundedForText

private const val EVENT_PAGE = 100

private data class MissionDetailState(
    val mission: Mission? = null,
    val events: List<StoredEvent> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val moments: List<MissionMomentSearchResult> = emptyList(),
)

private class MissionDetailViewModel(
    private val container: AppContainer,
    private val missionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(MissionDetailState())
    val state: StateFlow<MissionDetailState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val mission = runCatching { container.api.getMission(missionId) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
                .getOrNull()
            val events = runCatching { container.api.missionEvents(missionId, limit = EVENT_PAGE) }
                .getOrNull()?.first.orEmpty()
            _state.update {
                it.copy(
                    mission = mission ?: it.mission,
                    events = events,
                    hasMore = events.size == EVENT_PAGE,
                    loading = false,
                )
            }
        }
    }

    /// Events are fetched oldest-first; each page resumes after the highest
    /// sequence already loaded.
    fun loadMore() {
        val last = _state.value.events.lastOrNull()?.sequence ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { container.api.missionEvents(missionId, sinceSeq = last, limit = EVENT_PAGE) }
                .onSuccess { (events, _) ->
                    _state.update {
                        it.copy(
                            events = it.events + events.filter { e -> e.sequence > last },
                            hasMore = events.size == EVENT_PAGE,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message, loadingMore = false) } }
        }
    }

    private var searchGen = 0

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        // Generation fence: each keystroke supersedes in-flight searches, so
        // a slow older response can't overwrite a newer one's results.
        val gen = ++searchGen
        if (q.isBlank()) {
            _state.update { it.copy(moments = emptyList(), searching = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val moments = runCatching { container.api.searchMoments(q, missionId = missionId) }.getOrNull().orEmpty()
            if (gen == searchGen) {
                _state.update { it.copy(moments = moments, searching = false) }
            }
        }
    }
}

@Composable
fun MissionDetailScreen(
    container: AppContainer,
    missionId: String,
    onBack: () -> Unit,
    onOpenControl: (String) -> Unit,
) {
    val vm = remember(missionId) { MissionDetailViewModel(container, missionId) }
    val state by vm.state.collectAsState()
    val mission = state.mission

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.tag(TestTags.MISSION_DETAIL_BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.TextPrimary)
            }
            Text(
                mission?.title ?: mission?.shortDescription ?: missionId.take(8),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            mission?.status?.let { StatusBadge(it) }
        }
        state.error?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorBanner(it) } }

        if (state.loading && mission == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Accent) }
            return@Column
        }

        // When a search query is active, events are filtered client-side and
        // semantic "moments" from the server are appended below.
        val visibleEvents = if (state.query.isBlank()) state.events else {
            val q = state.query.lowercase()
            state.events.filter {
                it.content.lowercase().contains(q) ||
                    it.eventType.lowercase().contains(q) ||
                    (it.toolName ?: "").lowercase().contains(q)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                mission?.let { m ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            m.shortDescription?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            MetaField("ID", m.id)
                            m.workspaceName?.let { MetaField("Workspace", it) }
                            m.backend?.let { MetaField("Backend", it) }
                            m.agent?.let { MetaField("Agent", it) }
                            m.modelOverride?.let { MetaField("Model", it) }
                            m.parentMissionId?.let { MetaField("Parent mission", it) }
                            if (m.createdAt.isNotBlank()) MetaField("Created", m.createdAt.take(19).replace('T', ' '))
                            if (m.updatedAt.isNotBlank()) MetaField("Updated", m.updatedAt.take(19).replace('T', ' '))
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = { onOpenControl(m.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                                modifier = Modifier.tag(TestTags.MISSION_DETAIL_OPEN_CONTROL),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Open in Control")
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.query, onValueChange = { vm.setQuery(it) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().tag(TestTags.MISSION_DETAIL_SEARCH),
                    placeholder = { Text("Search events…", color = Palette.TextMuted) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextTertiary) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Card,
                        unfocusedContainerColor = Palette.Card,
                        cursorColor = Palette.Accent,
                        focusedTextColor = Palette.TextPrimary,
                        unfocusedTextColor = Palette.TextPrimary,
                    ),
                )
            }

            item {
                Text(
                    "TIMELINE · ${visibleEvents.size}${if (state.query.isBlank() && state.hasMore) "+" else ""} events",
                    color = Palette.TextTertiary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            items(visibleEvents, key = { it.sequence }) { ev -> EventRow(ev) }

            if (state.query.isBlank() && state.hasMore) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = { vm.loadMore() },
                        enabled = !state.loadingMore,
                        modifier = Modifier.tag(TestTags.MISSION_DETAIL_LOAD_MORE),
                    ) { Text(if (state.loadingMore) "Loading…" else "Load more events") }
                }
            }

            if (state.query.isNotBlank()) {
                if (state.searching) item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp), color = Palette.Accent)
                    }
                }
                if (state.moments.isNotEmpty()) {
                    item { Text("MOMENTS", color = Palette.TextTertiary, style = MaterialTheme.typography.labelMedium) }
                    items(state.moments, key = { "${it.mission.id}:${it.entryIndex}" }) { m -> MomentCard(m) }
                }
            }
        }
    }
}

@Composable
private fun MetaField(label: String, value: String) {
    Row {
        Text(label, color = Palette.TextTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(110.dp))
        Text(value, color = Palette.TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EventRow(ev: StoredEvent) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(eventLabel(ev), color = eventColor(ev.eventType), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(ev.timestamp.take(19).replace('T', ' '), color = Palette.TextTertiary, style = MaterialTheme.typography.labelSmall)
            }
            if (ev.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    ev.content.boundedForText(maxChars = 2_000),
                    color = Palette.TextSecondary,
                    style = if (ev.eventType.startsWith("tool")) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp) else MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                )
            }
        }
    }
}

private fun eventLabel(ev: StoredEvent): String = when (ev.eventType) {
    "tool_call", "tool_result" -> "${ev.eventType} · ${ev.toolName ?: "?"}"
    else -> ev.eventType
}

private fun eventColor(type: String) = when (type) {
    "user_message" -> Palette.Accent
    "assistant_message", "text_delta" -> Palette.Success
    "tool_call", "tool_result" -> Palette.AccentLight
    "thinking" -> Palette.TextTertiary
    "error" -> Palette.Error
    else -> Palette.TextSecondary
}

@Composable
private fun MomentCard(m: MissionMomentSearchResult) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(m.role, color = Palette.TextTertiary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(m.snippet.boundedForText(maxChars = 1_500), color = Palette.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            if (m.rationale.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(m.rationale.boundedForText(maxChars = 1_000), color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}
