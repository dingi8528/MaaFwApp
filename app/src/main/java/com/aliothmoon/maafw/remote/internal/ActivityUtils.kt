package com.aliothmoon.maafw.remote.internal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

@SuppressLint("BlockedPrivateApi")
object ActivityUtils {

    // android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
    private const val WINDOWING_MODE_FULLSCREEN = 1

    // getAppDisplayId：包名无任何运行中任务
    private const val DISPLAY_NO_TASK = -2

    // 启动后等待任务出现在目标屏的窗口
    private const val APP_PIN_TIMEOUT_MS = 10_000L
    private const val APP_PIN_POLL_INTERVAL_MS = 500L
    private const val COLD_START_STABLE_MS = 2_000L

    // 拉回操作后等系统完成 reparent 的时间
    private const val REPIN_SETTLE_MS = 1_000L

    @Volatile
    var forceFullscreenOnVirtualDisplay: Boolean = false

    private val setLaunchWindowingMode by lazy {
        runCatching {
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.onFailure { Ln.w("setLaunchWindowingMode: reflection failed", it) }.getOrNull()
    }

    /**
     * 以 shell 身份启动指定 Intent 的 Activity，绕过 BAL 限制。
     * [forceFullscreen] 为 true 时无视用户设置强制 FULLSCREEN 窗口模式（用于漂移拉回重试）。
     */
    @JvmStatic
    @JvmOverloads
    fun startActivity(
        intent: Intent, displayId: Int = 0, forceFullscreen: Boolean = false,
        stillActive: BooleanSupplier = BooleanSupplier { true },
    ): Boolean {
        if (!stillActive.asBoolean) return false
        val am = ServiceManager.getActivityManager()
        try {
            val launchOptions = ActivityOptions.makeBasic()
            if (displayId != Display.DEFAULT_DISPLAY) {
                launchOptions.launchDisplayId = displayId
                if (forceFullscreenOnVirtualDisplay || forceFullscreen) {
                    runCatching {
                        setLaunchWindowingMode?.invoke(launchOptions, WINDOWING_MODE_FULLSCREEN)
                    }.onFailure {
                        Ln.e("invoke setLaunchWindowingMode failed", it)
                    }
                }
            }
            val ret = try {
                if (!stillActive.asBoolean) return false
                am.startActivity(intent, launchOptions.toBundle())
            } catch (e: Exception) {
                Ln.w("startActivity failed, returning -1", e)
                -1
            }
            if (!stillActive.asBoolean) return false
            if (ret < 0) {
                Ln.w("startActivity returned error code $ret, fallback to am command")
                return startViaAmCommand(intent, displayId, stillActive)
            }
            return true
        } catch (e: Exception) {
            Ln.w("startActivity failed, fallback to am command", e)
            return startViaAmCommand(intent, displayId, stillActive)
        }
    }

    /**
     * PI 的 StartApp 允许把 package 写成 `包名/Activity` 的 component 全名（M9A 的 startup.json 即是），
     * 官方 adb controller 原样塞进 am start 所以两种都能用；走 PackageManager 与包名比对的地方必须先拆
     * 拆不出来时原样返回，让调用方按纯包名走既有失败路径
     */
    @JvmStatic
    fun packageNameOf(spec: String): String = componentOf(spec)?.packageName ?: spec

    /** Android 13+ task FPS callback 只认 taskId；找不到则由调用方走帧计数回退 */
    @JvmStatic
    fun findTaskId(packageName: String): Int? {
        val task = runCatching { findRecentTask(packageName) }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) task.taskId else task.id
    }

    private fun componentOf(spec: String): ComponentName? =
        spec.takeIf { it.contains('/') }?.let { ComponentName.unflattenFromString(it) }

    /** API < Q 读不出任务在哪块屏上，只能拿「上次是我们把它拉到这块屏上的」当它占着屏的依据 */
    @Volatile
    private var lastLaunched: Pair<String, Int>? = null
    private data class PendingLaunch(val packageName: String, val displayId: Int, val stillActive: BooleanSupplier)
    private val pendingStableLaunch = AtomicReference<PendingLaunch?>(null)

