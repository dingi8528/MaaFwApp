package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPinStabilityTest {
    private fun guard(stableMs: Long = 2_000) = DisplayPinStability(4, 100, 10_000, stableMs)

    @Test
    fun `brief launcher presence cannot hide SDK display jump`() {
        val guard = guard()
        assertEquals(DisplayPinStability.Result.WAIT, guard.observe(4, 100))
        assertEquals(DisplayPinStability.Result.MOVE, guard.observe(0, 600))
        assertEquals(DisplayPinStability.Result.WAIT, guard.observe(4, 1_600))
        assertEquals(DisplayPinStability.Result.WAIT, guard.observe(4, 3_100))
        assertEquals(DisplayPinStability.Result.READY, guard.observe(4, 3_600))
    }

    @Test
    fun `a second jump resets continuous stability`() {
        val guard = guard()
        guard.observe(4, 100)
        guard.observe(0, 600)
        guard.observe(4, 1_600)
        assertEquals(DisplayPinStability.Result.MOVE, guard.observe(0, 3_100))
        assertEquals(DisplayPinStability.Result.WAIT, guard.observe(4, 4_100))
        assertEquals(DisplayPinStability.Result.READY, guard.observe(4, 6_100))
    }

    @Test
    fun `unknown remains lenient and no-task waits only within total budget`() {
        val guard = guard()
        assertEquals(DisplayPinStability.Result.UNKNOWN, guard.observe(null, 100))
        assertEquals(DisplayPinStability.Result.WAIT, guard.observe(-2, 9_600))
        assertEquals(DisplayPinStability.Result.TIMEOUT, guard.observe(-2, 10_100))
    }

    @Test
    fun `continued drift and late unstable arrival cannot restart total deadline`() {
        val guard = guard()
        assertEquals(DisplayPinStability.Result.MOVE, guard.observe(0, 9_100))
        assertEquals(DisplayPinStability.Result.WAIT, guard.observe(4, 9_600))
        assertEquals(DisplayPinStability.Result.TIMEOUT, guard.observe(4, 10_100))
        assertEquals(DisplayPinStability.Result.TIMEOUT, guard.observe(0, 10_600))
    }

    @Test
    fun `existing or foreground app retains immediate success behavior`() {
        assertEquals(DisplayPinStability.Result.READY, guard(0).observe(4, 100))
    }
}
