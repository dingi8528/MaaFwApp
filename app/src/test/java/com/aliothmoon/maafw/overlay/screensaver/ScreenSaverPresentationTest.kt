package com.aliothmoon.maafw.overlay.screensaver

import android.app.Activity
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSaverPresentationTest {
    private class Host(brightness: Float = 0.6f, flags: Int = WindowManager.LayoutParams.FLAG_DIM_BEHIND) {
        val activity = mockk<Activity>()
        val window = mockk<Window>()
        val attrs = mockk<WindowManager.LayoutParams>()
        val windows = mockk<WindowManager>()
        val display = mockk<Display>()
        val decor = mockk<View>()

        init {
            attrs.screenBrightness = brightness
            attrs.flags = flags
            every { activity.isFinishing } returns false
            every { activity.isDestroyed } returns false
            every { activity.display } returns display
            every { display.displayId } returns Display.DEFAULT_DISPLAY
            every { activity.windowManager } returns windows
            every { windows.defaultDisplay } returns display
            every { activity.window } returns window
            every { window.decorView } returns decor
            every { decor.display } returns display
            every { decor.isAttachedToWindow } returns true
            every { decor.isShown } returns true
            every { decor.windowVisibility } returns View.VISIBLE
            every { window.attributes } returns attrs
            every { window.attributes = any() } just Runs
        }
    }

    @Test
    fun `show and hide adjust the existing window and restore its original state`() {
        val host = Host()
        val originalFlags = host.attrs.flags
        val presentation = ScreenSaverPresentation()
        presentation.bind(host.activity)

        assertTrue(presentation.show(32))
        assertTrue(presentation.isShowing.value)
        assertEquals(0.01f, host.attrs.screenBrightness, 0f)
        assertEquals(originalFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, host.attrs.flags)
        presentation.hide()
        assertFalse(presentation.isShowing.value)
        assertEquals(0.6f, host.attrs.screenBrightness, 0f)
        assertEquals(originalFlags, host.attrs.flags)
        verify(exactly = 0) { host.windows.addView(any(), any()) }
        verify(exactly = 0) { host.activity.createWindowContext(any(), any()) }
    }

    @Test
    fun `repeat show does not replace the saved brightness and repeat hide is harmless`() {
        val host = Host()
        val presentation = ScreenSaverPresentation()
        presentation.bind(host.activity)

        repeat(3) { assertTrue(presentation.show(32)) }
        presentation.hide()
        assertEquals(0.6f, host.attrs.screenBrightness, 0f)
        host.attrs.screenBrightness = 0.9f
        presentation.hide()
        assertEquals(0.9f, host.attrs.screenBrightness, 0f)
        verify(exactly = 2) { host.window.attributes = any() }

        assertTrue(presentation.show(32))
        presentation.hide()
        assertEquals(0.9f, host.attrs.screenBrightness, 0f)
    }

    @Test
    fun `keep screen on already present is retained and unrelated new flags are not lost`() {
        val host = Host(flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val presentation = ScreenSaverPresentation()
        presentation.bind(host.activity)
        assertTrue(presentation.show(32))
        host.attrs.flags = host.attrs.flags or WindowManager.LayoutParams.FLAG_FULLSCREEN

        presentation.hide()

        assertEquals(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN, host.attrs.flags)
    }

    @Test
    fun `changing Activity restores old window and starts new host without stale showing state`() {
        val old = Host(brightness = 0.4f)
        val current = Host(brightness = 0.8f)
        val presentation = ScreenSaverPresentation()
        presentation.bind(old.activity)
        assertTrue(presentation.show(32))

        presentation.bind(current.activity)
        assertFalse(presentation.isShowing.value)
        assertEquals(0.4f, old.attrs.screenBrightness, 0f)
        assertTrue(presentation.show(32))
        assertEquals(0.01f, current.attrs.screenBrightness, 0f)
        assertFalse(presentation.unbind(old.activity))
        assertTrue(presentation.isShowing.value)
        assertTrue(presentation.unbind(current.activity))
        assertFalse(presentation.isShowing.value)
        assertEquals(0.8f, current.attrs.screenBrightness, 0f)
        assertFalse(presentation.show(32))
    }

    @Test
    fun `rebind same host retains the original brightness until final hide`() {
        val host = Host()
        val presentation = ScreenSaverPresentation()
        presentation.bind(host.activity)
        assertTrue(presentation.show(32))
        presentation.bind(host.activity)
        assertTrue(presentation.isShowing.value)
        presentation.hide()
        assertEquals(0.6f, host.attrs.screenBrightness, 0f)
    }

    @Test
    fun `invisible host is refused and an active host becoming invisible restores the window`() {
        val host = Host()
        val presentation = ScreenSaverPresentation()
        presentation.bind(host.activity)
        assertTrue(presentation.show(32))
        every { host.decor.isShown } returns false

        assertFalse(presentation.show(32))
        assertFalse(presentation.isShowing.value)
        assertEquals(0.6f, host.attrs.screenBrightness, 0f)
    }

    @Test
    fun `destroyed finishing game display and missing hosts never become showing`() {
        val presentation = ScreenSaverPresentation()
        assertFalse(presentation.show(32))
        val host = Host()
        presentation.bind(host.activity)
        every { host.display.displayId } returns 10
        assertFalse(presentation.show(32))
        every { host.display.displayId } returns 0
        every { host.activity.isDestroyed } returns true
        assertFalse(presentation.show(32))
        every { host.activity.isDestroyed } returns false
        every { host.activity.isFinishing } returns true
        assertFalse(presentation.show(32))
        assertEquals(0.6f, host.attrs.screenBrightness, 0f)
        verify(exactly = 0) { host.window.attributes = any() }
    }

    @Test
    fun `Android 29 uses the existing Activity display and no new window API`() {
        val host = Host()
        val presentation = ScreenSaverPresentation()
        presentation.bind(host.activity)
        assertTrue(presentation.show(29))
        presentation.hide()

        verify(exactly = 0) { host.activity.display }
        verify(exactly = 0) { host.windows.addView(any(), any()) }
        assertEquals(0.6f, host.attrs.screenBrightness, 0f)
    }
}
