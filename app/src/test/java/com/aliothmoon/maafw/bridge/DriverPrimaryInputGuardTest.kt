package com.aliothmoon.maafw.bridge

import android.os.SystemClock
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.PrimaryInputGuard
import com.aliothmoon.maafw.remote.internal.GameFpsMonitor
import com.aliothmoon.maafw.third.Ln
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercise real Driver entry points; no actual Android input or native library is used. */
class DriverPrimaryInputGuardTest {
    private var stopped = 0

    @Before
    fun setUp() {
        PrimaryInputGuard.clear()
        mockkObject(PrimaryDisplayManager)
        mockkStatic(InputControlUtils::class, SystemClock::class, Ln::class)
        every { PrimaryDisplayManager.getCaptureSize() } returns (1920 to 1080)
        every { SystemClock.uptimeMillis() } returns 100L
        every { Ln.i(any()) } just Runs
        every { Ln.w(any<String>(), any<Throwable>()) } just Runs
        every { InputControlUtils.down(any(), any(), any(), any()) } returns InputControlUtils.TouchResult.DELIVERED
        every { InputControlUtils.cancelActiveGesture(any()) } just Runs
        every { InputControlUtils.keyDown(any(), any()) } returns true
        every { InputControlUtils.cancelKey(any(), any()) } returns true
        DriverClass.cancelPrimaryInput()
        PrimaryInputGuard.bind(1920, 1080) { stopped++ }
    }

    @After
    fun tearDown() {
        PrimaryInputGuard.clear()
        DriverClass.cancelPrimaryInput()
        unmockkAll()
    }

    @Test
    fun `rotation after DOWN cancels touch and blocks MOVE UP and recovery clicks`() {
        assertTrue(DriverClass.touchDown(1200, 500, 0, 0))
        every { PrimaryDisplayManager.getCaptureSize() } returns (1080 to 1920)
        assertFalse(DriverClass.touchMove(1250, 510, 0, 0))
        assertFalse(DriverClass.touchUp(1250, 510, 0, 0))
        assertFalse(DriverClass.touchDown(1200, 500, 1, 0))
        assertEquals(1, stopped)
        verify(exactly = 1) { InputControlUtils.cancelActiveGesture(0) }
        verify(exactly = 1) { InputControlUtils.down(any(), any(), any(), any()) }
        verify(exactly = 0) { InputControlUtils.move(any(), any(), any(), any()) }
        verify(exactly = 0) { InputControlUtils.up(any(), any(), any(), any()) }
    }

    @Test
    fun `stopping a pressed primary touch cancels it without generating UP`() {
        assertTrue(DriverClass.touchDown(1200, 500, 0, 0))
        assertTrue(PrimaryInputGuard.clear())
        DriverClass.cancelPrimaryInput()
        assertFalse(DriverClass.touchUp(1200, 500, 0, 0))
        assertEquals(0, stopped)
        verify(exactly = 1) { InputControlUtils.cancelActiveGesture(0) }
        verify(exactly = 0) { InputControlUtils.up(any(), any(), any(), any()) }
    }

    @Test
    fun `rotation releases a pressed key through the cancelled key path`() {
        assertTrue(DriverClass.keyDown(111, 0))
        every { PrimaryDisplayManager.getCaptureSize() } returns (1080 to 1920)
        assertFalse(DriverClass.keyUp(111, 0))
        assertEquals(1, stopped)
        verify(exactly = 1) { InputControlUtils.cancelKey(111, 0) }
        verify(exactly = 0) { InputControlUtils.keyUp(any(), any()) }
    }

    @Test
    fun `virtual display input is not blocked by a stopped primary guard`() {
        PrimaryInputGuard.clear()
        assertTrue(DriverClass.keyDown(111, 7))
        verify(exactly = 1) { InputControlUtils.keyDown(111, 7) }
        verify(exactly = 0) { PrimaryDisplayManager.getCaptureSize() }
        assertEquals(0, stopped)
    }

    @Test
    fun `cancelling primary input preserves an ongoing virtual display gesture`() {
        mockkStatic(GameFpsMonitor::class)
        every { GameFpsMonitor.currentFps() } returns 60f
        every { SystemClock.uptimeMillis() } returnsMany listOf(100L, 500L)
        every { InputControlUtils.move(any(), any(), any(), any()) } returns true
        every { InputControlUtils.up(any(), any(), any(), any()) } returns true
        assertTrue(DriverClass.touchDown(100, 200, 0, 7))
        DriverClass.cancelPrimaryInput()
        assertTrue(DriverClass.touchMove(110, 210, 0, 7))
        assertTrue(DriverClass.touchUp(110, 210, 0, 7))
        verify(exactly = 0) { InputControlUtils.cancelActiveGesture(any()) }
        verify(exactly = 1) { InputControlUtils.up(110, 210, 0, 7) }
        verify(exactly = 0) { PrimaryDisplayManager.getCaptureSize() }
    }
}
