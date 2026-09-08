package com.daview.server.api

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [MediaFacade] is where asking the library something stops being the caller's
 * problem and becomes disk work, so every call on it hops off the calling
 * thread before it touches SQLite.
 *
 * That matters because the callers are Compose screens holding the core in
 * their own process: a read that takes 200 ms is 200 ms the window does not
 * repaint, and a home screen doing half a dozen of them is what "the app hangs
 * for ten seconds" looked like. One blocking method added later would put it
 * back, and nothing about the call site would look wrong.
 *
 * A suspending method compiles to one whose last parameter is a Continuation,
 * which is what this reads. It cannot prove a body actually goes through
 * `io { }` — only that the shape which *can* be called straight from the event
 * thread does not reappear.
 */
class MediaFacadeThreadingTest {

    @Test
    fun `every call into the library suspends`() {
        // The one deliberate exception: the sequence is lazy, so its reads
        // happen wherever the caller consumes it, not where it is built.
        val exempt = setOf("backupChunks")

        val blocking = MediaFacade::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .filterNot { it.name.endsWith("\$default") }
            .filterNot { it.name in exempt }
            .filterNot { it.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation" }
            .map { it.name }
            .distinct()
            .sorted()

        assertEquals(emptyList(), blocking, "these still run on the caller's thread")
    }
}
