package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameFpsAdvisorTest {

    private fun samples(lowCount: Int, highFps: Float = 60f): List<Float> =
        List(GameFpsAdvisor.DEFAULT_WINDOW_SIZE) { index ->
            if (index < lowCount) 25f else highFps
        }

    @Test
    fun `fewer than eighty percent low samples do not advise`() {
        val advisor = GameFpsAdvisor()

        samples(lowCount = 11).forEach { assertNull(advisor.onSample(it)) }
    }

    @Test
    fun `eighty percent low samples advise with the window median`() {
        val advisor = GameFpsAdvisor()
        var advice: GameFpsAdvisor.Advice? = null

        samples(lowCount = 12).forEach { sample -> advice = advisor.onSample(sample) ?: advice }
        val result = advice ?: error("Expected low FPS advice")

        assertEquals(GameFpsAdvisor.Level.LOW, result.level)
        assertEquals(25f, result.medianFps, 0.001f)
    }

    @Test
    fun `low fps advises only once`() {
        val advisor = GameFpsAdvisor()
        var low: GameFpsAdvisor.Advice? = null

        samples(lowCount = 15).forEach { sample -> low = advisor.onSample(sample) ?: low }
        assertEquals(GameFpsAdvisor.Level.LOW, low!!.level)
        repeat(5) { assertNull(advisor.onSample(25f)) }

        samples(lowCount = 0, highFps = 40f).forEach { assertNull(advisor.onSample(it)) }
    }

    @Test
    fun `30 fps and above never advise`() {
        for (fps in listOf(30f, 31f, 40f, 49f, 50f, 60f)) {
            val advisor = GameFpsAdvisor()
            repeat(30) { assertNull("fps=$fps", advisor.onSample(fps)) }
        }
    }

    @Test
    fun `normal 30 fps jitter follows the displayed rounded fps`() {
        val advisor = GameFpsAdvisor()
        repeat(30) { assertNull(advisor.onSample(29.97f)) }
    }

    @Test
    fun `displayed 29 fps still advises`() {
        val advisor = GameFpsAdvisor()
        repeat(14) { assertNull(advisor.onSample(29.49f)) }
        assertEquals(29f, advisor.onSample(29.49f)!!.medianFps, 0.001f)
    }

    @Test
    fun `invalid fps never advises or throws`() {
        val advisor = GameFpsAdvisor()
        repeat(30) {
            for (fps in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f)) {
                assertNull(advisor.onSample(fps))
            }
        }
    }

    @Test
    fun `short idle streaks are ignored and longer streaks clear the window`() {
        val advisor = GameFpsAdvisor()
        samples(lowCount = 12).take(12).forEach { advisor.onSample(it) }
        repeat(3) { assertNull(advisor.onSample(0f)) }

        assertNull(advisor.onSample(25f))
        assertNull(advisor.onSample(25f))

        repeat(4) { advisor.onSample(0f) }
        repeat(14) { assertNull(advisor.onSample(25f)) }
        assertEquals(GameFpsAdvisor.Level.LOW, advisor.onSample(25f)!!.level)
    }
}
