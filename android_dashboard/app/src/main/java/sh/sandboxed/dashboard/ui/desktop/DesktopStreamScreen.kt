package sh.sandboxed.dashboard.ui.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.api.DesktopStreamEvent
import sh.sandboxed.dashboard.ui.PipHost
import sh.sandboxed.dashboard.ui.TestTags
import sh.sandboxed.dashboard.ui.tag
import sh.sandboxed.dashboard.ui.components.ErrorBanner
import sh.sandboxed.dashboard.ui.theme.Palette
import kotlin.math.roundToInt

private data class DesktopStreamState(
    val display: String,
    val connected: Boolean = false,
    val paused: Boolean = false,
    val bitmap: Bitmap? = null,
    val frameCount: Long = 0,
    val fps: Int = 10,
    val quality: Int = 70,
    val error: String? = null,
)

private class DesktopStreamViewModel(
    private val container: AppContainer,
    initialDisplay: String,
) : ViewModel() {
    private val _state = MutableStateFlow(DesktopStreamState(display = initialDisplay.ifBlank { ":101" }))
    val state: StateFlow<DesktopStreamState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        connect()
    }

    fun connect(display: String = _state.value.display) {
        streamJob?.cancel()
        val normalized = display.trim().ifBlank { ":101" }
        _state.update {
            it.copy(
                display = normalized,
                connected = false,
                paused = false,
                error = null,
                frameCount = if (normalized == it.display) it.frameCount else 0,
                bitmap = if (normalized == it.display) it.bitmap else null,
            )
        }
        streamJob = viewModelScope.launch {
            container.desktop
                .connect(normalized, _state.value.fps, _state.value.quality)
                .catch { e ->
                    _state.update { it.copy(connected = false, error = e.message ?: "Desktop stream failed") }
                }
                .collect { event ->
                    when (event) {
                        DesktopStreamEvent.Connected -> _state.update { it.copy(connected = true, error = null) }
                        is DesktopStreamEvent.Frame -> _state.update {
                            it.copy(bitmap = event.bitmap, frameCount = it.frameCount + 1, connected = true, error = null)
                        }
                        is DesktopStreamEvent.Error -> _state.update { it.copy(error = event.message) }
                        is DesktopStreamEvent.Closed -> _state.update {
                            it.copy(connected = false, error = event.reason?.takeIf { reason -> reason.isNotBlank() })
                        }
                    }
                }
        }
    }

    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
    }

    fun togglePause() {
        if (_state.value.paused) {
            container.desktop.resume()
            _state.update { it.copy(paused = false) }
        } else {
            container.desktop.pause()
            _state.update { it.copy(paused = true) }
        }
    }

    fun setFps(fps: Int) {
        val clamped = fps.coerceIn(1, 30)
        _state.update { it.copy(fps = clamped) }
        container.desktop.setFps(clamped)
    }

    fun setQuality(quality: Int) {
        val clamped = quality.coerceIn(10, 100)
        _state.update { it.copy(quality = clamped) }
        container.desktop.setQuality(clamped)
    }

    fun click(x: Int, y: Int) {
        container.desktop.click(x, y)
    }

    fun scroll(amount: Int) {
        val bitmap = _state.value.bitmap ?: return
        container.desktop.scroll(bitmap.width / 2, bitmap.height / 2, amount)
    }

    fun typeText(text: String) {
        container.desktop.typeText(text)
    }

    fun key(key: String) {
        container.desktop.key(key)
    }
}

@Composable
fun DesktopStreamScreen(container: AppContainer, initialDisplay: String, pipHost: PipHost? = null) {
    val vm = remember(initialDisplay) { DesktopStreamViewModel(container, initialDisplay) }
    val state by vm.state.collectAsState()
    var typedText by remember { mutableStateOf("") }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    val inPip by (pipHost?.isInPipMode ?: remember { MutableStateFlow(false) }).collectAsState()

    DisposableEffect(vm) {
        onDispose { vm.disconnect() }
    }

    // While a frame is streaming, register its aspect ratio and enable
    // auto-enter so leaving the app keeps the desktop visible in a PiP window.
    // Keyed on the frame dimensions (not the Bitmap object) so it doesn't fire
    // every frame. Cleared on dispose so PiP only auto-enters from this screen.
    val frameWidth = state.bitmap?.width
    val frameHeight = state.bitmap?.height
    LaunchedEffect(frameWidth, frameHeight, state.connected) {
        if (frameWidth != null && frameHeight != null && state.connected) {
            pipHost?.setPipActive(true, frameWidth, frameHeight)
        } else {
            pipHost?.setPipActive(false, 0, 0)
        }
    }
    DisposableEffect(pipHost) {
        onDispose { pipHost?.setPipActive(false, 0, 0) }
    }

    if (inPip) {
        // PiP window: just the frame, full-bleed, no chrome or input.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            state.bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Desktop frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Palette.BackgroundPrimary)) {
        DesktopHeader(
            state = state,
            pipSupported = pipHost?.isPipSupported == true,
            onPause = vm::togglePause,
            onReconnect = { vm.connect() },
            onPip = { pipHost?.enterPip() },
        )
        DisplayPicker(current = state.display, onSelect = { vm.connect(it) })
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { frameSize = it },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = state.bitmap
            val error = state.error
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Desktop frame",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bitmap, frameSize) {
                                detectTapGestures { offset ->
                                    mapFramePoint(offset, bitmap, frameSize)?.let { (x, y) -> vm.click(x, y) }
                                }
                            },
                    )
                    TapHint(Modifier.align(Alignment.BottomEnd).padding(12.dp))
                }
                error != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ErrorBanner(error)
                        Button(onClick = { vm.connect() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent), modifier = Modifier.tag(TestTags.DESKTOP_RETRY)) {
                            Text("Retry")
                        }
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Palette.Accent)
                        Text("Connecting to ${state.display}", color = Palette.TextSecondary)
                    }
                }
            }
        }
        DesktopControls(
            state = state,
            typedText = typedText,
            onTypedText = { typedText = it },
            onFps = vm::setFps,
            onQuality = vm::setQuality,
            onType = {
                vm.typeText(typedText)
                typedText = ""
            },
            onKey = vm::key,
            onScroll = vm::scroll,
        )
    }
}

