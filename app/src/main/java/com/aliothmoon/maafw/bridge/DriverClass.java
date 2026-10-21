package com.aliothmoon.maafw.bridge;


import android.content.pm.PackageInfo;
import android.os.SystemClock;

import com.aliothmoon.maafw.remote.internal.ActivityUtils;
import com.aliothmoon.maafw.remote.internal.AppWatchdog;
import com.aliothmoon.maafw.remote.internal.GameFpsMonitor;
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager;
import com.aliothmoon.maafw.remote.internal.PrimaryInputGuard;
import com.aliothmoon.maafw.third.FakeContext;
import com.aliothmoon.maafw.third.Ln;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import timber.log.Timber;

/**
 * upcall driver
 */
public final class DriverClass {

    private static final String TAG = "DriverClass";
    private static final int FRAME_WAIT_TIMEOUT_MS = 5000;
    private static final int FRAME_WAIT_INTERVAL_MS = 50;

    /** 无障碍写文本前拿它校验焦点输入框属于谁，免得写进别的应用 */
    private static volatile String targetPackage;

    /** 各 contact 按下的时刻（uptime ms），抬起时据此补足最短按住时长，见 {@link TapHoldPolicy} */
    private static final ConcurrentHashMap<Long, Long> downAt = new ConcurrentHashMap<>();

    /** 按下后动过的 contact：滑动本就按得久，日志里与点击分开看 */
    private static final ConcurrentHashMap<Long, Boolean> moved = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> primaryKeys = new ConcurrentHashMap<>();

    private DriverClass() {
    }

    public static boolean startApp(String packageName, int displayId, boolean forceStop) {
        if (!allowInput(displayId)) return false;
        Ln.i(TAG + String.format(Locale.US, "%s %d %b", packageName, displayId, forceStop));
        logTargetAppInfo(packageName, displayId, forceStop);
        targetPackage = ActivityUtils.packageNameOf(packageName);
        if (displayId == PrimaryDisplayManager.DISPLAY_ID) {
            return ActivityUtils.startApp(packageName, displayId, forceStop);
        }
        String target = ActivityUtils.packageNameOf(packageName);
        // Tell the watchdog before launching: a game SDK may leave the VD before its first poll.
        // The watchdog accepts registrations only for the current background run's display.
        AppWatchdog.TargetToken token = AppWatchdog.registerTarget(target, displayId);
        if (token == null) return false;
        BooleanSupplier stillActive = () -> AppWatchdog.isTargetActive(token);
        boolean ret = ActivityUtils.startApp(packageName, displayId, forceStop, true, stillActive);
        if (ret) {
            // 部分 ROM（如 One UI）会把游戏从虚拟屏挪回主屏，启动后校验并尝试拉回；
            // 拉不回则快速失败，避免识别对着虚拟屏空转
            // 这里比对的是包名，PI 给的可能是 "包名/Activity"，先拆
            ret = ActivityUtils.ensureAppOnDisplay(target, displayId, stillActive);
            if (!ret) {
                Ln.e(TAG + ": " + target + " could not be pinned on display " + displayId);
            }
        }
        if (ret && stillActive.getAsBoolean()) {
            awaitFirstFrame(stillActive);
            ret = stillActive.getAsBoolean();
            if (ret) GameFpsMonitor.start(target);
        } else {
            ret = false;
        }
        if (!ret) AppWatchdog.clearTarget(token);
        return ret;
    }

    public static boolean inputText(byte[] utf8, int displayId) {
        if (!allowInput(displayId)) return false;
        return TextInputDispatcher.input(new String(utf8, StandardCharsets.UTF_8), displayId, targetPackage);
    }

    public static boolean stopApp(String packageName, int displayId) {
        Ln.i(TAG + String.format(Locale.US, ": stopApp %s displayId=%d", packageName, displayId));
        return ActivityUtils.forceStop(packageName, displayId);
    }

    private static void logTargetAppInfo(String rawSpec, int displayId, boolean forceStop) {
        String context = " displayId=" + displayId + " forceStop=" + forceStop;
        try {
            String target = ActivityUtils.packageNameOf(rawSpec);
            PackageInfo info = FakeContext.get().getPackageManager().getPackageInfo(target, 0);
            Ln.i(TAG + ": target app spec=" + rawSpec + " package=" + target
                    + " version=" + info.versionName + " (" + info.getLongVersionCode() + ")"
                    + " uid=" + (info.applicationInfo == null ? "unknown" : info.applicationInfo.uid)
                    + context);
        } catch (Throwable e) {
            Ln.w(TAG + ": target app info unavailable spec=" + rawSpec + context, e);
        }
    }

