package com.aliothmoon.maafw.overlay.screensaver

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.runner.toLogText
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.third.Ln
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 后台屏保只在 AppRoot 现有 Compose 树中渲染，不创建 Window、ViewRoot 或独立 Composition。 */
class ScreenSaverOverlayManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val runnerPort: RunnerPort,
    private val focusDispatcher: FocusDispatcher,
    private val appSettings: AppSettingsGateway,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val presentation = ScreenSaverPresentation()
    private var phaseJob: Job? = null
    private var logJob: Job? = null
    private val _latestLog = MutableStateFlow<String?>(null)
    val latestLog: StateFlow<String?> = _latestLog.asStateFlow()
    val isShowing: StateFlow<Boolean> = presentation.isShowing

    fun bindHostActivity(activity: Activity) {
        presentation.bind(activity)
        if (!isShowing.value) stopLogRelay()
    }

    fun unbindHostActivity(activity: Activity) {
        if (presentation.unbind(activity)) stopLogRelay()
    }

    fun setup() {
        scope.launch {
            appSettings.runMode.collect { mode ->
                when (mode) {
                    RunMode.BACKGROUND -> observePhase()
                    RunMode.FOREGROUND -> {
                        phaseJob?.cancel()
                        phaseJob = null
                        hide()
                    }
                }
            }
        }
    }

    private fun observePhase() {
        if (phaseJob != null) return
        phaseJob = scope.launch {
            var previous: RunnerPhase = runnerPort.state.value.phase
            runnerPort.state.collect { state ->
                val current = state.phase
                when {
                    !previous.isBusy && current.isBusy ->
                        if (appSettings.screenSaverEnabled.value) show()
                    previous.isBusy && !current.isBusy -> hide()
                }
                previous = current
            }
        }
    }

    /** 手动与自动入口共用；只有可见主 Activity 才能显示同树屏保。 */
    suspend fun show(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (appSettings.runMode.value != RunMode.BACKGROUND) return@withContext false
        if (!presentation.show()) return@withContext false
        if (logJob == null) startLogRelay()
        true
    }

    suspend fun hide() = withContext(Dispatchers.Main.immediate) {
        presentation.hide()
        stopLogRelay()
    }

    private fun startLogRelay() {
        logJob = scope.launch {
            focusDispatcher.recording.collect { envelope ->
                envelope.event.toLogText(envelope.taskLabel)
                    .takeIf(String::isNotBlank)
                    ?.let { _latestLog.value = it }
            }
        }
    }

    private fun stopLogRelay() {
        logJob?.cancel()
        logJob = null
        _latestLog.value = null
    }
}

/** 可独立验证的显示状态；Activity 与被修改的 Window 均只保留弱引用。 */
internal class ScreenSaverPresentation {
    private var activity = WeakReference<Activity>(null)
    private val dimming = ScreenSaverWindowDimming()
    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    fun bind(host: Activity) {
        if (activity.get() === host) return
        hide()
        activity = WeakReference(host)
    }

    fun unbind(host: Activity): Boolean {
        if (activity.get() !== host) return false
        hide()
        activity.clear()
        return true
    }

    fun show(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        val host = activity.get()
        if (!isVisiblePrimaryScreenSaverActivity(host, sdkInt)) {
            hide()
            traceScreenSaver("show-rejected: no visible primary Activity")
            return false
        }
        if (_isShowing.value) return true
        return runCatching {
            dimming.apply(host!!.window)
            _isShowing.value = true
            traceScreenSaver("show route=activity-compose-tree display=0")
            true
        }.getOrElse {
            dimming.restore()
            traceScreenSaver("show failed: ${it.javaClass.simpleName}")
            false
        }
    }

    fun hide() {
        _isShowing.value = false
        dimming.restore()
    }
}

@Suppress("DEPRECATION")
internal fun isVisiblePrimaryScreenSaverActivity(activity: Activity?, sdkInt: Int): Boolean =
    runCatching {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return false
        val displayId = if (sdkInt >= Build.VERSION_CODES.R) activity.display?.displayId
            else activity.windowManager.defaultDisplay.displayId
        val decor = activity.window.decorView
        displayId == Display.DEFAULT_DISPLAY && decor.display?.displayId == Display.DEFAULT_DISPLAY &&
            decor.isAttachedToWindow && decor.isShown && decor.windowVisibility == View.VISIBLE
    }.getOrDefault(false)

/** 只恢复本功能改过的亮度与 KEEP_SCREEN_ON 位，保留期间其他功能更新的窗口 flags。 */
internal class ScreenSaverWindowDimming {
    private data class Saved(val window: WeakReference<Window>, val brightness: Float, val flags: Int)
    private var saved: Saved? = null

    fun apply(window: Window) {
        if (saved?.window?.get() === window) return
        restore()
        val attrs = window.attributes
        saved = Saved(WeakReference(window), attrs.screenBrightness, attrs.flags)
        attrs.screenBrightness = 0.01f
        attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        window.attributes = attrs
    }

    fun restore() {
        val original = saved ?: return
        saved = null
        val window = original.window.get() ?: return
        runCatching {
            val attrs = window.attributes
            attrs.screenBrightness = original.brightness
            val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            attrs.flags = (attrs.flags and keepOn.inv()) or (original.flags and keepOn)
            window.attributes = attrs
        }.onFailure { traceScreenSaver("restore window failed: ${it.javaClass.simpleName}") }
    }
}

private fun traceScreenSaver(message: String) {
    runCatching { Ln.i("ScreenSaverDisplay: $message") }
    runCatching { Timber.i("ScreenSaverDisplay: %s", message) }
}
