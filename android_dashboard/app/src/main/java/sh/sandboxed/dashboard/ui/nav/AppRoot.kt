package sh.sandboxed.dashboard.ui.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.AppSettings
import sh.sandboxed.dashboard.ui.PipHost
import sh.sandboxed.dashboard.ui.auth.AuthGate
import sh.sandboxed.dashboard.ui.automations.AutomationsScreen
import sh.sandboxed.dashboard.ui.control.ControlScreen
import sh.sandboxed.dashboard.ui.desktop.DesktopStreamScreen
import sh.sandboxed.dashboard.ui.fido.FidoOverlay
import sh.sandboxed.dashboard.ui.fido.FidoRulesScreen
import sh.sandboxed.dashboard.ui.files.FilesScreen
import sh.sandboxed.dashboard.ui.history.HistoryScreen
import sh.sandboxed.dashboard.ui.history.MissionDetailScreen
import sh.sandboxed.dashboard.ui.more.MoreScreen
import sh.sandboxed.dashboard.ui.runs.RunsScreen
import sh.sandboxed.dashboard.ui.TestTags
import sh.sandboxed.dashboard.ui.tag
import sh.sandboxed.dashboard.ui.settings.SettingsScreen
import sh.sandboxed.dashboard.ui.tasks.TasksScreen
import sh.sandboxed.dashboard.ui.terminal.TerminalScreen
import sh.sandboxed.dashboard.ui.theme.Palette
import sh.sandboxed.dashboard.ui.workspaces.WorkspacesScreen

private data class TabItem(val route: String, val label: String, val icon: ImageVector, val tag: String)

private val Tabs = listOf(
    TabItem("control", "Control", Icons.AutoMirrored.Filled.Chat, TestTags.NAV_TAB_CONTROL),
    TabItem("history", "Missions", Icons.Filled.History, TestTags.NAV_TAB_HISTORY),
    TabItem("terminal", "Terminal", Icons.Filled.Terminal, TestTags.NAV_TAB_TERMINAL),
    TabItem("files", "Files", Icons.Filled.Folder, TestTags.NAV_TAB_FILES),
    TabItem("more", "More", Icons.Filled.MoreHoriz, TestTags.NAV_TAB_MORE),
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(container: AppContainer, settings: AppSettings, host: FragmentActivity?) {
    // Wrap the whole tree (auth gate + main scaffold) so testTags surface as
    // `resource-id` in uiautomator dumps on every screen, including the
    // first-run config and login screens.
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        AuthGate(container = container, settings = settings) {
            Box(Modifier.fillMaxSize()) {
                MainScaffold(container, host)
                FidoOverlay(container, host)
            }
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, host: FragmentActivity?) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // In Picture-in-Picture the whole activity shrinks into a small window, so
    // drop the bottom navigation and insets and let the active content (the
    // desktop frame) fill it.
    val pipHost = host as? PipHost
    val inPip by (pipHost?.isInPipMode ?: remember { MutableStateFlow(false) }).collectAsState()

    Scaffold(
        bottomBar = {
            if (!inPip) {
                NavigationBar(containerColor = Palette.BackgroundSecondary, tonalElevation = 0.dp) {
                    Tabs.forEach { tab ->
                        val selected = currentRoute?.let { it == tab.route || it.startsWith(tab.route + "/") } ?: false
                        NavigationBarItem(
                            modifier = Modifier.testTag(tab.tag),
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    // More is a menu, not a persistent content tab. Restoring its
                                    // previous state can reopen the last menu destination directly.
                                    restoreState = tab.route != "more"
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Accent,
                                selectedTextColor = Palette.Accent,
                                unselectedIconColor = Palette.TextTertiary,
                                unselectedTextColor = Palette.TextTertiary,
                                indicatorColor = Palette.BackgroundTertiary,
                            )
                        )
                    }
                }
            }
        },
        containerColor = Palette.BackgroundPrimary,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(if (inPip) PaddingValues(0.dp) else padding).background(Palette.BackgroundPrimary)) {
            AppNavHost(navController, container, host)
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, container: AppContainer, host: FragmentActivity?) {
    NavHost(navController = navController, startDestination = "control") {
        composable("control") {
            ControlScreen(
                container = container,
                onOpenAutomations = { missionId -> navController.navigate("automations/$missionId") },
                onOpenDesktop = { display -> navController.navigate("desktop/${Uri.encode(display)}") },
            )
        }
        composable("history") {
            HistoryScreen(container) { missionId ->
                navController.navigate("mission/$missionId")
            }
        }
        composable(
            route = "mission/{missionId}",
            arguments = listOf(navArgument("missionId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("missionId").orEmpty()
            MissionDetailScreen(
                container = container,
                missionId = id,
                onBack = { navController.popBackStack() },
                onOpenControl = { missionId ->
                    container.scope.launch { container.settings.setLastMission(missionId) }
                    navController.navigate("control") { launchSingleTop = true }
                },
            )
        }
        composable("terminal") { TerminalScreen(container) }
        composable("files") { FilesScreen(container) }
        composable("more") {
            MoreScreen { route -> navController.navigate(route) }
        }
        composable("workspaces") { WorkspacesScreen(container) }
        composable("tasks") { TasksScreen(container) }
        composable("runs") { RunsScreen(container) }
        composable("settings") { SettingsScreen(container) }
        composable("fido_rules") { FidoRulesScreen(container) { navController.popBackStack() } }
        composable(
            route = "desktop/{display}",
            arguments = listOf(navArgument("display") { type = NavType.StringType }),
        ) { entry ->
            val display = Uri.decode(entry.arguments?.getString("display").orEmpty()).ifBlank { ":101" }
            DesktopStreamScreen(container, display, host as? PipHost)
        }
        composable(
            route = "automations/{missionId}",
            arguments = listOf(navArgument("missionId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("missionId").orEmpty()
            AutomationsScreen(container, id) { navController.popBackStack() }
        }
    }
}
