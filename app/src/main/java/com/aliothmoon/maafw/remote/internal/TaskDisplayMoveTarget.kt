package com.aliothmoon.maafw.remote.internal

/** Hidden TaskInfo fields differ across Android releases; never substitute a task ID for a stack. */
internal object TaskDisplayMoveTarget {
    fun resolve(task: Any, useRootTask: Boolean): Int? {
        if (!useRootTask) return readInt(task, "stackId")?.takeIf { it >= 0 }
        // On Android 12+ a fullscreen task is itself a root task. A child task ID is not a
        // root-task ID: leave nested/unknown hierarchies to the launch-intent fallback.
        val parentId = readInt(task, "parentTaskId") ?: return null
        if (parentId >= 0) return null
        return readInt(task, "taskId")?.takeIf { it >= 0 }
    }

    private fun readInt(instance: Any, name: String): Int? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val currentType = type
            val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) return runCatching {
                field.isAccessible = true
                field.getInt(instance)
            }.getOrNull()
            type = type.superclass
        }
        return null
    }
}
