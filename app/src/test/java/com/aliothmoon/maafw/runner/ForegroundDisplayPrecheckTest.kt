package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.util.ScreenSize
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDisplayPrecheckTest {
    private val plan = RunPlan(
        projectName = "demo", projectVersion = "1", controller = ControllerDefinition(),
        resource = ResourceDefinition("base", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"), tasks = emptyList(),
    )

    private fun context(mode: RunMode = RunMode.FOREGROUND) =
        RunContext(RunTrigger.Overlay, mode, plan, journal = DiscardingRunJournal)

    @Test
    fun `portrait 16 by 9 can configure home but cannot start foreground input`() = runTest {
        assertTrue(ScreenSize.isAspect16x9(1080, 1920))
        val block = ForegroundDisplayPrecheck { 1080 to 1920 }.evaluate(context()) as Verdict.Block
        assertTrue(block.reason.isResource(R.string.runner_foreground_landscape_required))
    }

    @Test
    fun `only landscape 16 by 9 passes and each Start reads the latest orientation`() = runTest {
        var size = 1920 to 1080
        val precheck = ForegroundDisplayPrecheck { size }
        assertEquals(Verdict.Pass, precheck.evaluate(context()))
        size = 1080 to 1920
        assertTrue(precheck.evaluate(context()) is Verdict.Block)
        size = 2340 to 1080
        assertTrue(precheck.evaluate(context()) is Verdict.Block)
    }

    @Test
    fun `unreadable primary blocks foreground but background never reads it`() = runTest {
        var reads = 0
        val precheck = ForegroundDisplayPrecheck { reads++; error("display unavailable") }
        assertEquals(Verdict.Pass, precheck.evaluate(context(RunMode.BACKGROUND)))
        assertEquals(0, reads)
        assertTrue(precheck.evaluate(context()) is Verdict.Block)
        assertEquals(1, reads)
        assertTrue(ForegroundDisplayPrecheck { null }.evaluate(context()) is Verdict.Block)
    }
}
