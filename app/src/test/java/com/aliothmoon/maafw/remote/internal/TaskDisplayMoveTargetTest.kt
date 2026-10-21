package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDisplayMoveTargetTest {
    private open class LegacyTaskInfo(@JvmField val taskId: Int, @JvmField val stackId: Int)
    private class RunningLegacyTask : LegacyTaskInfo(2953, 45)
    private class ModernTaskInfo(@JvmField val taskId: Int, @JvmField val parentTaskId: Int)
    private class MissingStack(@JvmField val taskId: Int)

    @Test
    fun `Android 10 and 11 move the real inherited stack not the unrelated task number`() {
        assertEquals(45, TaskDisplayMoveTarget.resolve(RunningLegacyTask(), useRootTask = false))
    }

    @Test
    fun `unreadable stack cannot fall back to a task ID`() {
        assertNull(TaskDisplayMoveTarget.resolve(MissingStack(2953), useRootTask = false))
        assertNull(TaskDisplayMoveTarget.resolve(LegacyTaskInfo(2953, -1), useRootTask = false))
    }

    @Test
    fun `Android 12 fullscreen task remains a root task target`() {
        assertEquals(2953, TaskDisplayMoveTarget.resolve(ModernTaskInfo(2953, -1), useRootTask = true))
    }

    @Test
    fun `nested task and unreadable root hierarchy are never moved as roots`() {
        assertNull(TaskDisplayMoveTarget.resolve(ModernTaskInfo(2953, 45), useRootTask = true))
        assertNull(TaskDisplayMoveTarget.resolve(MissingStack(2953), useRootTask = true))
    }
}
