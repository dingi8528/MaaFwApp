package com.aliothmoon.maafw.remote.internal

import android.os.SystemClock
import com.aliothmoon.maafw.third.Ln
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.function.BooleanSupplier
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Exercises real startup/recovery methods; private task queries replace the Android binder. */
class ActivityLaunchCancellationTest {
    private var active = true
    private val stillActive = BooleanSupplier { active }

    @Before
    fun setUp() {
        mockkObject(ActivityUtils, recordPrivateCalls = true)
        mockkStatic(ActivityUtils::class, Ln::class, SystemClock::class)
        every { Ln.i(any()) } just Runs
        every { Ln.w(any<String>()) } just Runs
        every { SystemClock.uptimeMillis() } returns 100L
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `already cancelled initial launch never reaches Android context or launcher`() {
        active = false
        assertFalse(ActivityUtils.startApp("game.fgo", 3, true, true, stillActive))
    }

    @Test
    fun `stop during force-stop task query prevents later binder force-stop`() {
        every { ActivityUtils["getAppDisplayId"]("game.fgo", any<String>()) } answers {
            active = false
            0
        }
        assertFalse(ActivityUtils.forceStop("game.fgo", 3, stillActive))
    }

    @Test
    fun `cancelled task query cannot start a repin from ensure`() {
        every { ActivityUtils["getAppDisplayId"]("game.fgo", "ensure") } answers {
            active = false
            0
        }
        every { ActivityUtils.repinAppToDisplay(any(), any(), any()) } returns true
        assertFalse(ActivityUtils.ensureAppOnDisplay("game.fgo", 3, stillActive))
        verify(exactly = 0) { ActivityUtils.repinAppToDisplay(any(), any(), any()) }
    }

    @Test
    fun `stop during failed move prevents fallback launcher access`() {
        every { ActivityUtils["moveAppTaskToDisplay"]("game.fgo", 3, stillActive) } answers {
            active = false
            false
        }
        // FakeContext is deliberately not initialized or mocked: reaching the launcher fallback
        // would fail the test on the host JVM rather than silently passing through a stub.
        assertFalse(ActivityUtils.repinAppToDisplay("game.fgo", 3, stillActive))
    }

    @Test
    fun `stop while successful move settles prevents query and fallback relaunch`() {
        every { ActivityUtils["moveAppTaskToDisplay"]("game.fgo", 3, stillActive) } returns true
        every { SystemClock.sleep(1_000) } answers { active = false }
        assertFalse(ActivityUtils.repinAppToDisplay("game.fgo", 3, stillActive))
        verify(exactly = 0) { ActivityUtils["getAppDisplayId"]("game.fgo", "repin-after-move") }
    }
}
