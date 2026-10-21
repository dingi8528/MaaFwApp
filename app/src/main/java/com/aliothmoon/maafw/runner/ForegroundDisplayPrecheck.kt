package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.util.ScreenSize

/** Read the primary display when Start is pressed; the home resolution controls also allow portrait. */
class ForegroundDisplayPrecheck(private val currentSize: () -> Pair<Int, Int>?) : RunPrecheck {
    override suspend fun evaluate(ctx: RunContext): Verdict {
        if (ctx.runMode != RunMode.FOREGROUND) return Verdict.Pass
        val size = runCatching(currentSize).getOrNull()
        return if (size != null && ScreenSize.isLandscape16x9(size.first, size.second)) {
            Verdict.Pass
        } else {
            Verdict.Block(uiTextOf(R.string.runner_foreground_landscape_required))
        }
    }
}
