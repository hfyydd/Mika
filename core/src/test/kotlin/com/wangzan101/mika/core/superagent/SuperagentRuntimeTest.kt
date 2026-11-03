package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperagentRuntimeTest {
    private fun policy() = Policy(
        version = "runtime", acceptMode = AcceptMode.Auto,
        allowedActors = mapOf("s1" to listOf("alice")),
    )

    private fun trigger() = Trigger(
        id = "r1", source = SourceRef("telegram", "s1"), externalId = "e1", threadKey = "thread",
        actor = "alice", addressed = true, text = "read the web page", atMs = 1,
    )

    @Test
    fun `polls evaluates executes and records durable receipt`() = runTest {
        val store = MemorySuperagentStore()
        val engine = Engine(
            store,
            router = { batch, _, _, _ -> batch.map { Decision.Accept(it.id, it.text, "read the web page", "explicit") } },
            newId = { "id-${store.load().tickets.size}-${store.load().assignments.size}" },
            now = { 100 },
        )
        val effects = EffectQueue(MemoryEffectStore(), now = { 100 })
        val executor = TaskHostExecutor(host = { ChildResult(it.id, ChildPhase.Completed, "done") })
        val runner = Runner(engine, executor, effects, now = { 100 })
        val runtime = SuperagentRuntime(
            engine,
            runner,
            ::policy,
            sources = listOf(object : Source {
                override val kind = "telegram"
                override suspend fun poll(state: SuperagentState) = listOf(trigger())
            }),
        )

        assertEquals(listOf(Engine.ReceiveResult.Stored), runtime.pollOnce())
        val outcomes = runtime.evaluateOnce()
        assertEquals(1, outcomes.filterIsInstance<EvalOutcome.TicketCreated>().size)
        assertTrue(effects.due().isEmpty())
        val state = engine.snapshot()
        assertEquals(ReportState.Ready, state.assignments.single().state)
        assertEquals(EffectState.Done, effects.all().single().state)
        assertEquals("done", state.tickets.single().ended?.outcome)
    }

    @Test
    fun `recovers abandoned running assignment after process death`() = runTest {
        val store = MemorySuperagentStore()
        val engine = Engine(store, router = { batch, _, _, _ -> batch.map { Decision.Ignore(it.id, "unused") } }, newId = { "new" }, now = { 200 })
        val old = com.wangzan101.mika.core.superagent.Assignment("old", "ticket", status = AssignmentStatus.Running, startedAtMs = 1)
        store.save(engine.snapshot().copy(assignments = listOf(old)))
        val recovered = SuperagentRuntime(
            engine,
            Runner(engine, executor = { _, _ -> ExecutorReport(ReportState.Ready, "ok") }),
            ::policy,
        ).recover()
        assertEquals(listOf("new"), recovered.map { it.id })
        assertEquals(AssignmentStatus.Ended, engine.snapshot().assignments.single { it.id == "old" }.status)
        assertEquals("interrupted", engine.snapshot().assignments.single { it.id == "old" }.outcome)
    }
}
