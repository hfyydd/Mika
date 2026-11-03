package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EffectQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `submits once processes in order and retries with backoff`() = runTest {
        var clock = 1_000L
        val store = MemoryEffectStore()
        val queue = EffectQueue(store, now = { clock })
        queue.submit("first", EffectKind.Assignment, "ticket", assignmentId = "a1")
        queue.submit("first", EffectKind.Assignment, "ticket", assignmentId = "a2")
        queue.submit("second", EffectKind.Ack, "ticket", payload = "r2")

        var failures = 0
        val executed = mutableListOf<String>()
        val completed = queue.drain { record ->
            if (record.id == "first" && failures == 0) {
                failures++
                throw IllegalStateException("network unavailable")
            }
            executed += record.id
            "receipt:${record.id}"
        }

        assertEquals(0, completed)
        assertEquals(emptyList<String>(), executed)
        val first = store.all().single { it.id == "first" }
        assertEquals(EffectState.Pending, first.state)
        assertEquals(1, first.attempts)
        assertEquals(2_000, first.availableAtMs)
        clock = 2_000
        assertEquals(2, queue.drain { "receipt:${it.id}" })
        assertEquals(listOf("first", "second"), store.all().filter { it.state == EffectState.Done }.map { it.id })
    }

    @Test
    fun `cancelled attempt stays pending and does not consume retries`() = runTest {
        val store = MemoryEffectStore()
        val queue = EffectQueue(store, now = { 10 })
        queue.submit("only", EffectKind.Assignment, "ticket")
        try {
            queue.drain { throw CancellationException("stop") }
        } catch (_: CancellationException) {
        }
        val record = store.all().single()
        assertEquals(EffectState.Pending, record.state)
        assertEquals(0, record.attempts)
    }

    @Test
    fun `file store survives attempting process death and preserves order`() = runTest {
        val file = tmp.newFile("effects.json")
        val first = FileEffectStore(file.toPath())
        val queue = EffectQueue(first, now = { 1 })
        queue.submit("a", EffectKind.Assignment, "t1")
        queue.submit("b", EffectKind.Reply, "t1")
        queue.drain { if (it.id == "a") throw IllegalStateException("crash") else "done" }

        val reopened = FileEffectStore(file.toPath())
        val recovered = EffectQueue(reopened, now = { 2_000 })
        recovered.recover()
        assertEquals(listOf("a", "b"), recovered.due().map { it.id })
        assertEquals(EffectState.Pending, reopened.all().single { it.id == "b" }.state)
        assertEquals(EffectState.Pending, reopened.all().single { it.id == "a" }.state)
    }
}
