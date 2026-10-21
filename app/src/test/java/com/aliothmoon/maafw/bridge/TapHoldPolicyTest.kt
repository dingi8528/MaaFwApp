package com.aliothmoon.maafw.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TapHoldPolicyTest {

    @Test
    fun `unknown fps does not pad`() {
        assertEquals(0L, TapHoldPolicy.minHoldMs(-1f))
        assertEquals(0L, TapHoldPolicy.minHoldMs(0f))
        assertEquals(0L, TapHoldPolicy.minHoldMs(Float.NaN))
        assertEquals(0L, TapHoldPolicy.minHoldMs(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `above 30 fps does not pad`() {
        assertEquals(0L, TapHoldPolicy.minHoldMs(30.001f))
        assertEquals(0L, TapHoldPolicy.minHoldMs(60f))
        assertEquals(0L, TapHoldPolicy.padMs(56, 60f))
    }

    @Test
    fun `30 fps is included and pads to 100 ms`() {
        assertEquals(100L, TapHoldPolicy.minHoldMs(30f))
        assertEquals(44L, TapHoldPolicy.padMs(56, 30f))
        assertEquals(101L, TapHoldPolicy.minHoldMs(29.97f))
    }

    @Test
    fun `low fps spans three frames`() {
        assertEquals(150L, TapHoldPolicy.minHoldMs(20f))
        assertEquals(200L, TapHoldPolicy.minHoldMs(15f))
        assertEquals(273L, TapHoldPolicy.minHoldMs(11f))
    }

    @Test
    fun `very low fps caps at 300 ms`() {
        assertEquals(300L, TapHoldPolicy.minHoldMs(10f))
        assertEquals(300L, TapHoldPolicy.minHoldMs(3f))
        assertEquals(300L, TapHoldPolicy.minHoldMs(Float.MIN_VALUE))
    }

    @Test
    fun `pad only covers what is still missing`() {
        assertEquals(273L - 56L, TapHoldPolicy.padMs(56, 11f))
        assertEquals(0L, TapHoldPolicy.padMs(100, 30f))
        assertEquals(0L, TapHoldPolicy.padMs(200, 30f))
        assertEquals(0L, TapHoldPolicy.padMs(300, 3f))
        assertEquals(0L, TapHoldPolicy.padMs(500, 3f))
        assertEquals(0L, TapHoldPolicy.padMs(1500, 3f))
    }
}
