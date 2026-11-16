package com.aliothmoon.maafw.bridge;

/**
 * 安卓自动触摸的最短按住时长（前台、后台共用）
 *
 * 游戏一般在每帧开头读一次触摸状态：按下与抬起落在同一帧间隙里，那一帧看到的就是「什么都没发生」。
 * 所有帧率（含未知、0 和无效读数）至少按住 {@link #FLOOR_MS}。
 * 有效帧率下按 {@link #FRAMES} 帧计算目标，限制在 {@link #FLOOR_MS} 至 {@link #CAP_MS} 之间，
 * 不设 30 FPS 的补时开关，避免读数在阈值附近抖动时退回短按。
 * 仅补足已经按住时间与目标的差额，不缩短 Pipeline 配置的长按或滑动。
 *
 * 可在 {@link DriverClass#touchUp} 日志核对帧率、已按住时间与实际补时；参数效果仍需真机验证。
 */
final class TapHoldPolicy {

    static final long FLOOR_MS = 200;
    static final long CAP_MS = 500;
    static final int FRAMES = 3;

    private TapHoldPolicy() {
    }

    static long minHoldMs(float fps) {
        if (!(fps > 0f) || !Float.isFinite(fps)) {
            return FLOOR_MS;
        }
        long frames = (long) Math.ceil(FRAMES * 1000.0 / fps);
        return Math.max(FLOOR_MS, Math.min(CAP_MS, frames));
    }

    /** 还需补等多久才能抬起；已按够时为 0 */
    static long padMs(long heldMs, float fps) {
        return Math.max(0L, minHoldMs(fps) - heldMs);
    }
}
