package com.aliothmoon.maafw.remote.internal

import android.os.SystemClock
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.third.Ln
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppWatchdogTargetTest {
    private val scheduler = TestCoroutineScheduler()
    private var now = 100L

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers, VirtualDisplayManager, ActivityUtils, ProcessLiveness.Companion)
        // @JvmStatic repinAppToDisplay is emitted as a real JVM static method; object mocking
        // covers probeDisplay/isAppOnDisplay only. Mock both without entering FakeContext.
        mockkStatic(ActivityUtils::class, Ln::class, SystemClock::class)
        every { Ln.i(any()) } just Runs
        every { Ln.w(any<String>()) } just Runs
        every { MaaDispatchers.IO } returns StandardTestDispatcher(scheduler)
        every { VirtualDisplayManager.getDisplayId() } returns 3
        every { SystemClock.elapsedRealtime() } answers { now }
        every { ActivityUtils.probeDisplay(3) } returns ActivityUtils.DisplayOccupancy.Empty
        every { ProcessLiveness.probe("game.fgo") } returns ProcessLiveness.ALIVE
        every { ActivityUtils.repinAppToDisplay("game.fgo", 3, any()) } returns true
        every { ActivityUtils.isAppOnDisplay("game.fgo", 3) } returns true
        AppWatchdog.stopWatching()
    }

    @After
    fun tearDown() {
        AppWatchdog.stopWatching()
        scheduler.runCurrent()
        unmockkAll()
    }

    @Test
    fun `launch registered before first poll can be recovered after immediate display drift`() {
        AppWatchdog.prepareRun(3)
        AppWatchdog.registerTarget("game.fgo", 3)
        AppWatchdog.startWatching { true }
        assertEquals("game.fgo", AppWatchdog.targetPackage)
        scheduler.advanceTimeBy(5_001)
        now += 5_000
        scheduler.advanceTimeBy(5_000)
        verify(exactly = 1) { ActivityUtils.repinAppToDisplay("game.fgo", 3, any()) }
    }

    @Test
    fun `foreground stale display and post-stop launches cannot acquire targets`() {
        AppWatchdog.prepareRun(-1)
        AppWatchdog.registerTarget("game.fgo", 3)
        assertNull(AppWatchdog.targetPackage)
        AppWatchdog.prepareRun(3)
        AppWatchdog.registerTarget("game.fgo", 4)
        assertNull(AppWatchdog.targetPackage)
        AppWatchdog.registerTarget("game.fgo", 3)
        AppWatchdog.stopWatching()
        AppWatchdog.registerTarget("game.other", 3)
        assertNull(AppWatchdog.targetPackage)
    }

    @Test
    fun `new run and failed launch clear previous targets`() {
        AppWatchdog.prepareRun(3)
        AppWatchdog.registerTarget("game.fgo", 3)
        AppWatchdog.prepareRun(3)
        assertNull(AppWatchdog.targetPackage)
        assertNull(AppWatchdog.cleanupTargetPackage)
        val old = AppWatchdog.registerTarget("game.fgo", 3)!!
        val current = AppWatchdog.registerTarget("game.other", 3)!!
        AppWatchdog.clearTarget(old)
        assertEquals("game.other", AppWatchdog.targetPackage)
        AppWatchdog.clearTarget(current)
        assertNull(AppWatchdog.targetPackage)
        assertNull(AppWatchdog.cleanupTargetPackage)
    }

    @Test
    fun `unknown display observation never moves a registered game`() {
        every { ActivityUtils.probeDisplay(3) } returns ActivityUtils.DisplayOccupancy.Unknown
        AppWatchdog.prepareRun(3)
        AppWatchdog.registerTarget("game.fgo", 3)
        AppWatchdog.startWatching { true }
        scheduler.advanceTimeBy(20_001)
        verify(exactly = 0) { ActivityUtils.repinAppToDisplay(any(), any(), any()) }
    }

    @Test
    fun `completed run closes registration while retaining only cleanup target`() {
        AppWatchdog.prepareRun(3)
        AppWatchdog.registerTarget("game.fgo", 3)
        AppWatchdog.startWatching { false }
        scheduler.runCurrent()
        assertNull(AppWatchdog.targetPackage)
        assertEquals("game.fgo", AppWatchdog.cleanupTargetPackage)
        AppWatchdog.registerTarget("game.other", 3)
        assertNull(AppWatchdog.targetPackage)
        assertEquals("game.fgo", AppWatchdog.cleanupTargetPackage)
    }

    @Test
    fun `stop invalidates token and old completion cannot clear a new run of same package`() {
        AppWatchdog.prepareRun(3)
        val previous = AppWatchdog.registerTarget("game.fgo", 3)!!
        assertTrue(AppWatchdog.isTargetActive(previous))
        AppWatchdog.stopWatching()
        assertFalse(AppWatchdog.isTargetActive(previous))
        AppWatchdog.prepareRun(3)
        val next = AppWatchdog.registerTarget("game.fgo", 3)!!
        AppWatchdog.clearTarget(previous)
        assertEquals("game.fgo", AppWatchdog.targetPackage)
        assertEquals("game.fgo", AppWatchdog.cleanupTargetPackage)
        assertTrue(AppWatchdog.isTargetActive(next))
    }

    @Test
    fun `stop never waits for a blocked task query and stale query cannot repin`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        AppWatchdog.prepareRun(3)
        val token = AppWatchdog.registerTarget("game.fgo", 3)!!
        every { ActivityUtils.probeDisplay(3) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ActivityUtils.DisplayOccupancy.Empty
        }
        val poll = thread {
            AppWatchdog::class.java.getDeclaredMethod("tick").also { it.isAccessible = true }
                .invoke(AppWatchdog)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = thread { AppWatchdog.stopWatching(); stopped.countDown() }
            assertTrue("stop must not wait for the binder-like query", stopped.await(1, TimeUnit.SECONDS))
            stop.join(2_000)
            assertFalse(AppWatchdog.isTargetActive(token))
        } finally {
            release.countDown()
            poll.join(2_000)
        }
        verify(exactly = 0) { ActivityUtils.repinAppToDisplay(any(), any(), any()) }
    }
}
