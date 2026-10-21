package com.aliothmoon.maafw.remote.internal
import com.aliothmoon.maafw.MaaDispatchers

import android.os.SystemClock
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.third.Ln
import java.util.function.BooleanSupplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 盯虚拟屏上的目标 app：优先接收 DriverClass 已知的启动包名，无 StartApp 时才从屏上反推，
 * 离屏则用 [ActivityUtils.repinAppToDisplay] 拉回；状态经 RemoteService.watchdogState() 暴露给 app。
 *
 * 判活与 onDisplay 合成一步（屏上有目标 app 即活）。全程 runCatching 宽松，不抛不误伤
 */
object AppWatchdog {

    const val STATE_IDLE = 0
    const val STATE_WATCHING = 1

    /** 窗口离开虚拟屏且拉回失败，进程还活着 */
    const val STATE_DISPLAY_DRIFT = 2

    /** pidof 查不到进程 */
    const val STATE_APP_DIED = 3

    private const val POLL_INTERVAL_MS = 5000L
    private const val REPIN_GRACE_MS = 5000L
    private const val MAX_REPIN_ATTEMPTS = 3

    /** PI 自己重启目标应用时会有一拍查不到进程，连续两拍都查不到才算没了 */
    private const val DEAD_CONFIRM_POLLS = 2

    private val scope = CoroutineScope(SupervisorJob())

    private val _state = MutableStateFlow(STATE_IDLE)
    val state: StateFlow<Int> = _state.asStateFlow()

    private var job: Job? = null
    private var watchedDisplayId = DefaultDisplayConfig.DISPLAY_NONE
    private var explicitTarget = false
    private var runGeneration = 0L
    private var targetGeneration = 0L
    private var activeToken: TargetToken? = null

    class TargetToken internal constructor(
        val runGeneration: Long,
        val targetGeneration: Long,
        val packageName: String,
        val displayId: Int,
    )

    private data class Snapshot(
        val runGeneration: Long,
        val displayId: Int,
        val token: TargetToken?,
        val explicitTarget: Boolean,
    )
    /** 本轮正在监控的目标；停止后不再保留可用于拉回的旧目标。 */
    @Volatile
    var targetPackage: String? = null
        private set
    /** 收尾关游戏单独保存；时长上限先 stopRun 再 stopTargetApp，不能被 stopWatching 清掉。 */
    @Volatile
    var cleanupTargetPackage: String? = null
        private set
    private var driftFirstSeenMs = 0L
    private var driftRepinAttempts = 0
    private var driftNotified = false
    private var diedNotified = false
    private var deadPolls = 0

    /** Run setup happens before runner.start(), whose worker can already call DriverClass. */
    @Synchronized
    fun prepareRun(displayId: Int) {
        stopWatching()
        cleanupTargetPackage = null
        watchedDisplayId = displayId.takeIf { it > 0 } ?: DefaultDisplayConfig.DISPLAY_NONE
    }

    @JvmStatic
    @Synchronized
    fun registerTarget(packageName: String, displayId: Int): TargetToken? {
        if (displayId != watchedDisplayId || displayId <= 0 || packageName.isBlank()) return null
        if (targetPackage != packageName) resetTargetState()
        explicitTarget = true
        targetPackage = packageName
        cleanupTargetPackage = packageName
        Ln.i("AppWatchdog: launch target registered: $packageName on display $displayId")
        return TargetToken(runGeneration, ++targetGeneration, packageName, displayId).also { activeToken = it }
    }

    @JvmStatic
    @Synchronized
    fun isTargetActive(token: TargetToken): Boolean =
        activeToken == token && watchedDisplayId == token.displayId && runGeneration == token.runGeneration

    @JvmStatic
    @Synchronized
    fun clearTarget(token: TargetToken) {
        if (!isTargetActive(token)) return
        targetPackage = null
        activeToken = null
        if (cleanupTargetPackage == token.packageName) cleanupTargetPackage = null
        explicitTarget = false
        resetTargetState()
    }

    private fun resetTargetState() {
        driftFirstSeenMs = 0L
        driftRepinAttempts = 0
        driftNotified = false
        diedNotified = false
        deadPolls = 0
        _state.value = STATE_IDLE
    }

    /**
     * Keep any target already registered by the worker during runner.start().
     * Natural completion keeps only the cleanup target for stopTargetApp.
     */
    @Synchronized
    fun startWatching(isRunActive: () -> Boolean) {
        job?.cancel()
        job = null
        if (watchedDisplayId == DefaultDisplayConfig.DISPLAY_NONE) return
        val generation = runGeneration
        Ln.i("AppWatchdog: start watching display $watchedDisplayId")
        job = scope.launch(MaaDispatchers.IO.limitedParallelism(1)) {
            while (isActive && isRunActive()) {
                delay(POLL_INTERVAL_MS)
                if (!isRunActive()) break
                tick()
            }
            synchronized(this@AppWatchdog) {
                if (isActive && runGeneration == generation) {
                    Ln.i("AppWatchdog: run finished, stop watching")
                    watchedDisplayId = DefaultDisplayConfig.DISPLAY_NONE
                    targetPackage = null
                    explicitTarget = false
                    activeToken = null
                    _state.value = STATE_IDLE
                }
            }
        }
    }

