package com.aliothmoon.maafw.bridge;

/**
 * 安卓自动触摸的最短按住时长（前台、后台共用）
 *
 * 游戏一般在每帧开头读一次触摸状态：按下与抬起落在同一帧间隙里，那一帧看到的就是「什么都没发生」。
 * 帧率不超过 {@link #PAD_AT_OR_BELOW_FPS} 时补至 {@link #FRAMES} 帧，30 FPS 对应 100ms，
 * 更低帧率最多补至 {@link #CAP_MS}。帧率更高或取不到时不补等。
 * 仅补足已经按住时间与目标的差额，不缩短 Pipeline 配置的长按或滑动。
 *
 * 参数按游戏验证；可在 {@link DriverClass#touchUp} 日志核对帧率、已按住时间与实际补时。
 */
final class TapHoldPolicy {

    static final float PAD_AT_OR_BELOW_FPS = 30f;
    static final long CAP_MS = 300;
    static final int FRAMES = 3;

    private TapHoldPolicy() {
    }

    static long minHoldMs(float fps) {
        if (!(fps > 0f) || fps > PAD_AT_OR_BELOW_FPS) {
            return 0L;
        }
        long frames = (long) Math.ceil(FRAMES * 1000.0 / fps);
        return Math.min(CAP_MS, frames);
    }

    /** 还需补等多久才能抬起；已按够时为 0 */
    static long padMs(long heldMs, float fps) {
        return Math.max(0L, minHoldMs(fps) - heldMs);
    }
}
