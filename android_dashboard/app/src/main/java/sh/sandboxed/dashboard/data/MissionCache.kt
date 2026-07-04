package sh.sandboxed.dashboard.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/// On-disk cache of the last-loaded mission (including its conversation
/// history) so the Control screen can render something useful when the server
/// is unreachable. Stale renders are flagged via `ControlState.staleCache`.
class MissionCache(context: Context) {
    private val dir = File(context.filesDir, "mission_cache").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    suspend fun save(mission: Mission) = withContext(Dispatchers.IO) {
        runCatching { fileFor(mission.id)?.writeText(json.encodeToString(Mission.serializer(), mission)) }
        Unit
    }

    suspend fun load(id: String): Mission? = withContext(Dispatchers.IO) {
        val file = fileFor(id) ?: return@withContext null
        runCatching { json.decodeFromString(Mission.serializer(), file.readText()) }.getOrNull()
    }

    // Mission ids are UUIDs; anything else is rejected rather than sanitized so
    // a hostile id can't collide with another mission's cache file.
    private fun fileFor(id: String): File? =
        id.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
            ?.let { File(dir, "$it.json") }
}
