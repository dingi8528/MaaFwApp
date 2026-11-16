package com.aliothmoon.maafw.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TapHoldPolicyTest {

    @Test
    fun `unknown and invalid fps keep the 200 ms floor`() {
        for (fps in listOf(-1f, 0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(200L, TapHoldPolicy.minHoldMs(fps))
            assertEquals(150L, TapHoldPolicy.padMs(50, fps))
        }
    }

    @Test
    fun `high fps keeps the 200 ms floor`() {
        assertEquals(200L, TapHoldPolicy.minHoldMs(60f))
        assertEquals(200L, TapHoldPolicy.minHoldMs(Float.MAX_VALUE))
        assertEquals(144L, TapHoldPolicy.padMs(56, 60f))
    }

    @Test
    fun `fps jitter around 30 never disables padding`() {
        for (fps in listOf(29.97f, 30f, 30.001f, 31f)) {
            assertEquals(200L, TapHoldPolicy.minHoldMs(fps))
            assertEquals(144L, TapHoldPolicy.padMs(56, fps))
        }
    }

    @Test
    fun `low fps spans three frames`() {
        assertEquals(200L, TapHoldPolicy.minHoldMs(20f))
        assertEquals(200L, TapHoldPolicy.minHoldMs(15.001f))
        assertEquals(200L, TapHoldPolicy.minHoldMs(15f))
        assertEquals(201L, TapHoldPolicy.minHoldMs(14.999f))
        assertEquals(273L, TapHoldPolicy.minHoldMs(11f))
        assertEquals(300L, TapHoldPolicy.minHoldMs(10f))
    }

    @Test
    fun `very low fps caps at 500 ms`() {
        assertEquals(492L, TapHoldPolicy.minHoldMs(6.1f))
        assertEquals(500L, TapHoldPolicy.minHoldMs(6f))
        assertEquals(500L, TapHoldPolicy.minHoldMs(5.999f))
        assertEquals(500L, TapHoldPolicy.minHoldMs(3f))
        assertEquals(500L, TapHoldPolicy.minHoldMs(Float.MIN_VALUE))
    }

    @Test
    fun `pad only covers what is still missing`() {
        assertEquals(273L - 56L, TapHoldPolicy.padMs(56, 11f))
        assertEquals(100L, TapHoldPolicy.padMs(100, 30f))
        assertEquals(1L, TapHoldPolicy.padMs(199, 60f))
        assertEquals(0L, TapHoldPolicy.padMs(200, 30f))
        assertEquals(0L, TapHoldPolicy.padMs(200, -1f))
        assertEquals(200L, TapHoldPolicy.padMs(300, 3f))
        assertEquals(1L, TapHoldPolicy.padMs(499, 3f))
        assertEquals(0L, TapHoldPolicy.padMs(500, 3f))
        assertEquals(0L, TapHoldPolicy.padMs(1500, 3f))
    }
}
