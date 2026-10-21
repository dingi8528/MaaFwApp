package com.aliothmoon.maafw.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** A primary cancellation must not erase the real virtual display gesture's slots. */
class InputControlGestureDisplayTest {
    @After
    fun tearDown() {
        set("slots", emptyList<TouchPointerSequence.Pointer>())
        set("gestureDisplayId", null)
        set("gestureDownTime", 0L)
    }

    @Test
    fun `primary cancellation leaves virtual pointers and their down time intact`() {
        val pointers = listOf(TouchPointerSequence.Pointer(0, 100f, 200f))
        set("slots", pointers)
        set("gestureDisplayId", 7)
        set("gestureDownTime", 50L)
        InputControlUtils.cancelActiveGesture(0)
        assertSame(pointers, get("slots"))
        assertEquals(7, get("gestureDisplayId"))
        assertEquals(50L, get("gestureDownTime"))
    }

    @Test
    fun `move and up on another display cannot reuse virtual pointers`() {
        val pointers = listOf(TouchPointerSequence.Pointer(0, 100f, 200f))
        set("slots", pointers)
        set("gestureDisplayId", 7)
        assertFalse(InputControlUtils.move(110, 210, 0, 0))
        assertFalse(InputControlUtils.up(110, 210, 0, 0))
        assertSame(pointers, get("slots"))
    }

    private fun get(name: String): Any? = InputControlUtils::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(null)
    }

    private fun set(name: String, value: Any?) = InputControlUtils::class.java.getDeclaredField(name).run {
        isAccessible = true
        set(null, value)
    }
}
