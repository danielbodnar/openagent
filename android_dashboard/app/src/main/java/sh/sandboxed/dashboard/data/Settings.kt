package sh.sandboxed.dashboard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import sh.sandboxed.dashboard.util.TokenCrypto

private val Context.dataStore by preferencesDataStore(name = "sandboxed_settings")

data class AppSettings(
    val baseUrl: String = "",
    val jwtToken: String? = null,
    val lastUsername: String = "",
    val defaultAgent: String = "",
    val defaultBackend: String = "",
    val defaultModel: String = "",
    val skipAgentSelection: Boolean = false,
    /// Composer drafts keyed by mission id ("" = the no-mission composer).
    val drafts: Map<String, String> = emptyMap(),
    val lastMissionId: String? = null,
    val fidoRules: List<AutoApprovalRule> = emptyList(),
    val fidoRequireBiometricAll: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

class SettingsStore(private val ctx: Context) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("api_base_url")
        val JWT_TOKEN = stringPreferencesKey("jwt_token")
        val LAST_USERNAME = stringPreferencesKey("last_username")
        val DEFAULT_AGENT = stringPreferencesKey("default_agent")
        val DEFAULT_BACKEND = stringPreferencesKey("default_backend")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val SKIP_AGENT = booleanPreferencesKey("skip_agent_selection")
        val DRAFTS_JSON = stringPreferencesKey("control_drafts_by_mission")
        val LAST_MISSION = stringPreferencesKey("control_last_mission_id")
        val FIDO_RULES_JSON = stringPreferencesKey("fido_auto_approval_rules")
        val FIDO_REQUIRE_BIOMETRIC_ALL = booleanPreferencesKey("fido_require_biometric_all")
    }

    private val rulesJson = Json { ignoreUnknownKeys = true }
    private val rulesSerializer = ListSerializer(AutoApprovalRule.serializer())
    private val draftsSerializer = MapSerializer(String.serializer(), String.serializer())

    val flow: Flow<AppSettings> = ctx.dataStore.data.map { prefs ->
        AppSettings(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            jwtToken = prefs[Keys.JWT_TOKEN]?.takeIf { it.isNotBlank() }?.let { TokenCrypto.decrypt(it) }?.takeIf { it.isNotBlank() },
            lastUsername = prefs[Keys.LAST_USERNAME].orEmpty(),
            defaultAgent = prefs[Keys.DEFAULT_AGENT].orEmpty(),
            defaultBackend = prefs[Keys.DEFAULT_BACKEND].orEmpty(),
            defaultModel = prefs[Keys.DEFAULT_MODEL].orEmpty(),
            skipAgentSelection = prefs[Keys.SKIP_AGENT] ?: false,
            drafts = prefs[Keys.DRAFTS_JSON]
                ?.let { runCatching { rulesJson.decodeFromString(draftsSerializer, it) }.getOrNull() }
                ?: emptyMap(),
            lastMissionId = prefs[Keys.LAST_MISSION],
            fidoRules = prefs[Keys.FIDO_RULES_JSON]
                ?.let { runCatching { rulesJson.decodeFromString(rulesSerializer, it) }.getOrNull() }
                ?: emptyList(),
            fidoRequireBiometricAll = prefs[Keys.FIDO_REQUIRE_BIOMETRIC_ALL] ?: false,
        )
    }

    suspend fun setBaseUrl(value: String) = ctx.dataStore.edit { it[Keys.BASE_URL] = value.trimEnd('/') }
    suspend fun setToken(value: String?) = ctx.dataStore.edit {
        if (value.isNullOrBlank()) it.remove(Keys.JWT_TOKEN) else it[Keys.JWT_TOKEN] = TokenCrypto.encrypt(value)
    }
    suspend fun setLastUsername(value: String) = ctx.dataStore.edit { it[Keys.LAST_USERNAME] = value }
    suspend fun setDefaultAgent(value: String) = ctx.dataStore.edit { it[Keys.DEFAULT_AGENT] = value }
    suspend fun setDefaultBackend(value: String) = ctx.dataStore.edit { it[Keys.DEFAULT_BACKEND] = value }
    suspend fun setDefaultModel(value: String) = ctx.dataStore.edit { it[Keys.DEFAULT_MODEL] = value }
    suspend fun setSkipAgentSelection(value: Boolean) = ctx.dataStore.edit { it[Keys.SKIP_AGENT] = value }
    suspend fun setDraft(missionId: String?, value: String) = ctx.dataStore.edit { prefs ->
        val current = prefs[Keys.DRAFTS_JSON]
            ?.let { runCatching { rulesJson.decodeFromString(draftsSerializer, it) }.getOrNull() }
            ?: emptyMap()
        val key = missionId.orEmpty()
        val next = if (value.isBlank()) current - key else current + (key to value)
        // Bound the map so abandoned missions don't grow it forever.
        val bounded = if (next.size > 50) next.entries.drop(next.size - 50).associate { it.toPair() } else next
        prefs[Keys.DRAFTS_JSON] = rulesJson.encodeToString(draftsSerializer, bounded)
    }
    suspend fun setLastMission(value: String?) = ctx.dataStore.edit {
        if (value == null) it.remove(Keys.LAST_MISSION) else it[Keys.LAST_MISSION] = value
    }

    suspend fun setFidoRules(rules: List<AutoApprovalRule>) = ctx.dataStore.edit {
        it[Keys.FIDO_RULES_JSON] = rulesJson.encodeToString(rulesSerializer, rules)
    }

    suspend fun setFidoRequireBiometricAll(value: Boolean) = ctx.dataStore.edit {
        it[Keys.FIDO_REQUIRE_BIOMETRIC_ALL] = value
    }
}
