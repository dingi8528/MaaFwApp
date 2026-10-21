package com.aliothmoon.maafw.remote.internal

import android.content.Intent
import android.os.IInterface
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ActivityManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStartFailureTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `unavailable binder start method is negative so caller can use am fallback`() {
        mockkStatic(Ln::class)
        every { Ln.e(any<String>(), any<Throwable>()) } just Runs
        val constructor = ActivityManager::class.java.getDeclaredConstructor(IInterface::class.java)
        constructor.isAccessible = true
        val wrapper = constructor.newInstance(mockk<IInterface>(relaxed = true))
        assertTrue(wrapper.startActivity(mockk<Intent>(), null) < 0)
    }
}