    @Synchronized
    fun stopWatching() {
        job?.cancel()
        job = null
        ++runGeneration
        watchedDisplayId = DefaultDisplayConfig.DISPLAY_NONE
        targetPackage = null
        explicitTarget = false
        activeToken = null
        resetTargetState()
    }

    private fun tick() {
        // Only state snapshots/commits hold our monitor. Binder queries, pidof and reparenting
        // can block; stopWatching must invalidate their token without waiting for any of them.
        val snapshot = synchronized(this) {
            if (watchedDisplayId == DefaultDisplayConfig.DISPLAY_NONE) return
            Snapshot(runGeneration, watchedDisplayId, activeToken, explicitTarget)
        }
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE || displayId != snapshot.displayId) {
            synchronized(this) { if (isCurrent(snapshot)) _state.value = STATE_IDLE }
            return
        }
        val occupancy = ActivityUtils.probeDisplay(displayId)
        // A ROM/API permission failure is not evidence that the game left its display.
        if (occupancy == ActivityUtils.DisplayOccupancy.Unknown) return
        val top = (occupancy as? ActivityUtils.DisplayOccupancy.Occupied)?.topPackage
        if (top != null && (!snapshot.explicitTarget || top == snapshot.token?.packageName)) {
            synchronized(this) {
                if (!isCurrent(snapshot)) return
                if (targetPackage == null) Ln.i("AppWatchdog: target acquired: $top")
                if (activeToken?.packageName != top) {
                    activeToken = TargetToken(runGeneration, ++targetGeneration, top, displayId)
                }
                targetPackage = top
                cleanupTargetPackage = top
                resetTargetState()
                _state.value = STATE_WATCHING
            }
            return
        }

        val token = snapshot.token ?: return
        val pkg = token.packageName
        if (!isTargetActive(token)) return

        // 屏上空了先问进程还在不在：被杀和"窗口跑到主屏去了"是两回事，
        // 拿后者的文案去讲前者，用户会照着去改前台模式而问题根本不在那
        val liveness = ProcessLiveness.probe(pkg)
        val shouldRepin = synchronized(this) {
            if (!isCurrent(snapshot)) return
            when (liveness) {
                ProcessLiveness.DEAD -> {
                    if (++deadPolls < DEAD_CONFIRM_POLLS) {
                        Ln.i("AppWatchdog: $pkg not found, confirming on next poll")
                        return
                    }
                    if (!diedNotified) {
                        diedNotified = true
                        _state.value = STATE_APP_DIED
                        Ln.w("AppWatchdog: $pkg process is gone")
                    }
                    return
                }
                ProcessLiveness.UNKNOWN -> return
                ProcessLiveness.ALIVE -> deadPolls = 0
            }
            if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS) return
            val now = SystemClock.elapsedRealtime()
            if (driftFirstSeenMs == 0L) {
                driftFirstSeenMs = now
                Ln.i("AppWatchdog: $pkg left the virtual display, grace ${REPIN_GRACE_MS}ms")
                return
            }
            if (now - driftFirstSeenMs < REPIN_GRACE_MS) return
            Ln.w("AppWatchdog: $pkg drifted, repin attempt ${driftRepinAttempts + 1}/$MAX_REPIN_ATTEMPTS")
            true
        }
        if (!shouldRepin) return
        val stillActive = BooleanSupplier { isTargetActive(token) }
        val ok = runCatching { ActivityUtils.repinAppToDisplay(pkg, displayId, stillActive) }.getOrDefault(false)
        val back = ok && stillActive.asBoolean &&
                runCatching { ActivityUtils.isAppOnDisplay(pkg, displayId) }.getOrDefault(true)
        synchronized(this) {
            if (!isCurrent(snapshot)) return
            if (back) {
                Ln.i("AppWatchdog: $pkg moved back to the virtual display")
                driftFirstSeenMs = 0L
                driftRepinAttempts = 0
                return
            }
            driftRepinAttempts++
            if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS && !driftNotified) {
                driftNotified = true
                _state.value = STATE_DISPLAY_DRIFT
                Ln.w("AppWatchdog: $pkg left the virtual display and repin failed")
            }
        }
    }

    /** Called only under this object's monitor. */
    private fun isCurrent(snapshot: Snapshot): Boolean =
        runGeneration == snapshot.runGeneration && watchedDisplayId == snapshot.displayId && activeToken == snapshot.token
}
