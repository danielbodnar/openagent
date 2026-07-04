package sh.sandboxed.dashboard.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

// Stable selectors for UI driving (uiautomator / Compose tests).
// AppRoot enables `testTagsAsResourceId`, so any tag here surfaces in
// `uiautomator dump` as a `resource-id`. Treat these as a public contract —
// renaming requires updating TEST_PLAN.md and any external driver scripts.
//
// `Modifier.tag(NAME)` pairs the semantics flag with the testTag so the
// resource-id surfaces even inside AlertDialog / Popup windows, which open in
// a separate Compose root and don't inherit the AppRoot-level flag.
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tag(name: String): Modifier =
    this.semantics { testTagsAsResourceId = true }.testTag(name)

object TestTags {
    // First-run / auth
    const val AUTH_URL_FIELD = "auth.url.field"
    const val AUTH_URL_CONTINUE = "auth.url.continue"
    const val AUTH_LOGIN_USERNAME = "auth.login.username"
    const val AUTH_LOGIN_PASSWORD = "auth.login.password"
    const val AUTH_LOGIN_SUBMIT = "auth.login.submit"
    const val AUTH_LOGIN_GITHUB = "auth.login.github"

    // Bottom navigation
    const val NAV_TAB_CONTROL = "nav.tab.control"
    const val NAV_TAB_HISTORY = "nav.tab.history"
    const val NAV_TAB_TERMINAL = "nav.tab.terminal"
    const val NAV_TAB_FILES = "nav.tab.files"
    const val NAV_TAB_MORE = "nav.tab.more"

    // Control: composer + top bar
    const val CONTROL_COMPOSER_INPUT = "control.composer.input"
    const val CONTROL_COMPOSER_SEND = "control.composer.send"
    const val CONTROL_TOPBAR_NEW_MISSION = "control.topbar.new_mission"
    const val CONTROL_TOPBAR_MISSIONS = "control.topbar.missions"
    const val CONTROL_TOPBAR_AUTOMATIONS = "control.topbar.automations"
    const val CONTROL_TOPBAR_DESKTOP = "control.topbar.desktop"
    const val CONTROL_TOPBAR_ASK = "control.topbar.ask"
    const val CONTROL_TOPBAR_RESUME = "control.topbar.resume"
    const val CONTROL_TOPBAR_OVERFLOW = "control.topbar.overflow"
    const val CONTROL_TOPBAR_WORKERS = "control.topbar.workers"
    const val CONTROL_TOPBAR_THOUGHTS = "control.topbar.thoughts"
    const val CONTROL_NEW_MESSAGES_CHIP = "control.new_messages_chip"
    const val CONTROL_MESSAGE_RETRY = "control.message.retry"
    const val CONTROL_THOUGHTS_CLOSE = "control.thoughts.close"
    const val CONTROL_COMPOSER_PARALLEL = "control.composer.parallel"
    const val CONTROL_STALE_PILL = "control.stale_pill"
    const val CONTROL_DIAGNOSTICS = "control.diagnostics"

    // Control: Ask co-pilot bottom sheet
    const val ASK_INPUT = "ask.input"
    const val ASK_SEND = "ask.send"
    const val ASK_THREADS = "ask.threads"
    const val ASK_NEW_THREAD = "ask.new_thread"
    const val ASK_CLEAR = "ask.clear"
    const val ASK_RETRY = "ask.retry"
    const val ASK_SEND_TO_AGENT = "ask.send_to_agent"

    // Control: New mission dialog
    const val NEW_MISSION_CREATE = "control.new_mission.create"
    const val NEW_MISSION_CANCEL = "control.new_mission.cancel"

    // Control: Mission switcher dialog
    const val SWITCHER_SEARCH = "control.switcher.search"
    const val SWITCHER_NEW = "control.switcher.new"
    const val SWITCHER_CLOSE = "control.switcher.close"

    // History (Missions)
    const val HISTORY_SEARCH = "history.search"
    const val HISTORY_REFRESH = "history.refresh"
    const val HISTORY_CLEANUP = "history.cleanup"
    const val HISTORY_FILTER_ALL = "history.filter.all"
    const val HISTORY_FILTER_ACTIVE = "history.filter.active"
    const val HISTORY_FILTER_INTERRUPTED = "history.filter.interrupted"
    const val HISTORY_FILTER_COMPLETED = "history.filter.completed"
    const val HISTORY_FILTER_FAILED = "history.filter.failed"

    // Terminal
    const val TERMINAL_INPUT = "terminal.input"
    const val TERMINAL_SEND = "terminal.send"
    const val TERMINAL_WORKSPACE = "terminal.workspace"
    const val TERMINAL_STATUS = "terminal.status"

    // Files
    const val FILES_UP = "files.up"
    const val FILES_UPLOAD = "files.upload"
    const val FILES_NEW_FOLDER = "files.new_folder"
    const val FILES_REFRESH = "files.refresh"
    const val FILES_PATH = "files.path"
    const val FILES_NEW_FOLDER_NAME = "files.new_folder.name"
    const val FILES_NEW_FOLDER_CREATE = "files.new_folder.create"
    const val FILES_NEW_FOLDER_CANCEL = "files.new_folder.cancel"