    private static void awaitFirstFrame(BooleanSupplier stillActive) {
        long baseline = NativeBridgeLib.getFrameCount();
        int elapsed = 0;
        while (stillActive.getAsBoolean() && NativeBridgeLib.getFrameCount() <= baseline && elapsed < FRAME_WAIT_TIMEOUT_MS) {
            try {
                Thread.sleep(FRAME_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            elapsed += FRAME_WAIT_INTERVAL_MS;
        }
        if (elapsed >= FRAME_WAIT_TIMEOUT_MS) {
            Ln.w(TAG + ": awaitFirstFrame timed out after " + FRAME_WAIT_TIMEOUT_MS + "ms");
        }
    }

    /* 热路径：一次 Swipe / MultiSwipe 会连发几十次。坐标由框架记，这里不打日志 */

    /**
     * 系统丢掉的 DOWN 照样报成功：ADB 控制器只管写出去，点击被吞也是成功，PI 按这个写；
     * 报失败的话 MaaFramework 直接判节点失败，不会再识别一轮重点
     */
    public static synchronized boolean touchDown(int x, int y, int contact, int displayId) {
        if (!allowInput(displayId)) return false;
        InputControlUtils.TouchResult result = InputControlUtils.down(x, y, contact, displayId);
        if (result == InputControlUtils.TouchResult.DELIVERED) {
            long key = contactKey(contact, displayId);
            downAt.put(key, SystemClock.uptimeMillis());
            moved.remove(key);
        }
        return result != InputControlUtils.TouchResult.FAILED;
    }

    public static synchronized boolean touchMove(int x, int y, int contact, int displayId) {
        if (!allowInput(displayId)) return false;
        moved.put(contactKey(contact, displayId), Boolean.TRUE);
        return InputControlUtils.move(x, y, contact, displayId);
    }

    /**
     * 抬起前补足最短按住时长：低帧率下按下与抬起落进同一帧间隙，游戏会整个吞掉这次点击
     *
     * 每次都记一行，用来实测按住时长、帧率与漏点的关系，参数定下来后再收
     */
    public static synchronized boolean touchUp(int x, int y, int contact, int displayId) {
        if (!allowInput(displayId)) return false;
        long key = contactKey(contact, displayId);
        Long down = downAt.get(key);
        boolean swipe = moved.containsKey(key);
        if (down != null) {
            long held = SystemClock.uptimeMillis() - down;
            float fps = GameFpsMonitor.currentFps();
            long minHold = TapHoldPolicy.minHoldMs(fps);
            long pad = TapHoldPolicy.padMs(held, fps);
            if (pad > 0) {
                SystemClock.sleep(pad);
            }
            Ln.i(TAG + String.format(Locale.US,
                    ": touchUp %s contact=%d at=(%d,%d) held=%dms fps=%.1f minHold=%dms pad=%dms display=%d",
                    swipe ? "swipe" : "tap", contact, x, y, held, fps, minHold, pad, displayId));
        }
        // Rotation may also happen while the minimum tap hold is being padded.
        if (!allowInput(displayId)) return false;
        try {
            return InputControlUtils.up(x, y, contact, displayId);
        } finally {
            downAt.remove(key);
            moved.remove(key);
        }
    }

    public static synchronized boolean keyDown(int keyCode, int displayId) {
        if (!allowInput(displayId)) return false;
        Ln.i(TAG + ": keyDown(keyCode=" + keyCode + ", displayId=" + displayId + ")");
        boolean result = InputControlUtils.keyDown(keyCode, displayId);
        if (result && displayId == PrimaryDisplayManager.DISPLAY_ID) primaryKeys.put(keyCode, Boolean.TRUE);
        Ln.i(TAG + ": keyDown result=" + result);
        return result;
    }

    public static synchronized boolean keyUp(int keyCode, int displayId) {
        if (!allowInput(displayId)) return false;
        Ln.i(TAG + ": keyUp(keyCode=" + keyCode + ", displayId=" + displayId + ")");
        boolean result = InputControlUtils.keyUp(keyCode, displayId);
        if (result && displayId == PrimaryDisplayManager.DISPLAY_ID) primaryKeys.remove(keyCode);
        Ln.i(TAG + ": keyUp result=" + result);
        return result;
    }

    private static synchronized boolean allowInput(int displayId) {
        if (PrimaryInputGuard.allowsInput(displayId)) return true;
        cancelPrimaryInput();
        return false;
    }

    private static long contactKey(int contact, int displayId) {
        return ((long) displayId << 32) | (contact & 0xffffffffL);
    }

    private static boolean isPrimaryContact(long key) {
        return (int) (key >> 32) == PrimaryDisplayManager.DISPLAY_ID;
    }

    /** Cancel a DOWN without turning it into a click on the newly visible application. */
    public static synchronized void cancelPrimaryInput() {
        try {
            if (downAt.keySet().stream().anyMatch(DriverClass::isPrimaryContact)) {
                InputControlUtils.cancelActiveGesture(PrimaryDisplayManager.DISPLAY_ID);
            }
            for (Integer key : primaryKeys.keySet()) {
                InputControlUtils.cancelKey(key, PrimaryDisplayManager.DISPLAY_ID);
            }
        } catch (Throwable error) {
            Ln.w(TAG + ": cancelling primary input failed", error);
        } finally {
            downAt.keySet().removeIf(DriverClass::isPrimaryContact);
            moved.keySet().removeIf(DriverClass::isPrimaryContact);
            primaryKeys.clear();
        }
    }
}
