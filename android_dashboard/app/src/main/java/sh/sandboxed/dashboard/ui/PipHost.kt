package sh.sandboxed.dashboard.ui

import kotlinx.coroutines.flow.StateFlow

/// Activity-level Picture-in-Picture surface, implemented by [MainActivity] and
/// consumed by the desktop stream screen. Kept as an interface so the UI layer
/// doesn't depend on the concrete Activity.
interface PipHost {
    /// Whether this device exposes the PiP system feature.
    val isPipSupported: Boolean

    /// True while the activity is rendered as a Picture-in-Picture window.
    val isInPipMode: StateFlow<Boolean>

    /// Explicitly enter PiP now (e.g. a "PiP" button tap).
    fun enterPip()

    /// Enable/disable auto-enter PiP (when the user leaves the app) and set the
    /// source aspect ratio (width:height). Pass active=false to stop auto-enter.
    fun setPipActive(active: Boolean, aspectWidth: Int, aspectHeight: Int)
}