    // More
    const val MORE_TILE_WORKSPACES = "more.tile.workspaces"
    const val MORE_TILE_DESKTOP = "more.tile.desktop"
    const val MORE_TILE_TASKS = "more.tile.tasks"
    const val MORE_TILE_RUNS = "more.tile.runs"
    const val MORE_TILE_FIDO = "more.tile.fido"
    const val MORE_TILE_SETTINGS = "more.tile.settings"

    // Settings
    const val SETTINGS_URL = "settings.server.url"
    const val SETTINGS_TEST_SAVE = "settings.test_save"
    const val SETTINGS_SIGN_OUT = "settings.sign_out"
    const val SETTINGS_SKIP_AGENT_PICKER = "settings.skip_agent_picker"

    // Workspaces
    const val WORKSPACES_CREATE = "workspaces.create"
    const val WORKSPACES_REFRESH = "workspaces.refresh"
    const val WORKSPACES_NEW_NAME = "workspaces.new.name"
    const val WORKSPACES_NEW_TYPE_CONTAINER = "workspaces.new.type.container"
    const val WORKSPACES_NEW_TYPE_HOST = "workspaces.new.type.host"
    const val WORKSPACES_NEW_PATH = "workspaces.new.path"
    const val WORKSPACES_NEW_CREATE = "workspaces.new.create"
    const val WORKSPACES_NEW_CANCEL = "workspaces.new.cancel"

    // Automations
    const val AUTOMATIONS_ADD = "automations.add"
    const val AUTOMATIONS_NEW_COMMAND = "automations.new.command"
    const val AUTOMATIONS_NEW_INTERVAL_SECS = "automations.new.interval_secs"
    const val AUTOMATIONS_NEW_CREATE = "automations.new.create"
    const val AUTOMATIONS_NEW_CANCEL = "automations.new.cancel"
    const val AUTOMATIONS_NEW_LIBRARY_NAME = "automations.new.library_name"
    const val AUTOMATIONS_NEW_FILE_PATH = "automations.new.file_path"
    const val AUTOMATIONS_NEW_VARIABLES = "automations.new.variables"
    const val AUTOMATIONS_NEW_STOP_COUNT = "automations.new.stop_count"
    const val AUTOMATIONS_NEW_STOP_REPO = "automations.new.stop_repo"
    const val AUTOMATIONS_NEW_RETRIES = "automations.new.retries"
    const val AUTOMATIONS_NEW_RETRY_DELAY = "automations.new.retry_delay"
    const val AUTOMATIONS_NEW_BACKOFF = "automations.new.backoff"

    // FIDO
    const val FIDO_BIOMETRIC_TOGGLE = "fido.always_biometric"
    const val FIDO_ADD_RULE = "fido.add_rule"
    const val FIDO_NEW_MATCH_ALL = "fido.new.match.all"
    const val FIDO_NEW_MATCH_HOST = "fido.new.match.host"
    const val FIDO_NEW_MATCH_FINGERPRINT = "fido.new.match.fingerprint"
    const val FIDO_NEW_VALUE = "fido.new.value"
    const val FIDO_NEW_EXPIRY_1H = "fido.new.expiry.1h"
    const val FIDO_NEW_EXPIRY_24H = "fido.new.expiry.24h"
    const val FIDO_NEW_EXPIRY_7D = "fido.new.expiry.7d"
    const val FIDO_NEW_EXPIRY_NEVER = "fido.new.expiry.never"
    const val FIDO_NEW_BIOMETRIC = "fido.new.biometric"
    const val FIDO_NEW_ADD = "fido.new.add"
    const val FIDO_NEW_CANCEL = "fido.new.cancel"

    // Tasks / Runs
    const val TASKS_REFRESH = "tasks.refresh"
    const val RUNS_REFRESH = "runs.refresh"

    // Desktop
    const val DESKTOP_RETRY = "desktop.retry"
    const val DESKTOP_PAUSE = "desktop.pause"
    const val DESKTOP_TYPE_TEXT = "desktop.type_text"
    const val DESKTOP_TYPE_SUBMIT = "desktop.type_submit"
    const val DESKTOP_KEY_RETURN = "desktop.key.return"
    const val DESKTOP_KEY_ESC = "desktop.key.esc"
    const val DESKTOP_KEY_CTRL_L = "desktop.key.ctrl_l"
    const val DESKTOP_KEY_TAB = "desktop.key.tab"
    const val DESKTOP_PIP = "desktop.pip"
    // Parameterised helpers
    fun desktopDisplay(display: String) = "desktop.display.${display.removePrefix(":")}"
    fun backendSelect(id: String) = "settings.backend.$id"
    fun agentSelect(id: String) = "settings.agent.$id"
    const val SETTINGS_MODEL_DEFAULT = "settings.model.default"
    fun modelSelect(id: String) = "settings.model.${id.replace('/', '_')}"

    // Files: path navigation
    const val FILES_QUICKNAV = "files.quicknav"
    const val FILES_PATH_FIELD = "files.path.field"
    const val FILES_PATH_GO = "files.path.go"

    // Workspaces
    const val WORKSPACES_DETAIL_CLOSE = "workspaces.detail.close"

    // Mission detail (History)
    const val MISSION_DETAIL_BACK = "mission_detail.back"
    const val MISSION_DETAIL_OPEN_CONTROL = "mission_detail.open_control"
    const val MISSION_DETAIL_SEARCH = "mission_detail.search"
    const val MISSION_DETAIL_LOAD_MORE = "mission_detail.load_more"
}
