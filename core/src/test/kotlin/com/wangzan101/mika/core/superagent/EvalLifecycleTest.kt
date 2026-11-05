package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalLifecycleTest {

    private var clock = 1000L
    private var routerCalls = 0

    private fun trigger(id: String, atMs: Long = 1000L) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = "e-$id", threadKey = "t1",
        actor = "mallory", addressed = true, text = "help", atMs = atMs,
    )

    private fun failingEngine(store: SuperagentStore) = Engine(
        store,
        { _, _, _, _ -> routerCalls++; throw RuntimeException("router down") },
        newId = { "id-${routerCalls}" },
        now = { clock },
        evalDeadlineMs = 100L,
    )

    private val policy = Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("alice")))

    @Test
    fun `candidate deadline starts at delivery`() {
        val store = MemorySuperagentStore()
        Engine(store, { _, _, _, _ -> emptyList() }, now = { clock }, evalDeadlineMs = 100L)
            .receive(trigger("a"))

        val disposition = store.load().dispositions.getValue("a")
        assertEquals(1000L, disposition.admittedAtMs)
        assertEquals(1100L, disposition.evalDeadlineAtMs)
        assertEquals(0, disposition.evalRequeues)
        assertNull(disposition.unavailableAtMs)
    }

    @Test
    fun `pause extends the deadline and resume keeps the extension`() = runTest {
        val store = MemorySuperagentStore()
        val e = failingEngine(store)
        e.receive(trigger("a"))
        clock = 1000L
        assertTrue(e.pauseEval("a", untilMs = 2000L))
        assertEquals(2100L, store.load().dispositions.getValue("a").evalDeadlineAtMs)
        assertEquals(2000L, store.load().dispositions.getValue("a").evalPausedUntilMs)

        assertTrue(e.resumeEval("a"))
        assertEquals(2100L, store.load().dispositions.getValue("a").evalDeadlineAtMs)
        assertNull(store.load().dispositions.getValue("a").evalPausedUntilMs)
    }

    @Test
    fun `expired candidates requeue twice then become unavailable`() = runTest {
        val store = MemorySuperagentStore()
        val e = failingEngine(store)
        e.receive(trigger("a", atMs = 800L))
        clock = 1200L
        e.evaluateDue(policy)
        assertEquals(1, store.load().dispositions.getValue("a").evalRequeues)
        assertEquals(listOf("a"), store.load().threads.single().queuedTriggerIds)

        clock = 1400L
        e.evaluateDue(policy)
        assertEquals(2, store.load().dispositions.getValue("a").evalRequeues)
        assertEquals(listOf("a"), store.load().threads.single().queuedTriggerIds)

        clock = 1600L
        e.evaluateDue(policy)
        val disposition = store.load().dispositions.getValue("a")
        assertTrue(disposition.evalRequeues >= 2)
        assertNotNull(disposition.unavailableAtMs)
        assertTrue(store.load().threads.single().queuedTriggerIds.isEmpty())
        assertEquals(2, routerCalls)
    }

    @Test
    fun `router failure leaves the queue without burning eval retries`() = runTest {
        val store = MemorySuperagentStore()
        val e = Engine(
            store,
            { _, _, _, _ -> throw RuntimeException("router down") },
            newId = { "fixed" },
            now = { clock },
            evalDeadlineMs = 100_000L,
        )
        e.receive(trigger("a"))
        repeat(3) {
            e.evaluateDue(policy)
            val disposition = store.load().dispositions.getValue("a")
            assertEquals(0, disposition.evalRequeues)
            assertNull(disposition.unavailableAtMs)
            assertEquals(listOf("a"), store.load().threads.single().queuedTriggerIds)
        }
    }
}
