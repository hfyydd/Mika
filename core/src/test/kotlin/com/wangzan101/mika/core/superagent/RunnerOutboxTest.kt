package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RunnerOutboxTest {
    @Test
    fun `terminal executor report queues one reply on the original source route`() = runTest {
        val store = MemorySuperagentStore()
        val engine = Engine(
            store,
            router = { batch, _, _, _ -> batch.map { Decision.Accept(it.id, it.text, "read only", "explicit") } },
            newId = { "id-${store.load().tickets.size}-${store.load().assignments.size}" },
            now = { 100 },
        )
        engine.receive(trigger())
        engine.evaluateDue(policy())
        val created = engine.snapshot().assignments.single()
        val effects = EffectQueue(MemoryEffectStore(), now = { 100 })
        val envelopes = mutableListOf<ReplyEnvelope>()
        val outbox = SourceOutbox(effects, mapOf("telegram:main" to ReplyChannel { envelope, _ -> envelopes += envelope; "receipt" }))
        val runner = Runner(engine, TaskHostExecutor(host = { ChildResult(it.id, ChildPhase.Completed, "done") }), effects, outbox)

        runner.runAssignment(created.id)
        val sent = outbox.flush()

        assertEquals(1, sent)
        assertEquals("done", envelopes.single().text)
        assertEquals("chat", envelopes.single().threadKey)
        assertEquals(EffectState.Done, effects.all().last { it.kind == EffectKind.Reply }.state)
    }

    @Test
    fun `progress reports do not create reply side effects`() = runTest {
        val store = MemorySuperagentStore()
        val engine = Engine(
            store,
            router = { batch, _, _, _ -> batch.map { Decision.Accept(it.id, it.text, "observe", "explicit") } },
            newId = { "id-${store.load().tickets.size}-${store.load().assignments.size}" },
            now = { 100 },
        )
        engine.receive(trigger())
        engine.evaluateDue(policy())
        val created = engine.snapshot().assignments.single()
        val effects = EffectQueue(MemoryEffectStore(), now = { 100 })
        val runner = Runner(engine, TaskHostExecutor(host = { ChildResult(it.id, ChildPhase.Running, "working") }), effects, SourceOutbox(effects, emptyMap()))

        runner.runAssignment(created.id)

        assertEquals(0, effects.all().count { it.kind == EffectKind.Reply })
    }

    private fun policy() = Policy(
        version = "test", acceptMode = AcceptMode.Auto,
        allowedActors = mapOf("main" to listOf("@alice")),
    )

    private fun trigger() = Trigger(
        id = "r1", source = SourceRef("telegram", "main"), externalId = "u1", threadKey = "chat",
        actor = "@alice", addressed = true, text = "please do it", atMs = 1,
    )
}