@Composable
private fun DesktopHeader(
    state: DesktopStreamState,
    pipSupported: Boolean,
    onPause: () -> Unit,
    onReconnect: () -> Unit,
    onPip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Palette.BackgroundSecondary).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Desktop", color = Palette.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                "${if (state.connected) if (state.paused) "Paused" else "Live" else "Disconnected"} · ${state.display} · ${state.frameCount} frames",
                color = if (state.connected) Palette.Success else Palette.Warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (pipSupported) {
            IconButton(onClick = onPip, enabled = state.connected && state.bitmap != null, modifier = Modifier.tag(TestTags.DESKTOP_PIP)) {
                Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = Palette.Accent)
            }
        }
        IconButton(onClick = onPause, enabled = state.connected, modifier = Modifier.tag(TestTags.DESKTOP_PAUSE)) {
            Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Pause stream", tint = Palette.Accent)
        }
        IconButton(onClick = onReconnect) {
            Icon(Icons.Filled.Refresh, "Reconnect", tint = Palette.TextSecondary)
        }
    }
}

@Composable
private fun DisplayPicker(current: String, onSelect: (String) -> Unit) {
    val displays = listOf(":99", ":100", ":101", ":102")
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Palette.BackgroundSecondary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(displays, key = { it }) { display ->
            FilterChip(
                selected = current == display,
                onClick = { onSelect(display) },
                label = { Text(display, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.tag(TestTags.desktopDisplay(display)),
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
private fun TapHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(Palette.Card.copy(alpha = 0.82f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.TouchApp, null, tint = Palette.TextTertiary, modifier = Modifier.size(14.dp))
        Text("Tap to click", color = Palette.TextTertiary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DesktopControls(
    state: DesktopStreamState,
    typedText: String,
    onTypedText: (String) -> Unit,
    onFps: (Int) -> Unit,
    onQuality: (Int) -> Unit,
    onType: () -> Unit,
    onKey: (String) -> Unit,
    onScroll: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Palette.BackgroundSecondary).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SliderRow("FPS", state.fps, "1", "30") { onFps(it.coerceIn(1, 30)) }
        Slider(
            value = state.fps.toFloat(),
            onValueChange = { onFps(it.roundToInt()) },
            valueRange = 1f..30f,
            steps = 28,
        )
        SliderRow("Quality", state.quality, "10", "100") { onQuality(it.coerceIn(10, 100)) }
        Slider(
            value = state.quality.toFloat(),
            onValueChange = { onQuality(it.roundToInt()) },
            valueRange = 10f..100f,
            steps = 17,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typedText,
                onValueChange = onTypedText,
                singleLine = true,
                placeholder = { Text("Type text", color = Palette.TextMuted) },
                modifier = Modifier.weight(1f).tag(TestTags.DESKTOP_TYPE_TEXT),
                colors = controlFieldColors(),
            )
            Button(onClick = onType, enabled = typedText.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent), modifier = Modifier.tag(TestTags.DESKTOP_TYPE_SUBMIT)) {
                Text("Type")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { QuickKey("Return", TestTags.DESKTOP_KEY_RETURN) { onKey("Return") } }
            item { QuickKey("Esc", TestTags.DESKTOP_KEY_ESC) { onKey("Escape") } }
            item { QuickKey("Ctrl+L", TestTags.DESKTOP_KEY_CTRL_L) { onKey("ctrl+l") } }
            item { QuickKey("Tab", TestTags.DESKTOP_KEY_TAB) { onKey("Tab") } }
            item {
                IconButton(onClick = { onScroll(-360) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Scroll up", tint = Palette.TextSecondary)
                }
            }
            item {
                IconButton(onClick = { onScroll(360) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Scroll down", tint = Palette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Int, minLabel: String, maxLabel: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextTertiary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Text(minLabel, color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(value.toString(), color = Palette.TextSecondary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(maxLabel, color = Palette.TextMuted, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onStep(value - 1) }) { Text("-") }
        TextButton(onClick = { onStep(value + 1) }) { Text("+") }
    }
}

@Composable
private fun QuickKey(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Palette.Card), modifier = Modifier.testTag(tag)) {
        Text(label, color = Palette.TextPrimary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun controlFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Palette.Card,
    unfocusedContainerColor = Palette.Card,
    focusedTextColor = Palette.TextPrimary,
    unfocusedTextColor = Palette.TextPrimary,
    cursorColor = Palette.Accent,
)

private fun mapFramePoint(offset: Offset, bitmap: Bitmap, container: IntSize): Pair<Int, Int>? {
    if (container.width <= 0 || container.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return null
    val scale = minOf(container.width / bitmap.width.toFloat(), container.height / bitmap.height.toFloat())
    if (scale <= 0f) return null
    val renderedWidth = bitmap.width * scale
    val renderedHeight = bitmap.height * scale
    val left = (container.width - renderedWidth) / 2f
    val top = (container.height - renderedHeight) / 2f
    val x = ((offset.x - left) / scale).roundToInt()
    val y = ((offset.y - top) / scale).roundToInt()
    return if (x in 0 until bitmap.width && y in 0 until bitmap.height) x to y else null
}
