package com.aliothmoon.maafw.remote.internal

/** Bounded startup guard: a brief launcher activity on the VD is not yet a stable game task. */
internal class DisplayPinStability(
    private val targetDisplay: Int,
    private val startedAtMs: Long,
    private val timeoutMs: Long,
    private val stableForMs: Long,
) {
    enum class Result { WAIT, READY, MOVE, UNKNOWN, TIMEOUT }

    private var stableSinceMs: Long? = null

    fun observe(displayId: Int?, nowMs: Long): Result {
        if (displayId == null) return Result.UNKNOWN
        if (displayId == targetDisplay) {
            val since = stableSinceMs ?: nowMs.also { stableSinceMs = it }
            if (nowMs - since >= stableForMs) return Result.READY
        } else {
            stableSinceMs = null
        }
        if (nowMs - startedAtMs >= timeoutMs) return Result.TIMEOUT
        return if (displayId >= 0 && displayId != targetDisplay) Result.MOVE else Result.WAIT
    }
}