    /**
     * 强杀 [packageName]（可以是 component 全名）；它占着 [displayId] 时等画面停稳并把帧缓冲换成黑帧
     *
     * 杀的不是屏上那个应用就不碰帧缓冲：屏上是别人的静止画面，换黑了不会自己回来
     */
    @JvmStatic
    @JvmOverloads
    fun forceStop(
        packageName: String, displayId: Int,
        stillActive: BooleanSupplier = BooleanSupplier { true },
    ): Boolean {
        if (!stillActive.asBoolean) return false
        val targetPackage = packageNameOf(packageName)
        val occupiesDisplay = when (val current = getAppDisplayId(targetPackage)) {
            null -> lastLaunched == (targetPackage to displayId)
            else -> current == displayId
        }
        if (!stillActive.asBoolean) return false
        val stopped = runCatching {
            ServiceManager.getActivityManager().forceStopPackage(targetPackage)
        }.onFailure {
            Ln.w("forceStop: failed to force-stop $targetPackage", it)
        }.getOrDefault(false)
        if (!stillActive.asBoolean) return false
        if (stopped && occupiesDisplay) StaleFrameGuard.blankAfterKill(displayId, targetPackage)
        return stopped
    }

    @JvmStatic
    @JvmOverloads
    fun startApp(
        packageName: String,
        displayId: Int,
        forceStop: Boolean = true,
        excludeFromRecents: Boolean = true,
        stillActive: BooleanSupplier = BooleanSupplier { true },
    ): Boolean {
        if (!stillActive.asBoolean) return false
        val pm = FakeContext.get().packageManager

        val component = componentOf(packageName)
        val targetPackage = component?.packageName ?: packageName

        val intent = if (component != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        } else {
            pm.getLaunchIntentForPackage(packageName)
                ?: pm.getLeanbackLaunchIntentForPackage(packageName)
        }

        if (intent == null) {
            Ln.w("Cannot create launch intent for app $packageName")
            return false
        }

        var flag = Intent.FLAG_ACTIVITY_NEW_TASK
        if (excludeFromRecents) {
            flag = flag or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        intent.addFlags(flag)

        // force_stop 只为把主屏上的现有进程挪到目标屏；已在目标屏上运行时只拉到前台。
        // 资源侧常在加载期反复 StartApp 等待进入游戏（ADB 下语义即"已运行则前台化"），
        // 每次都杀会让目标永远停在启动阶段
        val beforeLaunch = getAppDisplayId(targetPackage, "startApp-before")
        if (!stillActive.asBoolean) return false
        var knownColdStart = false
        if (forceStop) {
            if (beforeLaunch == displayId) {
                Ln.i("startApp: $targetPackage already on display $displayId, skip force-stop")
            } else {
                if (!stillActive.asBoolean) return false
                val stopped = forceStop(targetPackage, displayId, stillActive)
                knownColdStart = stopped && displayId > Display.DEFAULT_DISPLAY && beforeLaunch != null
            }
        }
        Ln.i("startApp ${intent.component?.flattenToShortString()}")

        return startActivity(intent, displayId, stillActive = stillActive).also { started ->
            Ln.i("startApp: package=$targetPackage expected=$displayId started=$started knownColdStart=$knownColdStart")
            if (started) {
                lastLaunched = targetPackage to displayId
                if (knownColdStart) pendingStableLaunch.set(PendingLaunch(targetPackage, displayId, stillActive))
            }
        }
    }

    sealed interface DisplayOccupancy {
        data class Occupied(val topPackage: String?) : DisplayOccupancy
        data object Empty : DisplayOccupancy
        data object Unknown : DisplayOccupancy
    }

    /**
     * [displayId] 上有没有任务。API 不支持、任务表为空、有任务读不出 displayId 都算 [DisplayOccupancy.Unknown]：
     * [StaleFrameGuard] 拿 Empty 当「画面已经没人画了」去换黑帧，把判不出当成空了，
     * 一张不再重绘的静止界面就会一直黑下去
     */
    fun probeDisplay(displayId: Int): DisplayOccupancy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return DisplayOccupancy.Unknown
        if (taskDisplayIdField == null) return DisplayOccupancy.Unknown
        return runCatching {
            val am = FakeContext.get().getSystemService(ActivityManager::class.java)
                ?: return DisplayOccupancy.Unknown
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(100)
            val displayIds = tasks.map(::getTaskDisplayId)
            val index = displayIds.indexOf(displayId)
            when {
                index >= 0 -> DisplayOccupancy.Occupied(tasks[index].topActivity?.packageName)
                tasks.isEmpty() || displayIds.any { it < 0 } -> DisplayOccupancy.Unknown
                else -> DisplayOccupancy.Empty
            }
        }.onFailure { Ln.w("probeDisplay: failed", it) }.getOrDefault(DisplayOccupancy.Unknown)
    }

