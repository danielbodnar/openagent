package sh.sandboxed.dashboard.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import sh.sandboxed.dashboard.data.AppContainer

/**
 * Lightweight haptic helper. minSdk=33 guarantees VibratorManager, one-shot
 * vibration effects, and predefined effects are available.
 */
class Haptics(container: AppContainer) {
    private val vibrator: Vibrator? = run {
        val ctx: Context = container.appContext
        val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    }

    fun light() = oneShot(intensity = 32, durationMs = 12)
    fun medium() = oneShot(intensity = 96, durationMs = 18)
    fun success() = heavyClick()
    fun selection() = oneShot(intensity = 24, durationMs = 8)
    fun error() {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 35, 25), -1))
    }

    private fun oneShot(intensity: Int, durationMs: Long) {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(durationMs, intensity.coerceIn(1, 255)))
    }

    private fun heavyClick() {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }
}
