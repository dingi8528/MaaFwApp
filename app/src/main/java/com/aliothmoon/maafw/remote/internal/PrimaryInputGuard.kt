package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.third.Ln

/**
 * A native controller's touch bounds cannot follow a rotation. Once invalidated, reject all
 * remaining primary input until the next run binds a fresh capture size, even if it rotates back.
 */
object PrimaryInputGuard {
    private class Binding(
        val size: Pair<Int, Int>,
        val onChanged: (Pair<Int, Int>?) -> Unit,
        var invalidated: Boolean = false,
    )

    private val lock = Any()
    private var binding: Binding? = null

    fun bind(width: Int, height: Int, onChanged: (Pair<Int, Int>?) -> Unit) {
        synchronized(lock) { binding = Binding(width to height, onChanged) }
    }

    /** Returns whether a primary run had been bound, so its injected DOWN events can be cancelled. */
    fun clear(): Boolean = synchronized(lock) {
        val existed = binding != null
        binding = null
        existed
    }

    @JvmStatic
    fun allowsInput(displayId: Int): Boolean {
        if (displayId != PrimaryDisplayManager.DISPLAY_ID) return true
        val expected = synchronized(lock) { binding?.takeUnless { it.invalidated } } ?: return false
        // Do not hold our lock while reading the capture manager or stopping the runner.
        val actual = runCatching { PrimaryDisplayManager.getCaptureSize() }.getOrNull()
        val accepted = synchronized(lock) {
            if (binding !== expected || expected.invalidated) return false
            if (actual == expected.size) true else {
                expected.invalidated = true
                false
            }
        }
        if (!accepted) runCatching { expected.onChanged(actual) }
            .onFailure { Ln.w("PrimaryInputGuard: failed to request run stop", it) }
        return accepted
    }
}
