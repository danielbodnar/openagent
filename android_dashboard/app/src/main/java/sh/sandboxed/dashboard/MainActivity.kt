package sh.sandboxed.dashboard

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.ui.PipHost
import sh.sandboxed.dashboard.ui.nav.AppRoot
import sh.sandboxed.dashboard.ui.theme.SandboxedTheme
import sh.sandboxed.dashboard.util.GitHubAuth
import kotlin.math.roundToInt

class MainActivity : FragmentActivity(), PipHost {
    private val _isInPipMode = MutableStateFlow(false)
    override val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    override val isPipSupported: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    // Latest PiP params inputs. `pipActive` drives auto-enter (enter PiP when the
    // user leaves the app), which the desktop screen toggles while streaming.
    private var pipActive = false
    private var pipAspect: Rational? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SandboxedDashboardApp).container
        handleAuthIntent(intent, container)
        setContent {
            SandboxedTheme {
                val settings by container.cached.collectAsState()
                AppRoot(container = container, settings = settings, host = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val container = (application as SandboxedDashboardApp).container
        handleAuthIntent(intent, container)
    }

    override fun enterPip() {
        if (!isPipSupported) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    override fun setPipActive(active: Boolean, aspectWidth: Int, aspectHeight: Int) {
        if (!isPipSupported) return
        pipActive = active
        pipAspect = clampedAspect(aspectWidth, aspectHeight)
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isInPipMode.value = isInPictureInPictureMode
    }

    private fun buildPipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .apply { pipAspect?.let { setAspectRatio(it) } }
            .setAutoEnterEnabled(pipActive)
            .setSeamlessResizeEnabled(true)
            .build()

    /// Android requires the PiP aspect ratio to be within [1:2.39, 2.39:1].
    /// A frame outside that range gets clamped; an empty frame yields null
    /// (the system then uses a default ratio).
    private fun clampedAspect(width: Int, height: Int): Rational? {
        if (width <= 0 || height <= 0) return null
        val ratio = (width.toDouble() / height.toDouble()).coerceIn(1.0 / 2.39, 2.39)
        return Rational((ratio * 1000).roundToInt(), 1000)
    }

    private fun handleAuthIntent(intent: Intent?, container: AppContainer) {
        val data = intent?.data ?: return
        if (!GitHubAuth.isCallback(data)) return
        val result = GitHubAuth.parse(data)
        val token = result.token ?: return
        container.scope.launch { container.settings.setToken(token) }
    }
}
