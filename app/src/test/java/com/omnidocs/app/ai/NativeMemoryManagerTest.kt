package com.omnidocs.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeMemoryManagerTest {

    @Test
    fun testNativeModelSlotEnumValues() {
        val slots = NativeModelSlot.values()
        assertTrue(slots.contains(NativeModelSlot.GENERATIVE_LLM))
        assertTrue(slots.contains(NativeModelSlot.SPEECH_TO_TEXT))
        assertTrue(slots.contains(NativeModelSlot.EMBEDDINGS))
    }

    @Test
    fun testBackgroundInferenceDispatcher_executesTasksSequentially() = runBlocking {
        val dispatcher = BackgroundInferenceDispatcher()
        val counter = AtomicInteger(0)
        val taskResults = mutableListOf<Int>()

        val job1 = {
            val v = counter.incrementAndGet()
            taskResults.add(v)
        }
        val job2 = {
            val v = counter.incrementAndGet()
            taskResults.add(v)
        }
        val job3 = {
            val v = counter.incrementAndGet()
            taskResults.add(v)
        }

        dispatcher.enqueue { job1() }
        dispatcher.enqueue { job2() }
        dispatcher.enqueue { job3() }

        // Allow background queue to execute
        kotlinx.coroutines.delay(100)

        assertEquals(3, counter.get())
        assertEquals(listOf(1, 2, 3), taskResults)
    }
}
