package sh.sandboxed.dashboard.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.AskMessage
import sh.sandboxed.dashboard.data.AskThread
import sh.sandboxed.dashboard.ui.TestTags
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import sh.sandboxed.dashboard.ui.tag
import sh.sandboxed.dashboard.ui.theme.Palette
import sh.sandboxed.dashboard.util.Haptics

// Cyan "co-pilot" identity, distinct from the indigo accent of the main agent.
private val Copilot = Color(0xFF22D3EE)

/// Ask — the Android surface for the non-interrupting sidecar co-pilot.
///
/// A bottom sheet over the mission that runs in its own lane: it never touches
/// the mission's queue or the working agent. Threads/messages live in a
/// separate backend store and render here with a distinct cyan identity.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    container: AppContainer,
    missionId: String,
    onSendToAgent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val vm = remember(missionId) { AskViewModel(container, missionId) }
    val state by vm.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = remember { Haptics(container) }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    DisposableEffect(vm) { onDispose { vm.stop() } }

    LaunchedEffect(state.messages.size, state.isLoading) {
        val count = state.messages.size + if (state.isLoading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.BackgroundSecondary,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            AskHeader(
                threads = state.threads,
                threadId = state.threadId,
                onNewThread = { haptics.selection(); vm.newThread() },
                onSelectThread = { haptics.selection(); vm.selectThread(it) },
                onClear = { haptics.error(); vm.clearThread() },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    item("empty") { AskEmptyState() }
                }
                items(state.messages, key = { it.id }) { message ->
                    AskBubble(
                        message = message,
                        onSendToAgent = { haptics.selection(); onSendToAgent(it) },
                        onRetry = if (message.sendState.isFailed) ({ vm.retry(message) }) else null,
                    )
                }
                if (state.isLoading) {
                    item("loading") { ThinkingRow() }
                }
                state.error?.let { err ->
                    item("error") {
                        Text(
                            err,
                            color = Palette.Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Palette.Error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        )
                    }
                }
            }
            AskComposer(
                value = input,
                onChange = { input = it },
                enabled = !state.isLoading,
                onSend = {
                    val text = input
                    input = ""
                    haptics.medium()
                    vm.send(text)
                },
            )
        }
    }
}

@Composable
private fun AskHeader(
    threads: List<AskThread>,
    threadId: String?,
    onNewThread: () -> Unit,
    onSelectThread: (String) -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = Copilot, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Ask", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.tag(TestTags.ASK_THREADS)) {
                Icon(Icons.Filled.Forum, "Threads", tint = Copilot)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New thread") },
                    onClick = { menuOpen = false; onNewThread() },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                    modifier = Modifier.tag(TestTags.ASK_NEW_THREAD),
                )
                threads.forEach { thread ->
                    DropdownMenuItem(
                        text = { Text(thread.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { menuOpen = false; onSelectThread(thread.id) },
                        leadingIcon = {
                            if (thread.id == threadId) Icon(Icons.Filled.Check, null, tint = Copilot)
                            else Icon(Icons.Filled.Forum, null)
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = onClear,
            enabled = threadId != null,
            modifier = Modifier.tag(TestTags.ASK_CLEAR),
        ) {
            Icon(Icons.Filled.Delete, "Clear thread", tint = if (threadId != null) Copilot else Palette.TextMuted)
        }
    }
}

@Composable
private fun AskEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = Copilot.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
        Text(
            "Ask about this mission — what it's doing, why, or inspect the workspace. The working agent is never interrupted.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun ThinkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Copilot)
        Text("thinking…", style = MaterialTheme.typography.bodySmall, color = Copilot.copy(alpha = 0.8f))
    }
}

@Composable
private fun AskBubble(
    message: AskMessage,
    onSendToAgent: (String) -> Unit,
    onRetry: (() -> Unit)?,
) {
    when {
        message.isUser -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 320.dp)) {
                    Box(
                        Modifier
                            .background(
                                if (message.sendState.isFailed) Palette.Error.copy(alpha = 0.18f) else Palette.Card,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (message.sendState.isPending) Palette.TextMuted else Palette.TextPrimary,
                            )
                            if (message.sendState.isPending) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Palette.TextMuted)
                            } else if (message.sendState.isFailed) {
                                Icon(Icons.Filled.ErrorOutline, "Failed", tint = Palette.Error, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (message.sendState.isFailed && onRetry != null) {
                        Row(
                            Modifier.tag(TestTags.ASK_RETRY).clickable(onClick = onRetry).padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, null, tint = Palette.Error, modifier = Modifier.size(13.dp))
                            Text("Not sent · Tap to retry", style = MaterialTheme.typography.labelSmall, color = Palette.Error)
                        }
                    }
                }
            }
        }
        message.isTool -> {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Terminal, null, tint = Palette.TextMuted, modifier = Modifier.size(12.dp).padding(top = 2.dp))
                Text(
                    toolSummary(message),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Palette.TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        else -> {
            // assistant
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Copilot, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .background(Copilot.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Markdown(
                        content = message.content,
                        colors = markdownColor(
                            text = Palette.TextPrimary,
                            codeText = Palette.TextSecondary,
                            codeBackground = Palette.BackgroundTertiary,
                            inlineCodeText = Palette.AccentLight,
                            inlineCodeBackground = Palette.BackgroundTertiary,
                            linkText = Palette.AccentLight,
                            dividerColor = Palette.Border,
                        ),
                    )
                    if (message.content.isNotBlank()) {
                        Row(
                            Modifier.tag(TestTags.ASK_SEND_TO_AGENT).clickable { onSendToAgent(message.content) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Copilot.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                            Text("Send to agent", style = MaterialTheme.typography.labelSmall, color = Copilot.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskComposer(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    val canSend = value.isNotBlank() && enabled
    Row(
        Modifier.fillMaxWidth().background(Palette.BackgroundTertiary).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("Ask the co-pilot…", color = Palette.TextMuted) },
            maxLines = 4,
            modifier = Modifier.weight(1f).tag(TestTags.ASK_INPUT),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.Card,
                unfocusedContainerColor = Palette.Card,
                focusedTextColor = Palette.TextPrimary,
                unfocusedTextColor = Palette.TextPrimary,
                cursorColor = Copilot,
            ),
        )
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.tag(TestTags.ASK_SEND),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                "Send",
                tint = if (canSend) Copilot else Palette.TextMuted,
            )
        }
    }
}

private fun toolSummary(message: AskMessage): String {
    val label = message.toolName?.let { "$it → " } ?: "↳ "
    val body = if (message.isToolCall) {
        runCatching {
            val obj = JSONObject(message.content)
            obj.optString("command").takeIf { it.isNotBlank() }
                ?: obj.optString("path").takeIf { it.isNotBlank() }
                ?: message.content
        }.getOrDefault(message.content)
    } else {
        message.content
    }
    val trimmed = if (body.length > 200) body.take(200) + "…" else body
    return label + trimmed
}
