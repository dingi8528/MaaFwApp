package com.aliothmoon.maafw.remote.internal

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrimaryInputGuardTest {
    @Before
    fun setUp() {
        PrimaryInputGuard.clear()
        mockkObject(PrimaryDisplayManager)
        every { PrimaryDisplayManager.getCaptureSize() } returns (1920 to 1080)
    }

    @After
    fun tearDown() {
        PrimaryInputGuard.clear()
        unmockkAll()
    }

    @Test
    fun `rotation invalidates once and rotating back does not resume stale coordinates`() {
        val changes = mutableListOf<Pair<Int, Int>?>()
        PrimaryInputGuard.bind(1920, 1080) { changes += it }
        assertTrue(PrimaryInputGuard.allowsInput(0))
        every { PrimaryDisplayManager.getCaptureSize() } returns (1080 to 1920)
        assertFalse(PrimaryInputGuard.allowsInput(0))
        every { PrimaryDisplayManager.getCaptureSize() } returns (1920 to 1080)
        assertFalse(PrimaryInputGuard.allowsInput(0))
        assertEquals(listOf(1080 to 1920), changes)
        PrimaryInputGuard.bind(1920, 1080) { changes += it }
        assertTrue(PrimaryInputGuard.allowsInput(0))
    }

    @Test
    fun `missing capture invalidates while stopped or unbound primary rejects input`() {
        var failures = 0
        assertFalse(PrimaryInputGuard.allowsInput(0))
        PrimaryInputGuard.bind(1920, 1080) { failures++ }
        every { PrimaryDisplayManager.getCaptureSize() } returns null
        assertFalse(PrimaryInputGuard.allowsInput(0))
        assertEquals(1, failures)
        assertTrue(PrimaryInputGuard.clear())
        assertFalse(PrimaryInputGuard.allowsInput(0))
        assertFalse(PrimaryInputGuard.clear())
    }

    @Test
    fun `background input bypasses both primary capture reads and failure callback`() {
        PrimaryInputGuard.bind(1920, 1080) { error("background must not stop") }
        assertTrue(PrimaryInputGuard.allowsInput(7))
        PrimaryInputGuard.clear()
        assertTrue(PrimaryInputGuard.allowsInput(7))
        verify(exactly = 0) { PrimaryDisplayManager.getCaptureSize() }
    }
}