    /**
     * 检查指定包名的应用最近的活动 task 是否运行在给定 displayId 上。
     * API 28 无 TaskInfo.displayId 字段（@hide），宽松返回 true（不拦截）。
     * 任何异常也宽松返回 true，避免误伤。
     */
    fun isAppOnDisplay(packageName: String, targetDisplayId: Int): Boolean {
        return when (getAppDisplayId(packageName)) {
            null -> true // 无法判断，宽松放行
            targetDisplayId -> true
            else -> false
        }
    }

    /**
     * 启动后校验：等待 [packageName] 的任务出现在 [displayId] 上；若发现任务落在其它
     * display（如 One UI / 部分 ROM 会把游戏从虚拟屏挪回主屏，B 服 U8 SDK 二段跳也可能
     * 丢失 launchDisplayId），立即尝试拉回。仅在确认漂移且拉回失败时返回 false。
     */
    @JvmStatic
    @JvmOverloads
    fun ensureAppOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long = APP_PIN_TIMEOUT_MS
    ): Boolean = ensureAppOnDisplay(packageName, displayId, timeoutMs, BooleanSupplier { true })

    @JvmStatic
    fun ensureAppOnDisplay(packageName: String, displayId: Int, stillActive: BooleanSupplier): Boolean =
        ensureAppOnDisplay(packageName, displayId, APP_PIN_TIMEOUT_MS, stillActive)

    private fun ensureAppOnDisplay(
        packageName: String, displayId: Int, timeoutMs: Long, stillActive: BooleanSupplier,
    ): Boolean {
        val startedAt = SystemClock.uptimeMillis()
        if (!stillActive.asBoolean) return false
        // Match the exact launch predicate, not just package/display: an old cancelled callback
        // must not consume a newer run's pending stability check for the same game and VD.
        val pending = pendingStableLaunch.get()
        val requireStable = pending != null && pending.packageName == packageName &&
                pending.displayId == displayId && pending.stillActive === stillActive &&
                pendingStableLaunch.compareAndSet(pending, null)
        val stability = DisplayPinStability(
            displayId, startedAt, timeoutMs,
            if (requireStable) COLD_START_STABLE_MS else 0L,
        )
        fun finish(result: Boolean, reason: String): Boolean {
            Ln.i("ensureAppOnDisplay: package=$packageName expected=$displayId result=$result reason=$reason" +
                    " stableRequired=$requireStable elapsed=${SystemClock.uptimeMillis() - startedAt}ms")
            return result
        }
        while (true) {
            if (!stillActive.asBoolean) return finish(false, "cancelled")
            val current = getAppDisplayId(packageName, "ensure")
            if (!stillActive.asBoolean) return finish(false, "cancelled-after-query")
            when (stability.observe(current, SystemClock.uptimeMillis())) {
                DisplayPinStability.Result.UNKNOWN -> return finish(true, "query-unavailable")
                DisplayPinStability.Result.READY -> return finish(true, if (requireStable) "stable-on-display" else "on-display")
                DisplayPinStability.Result.MOVE -> {
                    Ln.w("ensureAppOnDisplay: $packageName drifted to display $current (expected $displayId), trying to move it back")
                    val moved = repinAppToDisplay(packageName, displayId, stillActive)
                    if (!stillActive.asBoolean) return finish(false, "cancelled-after-repin")
                    if (!moved || !requireStable) return finish(moved, "repin")
                }
                DisplayPinStability.Result.TIMEOUT -> {
                    // Preserve the old lenient no-task behavior outside verified cold launches.
                    return finish(!requireStable, if (requireStable) "stable-timeout" else "no-task-timeout")
                }
                DisplayPinStability.Result.WAIT -> Unit
            }
            SystemClock.sleep(APP_PIN_POLL_INTERVAL_MS)
        }
    }

    /**
     * 把 [packageName] 的任务拉回 [displayId]：
     * 1. moveRootTaskToDisplay / moveStackToDisplay（hidden API，shell 有 MANAGE_ACTIVITY_TASKS）
     * 2. am display move-stack 命令兜底
     * 3. 重新投放 launch intent（强制 FULLSCREEN），让系统 reparent 现有任务
     */
    @JvmStatic
    fun repinAppToDisplay(packageName: String, displayId: Int): Boolean =
        repinAppToDisplay(packageName, displayId, BooleanSupplier { true })

    @JvmStatic
    fun repinAppToDisplay(packageName: String, displayId: Int, stillActive: BooleanSupplier): Boolean {
        if (!stillActive.asBoolean) return false
        val moved = moveAppTaskToDisplay(packageName, displayId, stillActive)
        if (!stillActive.asBoolean) return false
        if (moved) {
            SystemClock.sleep(REPIN_SETTLE_MS)
            if (!stillActive.asBoolean) return false
            val current = getAppDisplayId(packageName, "repin-after-move")
            if (!stillActive.asBoolean) return false
            Ln.i("repinAppToDisplay: after move package=$packageName expected=$displayId observed=$current")
            if (current == null || current == displayId) {
                Ln.i("repinAppToDisplay: $packageName moved back to display $displayId")
                return true
            }
        }
        if (!stillActive.asBoolean) return false
        val pm = FakeContext.get().packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            if (startActivity(intent, displayId, forceFullscreen = true, stillActive = stillActive)) {
                SystemClock.sleep(REPIN_SETTLE_MS)
                if (!stillActive.asBoolean) return false
                val current = getAppDisplayId(packageName, "repin-after-relaunch")
                if (!stillActive.asBoolean) return false
                Ln.i("repinAppToDisplay: after relaunch package=$packageName expected=$displayId observed=$current")
                if (current == null || current == displayId) {
                    Ln.i("repinAppToDisplay: $packageName relaunched onto display $displayId")
                    return true
                }
            }
        }
        Ln.e("repinAppToDisplay: failed to pin $packageName on display $displayId")
        return false
    }

    /**
     * 返回 [packageName] 最近任务所在的 displayId。
     * null = 无法判断（API < Q / 反射失败 / 异常）；[DISPLAY_NO_TASK] = 无运行中任务。
     * 取最近任务而非任意任务：漂移时新任务落在主屏，虚拟屏上可能残留旧任务。
     */
    private fun getAppDisplayId(packageName: String, diagnostic: String? = null): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || taskDisplayIdField == null) {
            if (diagnostic != null) Ln.w("taskLocation: phase=$diagnostic package=$packageName query=unavailable sdk=${Build.VERSION.SDK_INT} field=${taskDisplayIdField != null}")
            return null
        }
        return runCatching {
            val task = findRecentTask(packageName, diagnostic) ?: return@runCatching DISPLAY_NO_TASK
            getTaskDisplayId(task).takeIf { it >= 0 }.also { result ->
                if (diagnostic != null && result == null) Ln.w("taskLocation: phase=$diagnostic package=$packageName query=invalid-display")
            }
        }.onFailure {
            if (diagnostic != null) Ln.w("taskLocation: phase=$diagnostic package=$packageName query=failed", it)
        }.getOrNull()
    }

    private fun findRecentTask(packageName: String, diagnostic: String? = null): ActivityManager.RunningTaskInfo? {
        val am = FakeContext.get().getSystemService(ActivityManager::class.java) ?: run {
            if (diagnostic != null) Ln.w("taskLocation: phase=$diagnostic package=$packageName query=no-service")
            return null
        }
        @Suppress("DEPRECATION")
        val tasks = am.getRunningTasks(100)
        val matching = tasks.filter { task ->
            task.topActivity?.packageName == packageName
                    || task.baseActivity?.packageName == packageName
        }
        if (diagnostic != null) {
            val snapshot = matching.joinToString("; ") { task ->
                "task=${task.taskId},stack=${TaskDisplayMoveTarget.resolve(task, false)},display=${getTaskDisplayId(task)}" +
                        ",top=${task.topActivity},base=${task.baseActivity}"
            }
            Ln.i("taskLocation: phase=$diagnostic package=$packageName tasks=${tasks.size} matches=${matching.size} [$snapshot]")
        }
        return matching.firstOrNull()
    }

    private fun moveAppTaskToDisplay(packageName: String, displayId: Int, stillActive: BooleanSupplier): Boolean {
        if (!stillActive.asBoolean) return false
        val task = findRecentTask(packageName, "repin-before-move") ?: run {
            Ln.w("moveAppTaskToDisplay: no running task of $packageName")
            return false
        }
        val method = moveTaskToDisplayMethod
        val useRootTask = method?.name == "moveRootTaskToDisplay" ||
                (method == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        // Android 10/11 moveStackToDisplay 和 am display move-stack 收的是 stackId，
        // 不能拿 taskId 猜；编号碰巧属于别的栈时会误移无关应用。
        val moveId = TaskDisplayMoveTarget.resolve(task, useRootTask) ?: run {
            Ln.w("moveAppTaskToDisplay: cannot resolve ${if (useRootTask) "root task" else "stack"} of $packageName")
            return false
        }
        method?.let {
            runCatching {
                if (!stillActive.asBoolean) return false
                method.invoke(activityTaskManager, moveId, displayId)
                Ln.i("moveAppTaskToDisplay: ${method.name}($moveId, $displayId) invoked")
                return stillActive.asBoolean
            }.onFailure { Ln.w("moveAppTaskToDisplay: ${method.name} failed", it) }
        }
        return moveTaskViaAmCommand(moveId, displayId, stillActive)
    }

    private val activityTaskManager by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, "activity_task")
            Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure { Ln.w("activityTaskManager: reflection failed", it) }.getOrNull()
    }

    // API 31+: moveRootTaskToDisplay；API 29/30: moveStackToDisplay
    private val moveTaskToDisplayMethod by lazy {
        val atm = activityTaskManager ?: return@lazy null
        sequenceOf("moveRootTaskToDisplay", "moveStackToDisplay").firstNotNullOfOrNull { name ->
            runCatching {
                atm.javaClass.getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()
        } ?: run {
            Ln.w("moveTaskToDisplayMethod: no candidate method found")
            null
        }
    }

    private fun moveTaskViaAmCommand(moveId: Int, displayId: Int, stillActive: BooleanSupplier): Boolean {
        if (!stillActive.asBoolean) return false
        return try {
            val args = arrayOf("am", "display", "move-stack", moveId.toString(), displayId.toString())
            Ln.i("moveTaskViaAmCommand: exec: ${args.joinToString(" ")}")
            if (!stillActive.asBoolean) return false
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stderr.isNotEmpty()) Ln.w("moveTaskViaAmCommand: am stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("moveTaskViaAmCommand: am exited with code $exitCode")
                return false
            }
            stillActive.asBoolean
        } catch (e: Exception) {
            Ln.e("moveTaskViaAmCommand: am command failed", e)
            false
        }
    }

    private val taskDisplayIdField by lazy {
        val resolved = runCatching {
            var cls: Class<*>? = ActivityManager.RunningTaskInfo::class.java
            var field: java.lang.reflect.Field? = null
            while (cls != null && field == null) {
                field = runCatching { cls.getDeclaredField("displayId") }.getOrNull()
                cls = cls.superclass
            }
            field?.also { it.isAccessible = true }
        }.onFailure { Ln.w("taskDisplayIdField: reflection failed", it) }.getOrNull()
        if (resolved == null) Ln.w("taskDisplayIdField: unavailable sdk=${Build.VERSION.SDK_INT}")
        else Ln.i("taskDisplayIdField: resolved ${resolved.declaringClass.name}.${resolved.name}")
        resolved
    }

    private fun getTaskDisplayId(task: ActivityManager.RunningTaskInfo): Int =
        runCatching { taskDisplayIdField?.getInt(task) ?: -1 }.getOrDefault(-1)

    private fun startViaAmCommand(intent: Intent, displayId: Int, stillActive: BooleanSupplier): Boolean {
        if (!stillActive.asBoolean) return false
        try {
            val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
            val args = if (displayId == Display.DEFAULT_DISPLAY) {
                arrayOf("am", "start", intentUri)
            } else {
                arrayOf("am", "start", "--display", displayId.toString(), intentUri)
            }
            Ln.i("startViaAmCommand: displayId=$displayId, exec: ${args.joinToString(" ")}")
            if (!stillActive.asBoolean) return false
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            // am start 输出量极小（远小于管道缓冲），先 waitFor 再读不会死锁
            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stdout.isNotEmpty()) Ln.i("startViaAmCommand: am stdout: $stdout")
            if (stderr.isNotEmpty()) Ln.w("startViaAmCommand: am stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("startViaAmCommand: am exited with code $exitCode")
                return false
            }
            Ln.i("startViaAmCommand: success (exitCode=0)")
            return stillActive.asBoolean
        } catch (e: Exception) {
            Ln.e("startViaAmCommand: am command fallback failed", e)
            return false
        }
    }
}
