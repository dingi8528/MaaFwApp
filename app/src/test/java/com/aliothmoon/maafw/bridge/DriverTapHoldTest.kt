package com.aliothmoon.maafw.bridge

import android.os.SystemClock
import com.aliothmoon.maafw.remote.internal.GameFpsMonitor
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.PrimaryInputGuard
import com.aliothmoon.maafw.third.Ln
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercise the shared native-controller entry point, including actual padding dispatch. */
class DriverTapHoldTest {
    @Before
    fun setUp() {
        PrimaryInputGuard.clear()
        mockkObject(PrimaryDisplayManager)
        mockkStatic(InputControlUtils::class, SystemClock::class, Ln::class, GameFpsMonitor::class)
        every { PrimaryDisplayManager.getCaptureSize() } returns (1920 to 1080)
        every { InputControlUtils.cancelActiveGesture(any()) } just Runs
        every { InputControlUtils.down(any(), any(), any(), any()) } returns InputControlUtils.TouchResult.DELIVERED
        every { InputControlUtils.up(any(), any(), any(), any()) } returns true
        every { SystemClock.sleep(any()) } just Runs
        every { Ln.i(any()) } just Runs
        DriverClass.cancelPrimaryInput()
        PrimaryInputGuard.bind(1920, 1080) {}
    }

    @After
    fun tearDown() {
        PrimaryInputGuard.clear()
        DriverClass.cancelPrimaryInput()
        unmockkAll()
    }

    private fun tap(display: Int, fps: Float, held: Long) {
        every { GameFpsMonitor.currentFps() } returns fps
        every { SystemClock.uptimeMillis() } returnsMany listOf(100L, 100L + held)
        assertTrue(DriverClass.touchDown(100, 200, 0, display))
        assertTrue(DriverClass.touchUp(100, 200, 0, display))
        verify(exactly = 1) { InputControlUtils.up(100, 200, 0, display) }
    }

    @Test
    fun `foreground short click is padded at exactly 30 fps`() {
        tap(0, 30f, 50)
        verify(exactly = 1) { SystemClock.sleep(50L) }
    }

    @Test
    fun `background short click uses the same fps policy`() {
        tap(7, 20f, 50)
        verify(exactly = 1) { SystemClock.sleep(100L) }
    }

    @Test
    fun `very low fps padding is capped`() {
        tap(7, 3f, 50)
        verify(exactly = 1) { SystemClock.sleep(250L) }
    }

    @Test
    fun `configured longer hold is released without additional sleep`() {
        tap(7, 3f, 500)
        verify(exactly = 0) { SystemClock.sleep(any()) }
    }

    @Test
    fun `unknown fps leaves the click unchanged`() {
        tap(0, -1f, 50)
        verify(exactly = 0) { SystemClock.sleep(any()) }
    }
}
