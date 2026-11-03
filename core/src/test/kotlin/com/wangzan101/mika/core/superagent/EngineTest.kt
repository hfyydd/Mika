package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private var idCounter = 0
    private fun ids() = "id-${++idCounter}"

    private fun engine(store: SuperagentStore = MemorySuperagentStore(), router: Router, clock: Long = 1000) =
        Engine(store, router, newId = ::ids, now = { clock })

    private fun trigger(
        id: String, actor: String = "alice", addressed: Boolean = true, isPerson: Boolean = true,
        threadKey: String = "t1", externalId: String = "e-$id", text: String = "do the thing",
    ) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = externalId, threadKey = threadKey,
        actor = actor, isPerson = isPerson, addressed = addressed, text = text, atMs = 100,
    )

    private val autoPolicy = Policy(version = "p1", acceptMode = AcceptMode.Auto, allowedActors = mapOf("s1" to listOf("alice")))

    private fun acceptRouter(): Router = Router { batch, _, _, _ ->
        batch.map { Decision.Accept(it.id, title = "work", scope = "test scope", reason = "ok") }
    }

    @Test
    fun `receive is idempotent per source external id`() {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        assertEquals(Engine.ReceiveResult.Stored, e.receive(trigger("a")))
        assertEquals(Engine.ReceiveResult.Duplicate, e.receive(trigger("a2", externalId = "e-a")))
    }

    @Test
    fun `accept creates ticket mandate assignment and subscription`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val outcomes = e.evaluateDue(autoPolicy)
        assertEquals(1, outcomes.size)
        val created = outcomes[0] as EvalOutcome.TicketCreated
        assertEquals(Strength.Explicit, created.ticket.mandate.strength)
        assertEquals("addressed", created.ticket.mandate.basis)
        assertEquals("p1", created.ticket.mandate.policyVersion)
        assertEquals(AssignmentStatus.Pending, created.assignment.status)
        val state = store.load()
        assertEquals(setOf(created.ticket.id), state.threads.single { it.key == "t1" }.subscriptions)
    }

    @Test
    fun `unlisted unaddressed trigger is not admitted`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a", actor = "mallory", addressed = false))
        val outcomes = e.evaluateDue(autoPolicy)
        assertTrue(outcomes[0] is EvalOutcome.NotAdmitted)
        assertTrue(store.load().tickets.isEmpty())
    }

    @Test
    fun `inferred accept is refused when accept mode off`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a", actor = "mallory")) // addressed but not on the list
        val outcomes = e.evaluateDue(autoPolicy.copy(acceptMode = AcceptMode.Off))
        assertTrue(outcomes[0] is EvalOutcome.NotAdmitted)
    }

    @Test
    fun `observe mode records would-accept without creating ticket`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val outcomes = e.evaluateDue(autoPolicy.copy(acceptMode = AcceptMode.Observe))
        assertTrue(outcomes[0] is EvalOutcome.WouldAccept)
        assertTrue(store.load().tickets.isEmpty())
        assertEquals(1, store.load().observations.size)
    }

    @Test
    fun `person followup on ticket thread becomes input without eval`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket

        // 同一线程上人的跟进:直通给 Ticket,不再排队 Eval。
        e.receive(trigger("b", text = "also change the icon", externalId = "e-b"))
        val state = store.load()
        val thread = state.threads.single { it.key == "t1" }
        assertTrue(thread.queuedTriggerIds.isEmpty())
        assertEquals(1, state.tickets.single { it.id == ticket.id }.inputs.size)
    }

    @Test
    fun `person message resumes ended ticket but not person-stopped one`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket

        // Executor 报 done → Ticket 结束;人再说话 → 恢复。
        e.report(e.snapshot().assignments.last().id, ExecutorReport("finished", outcome = "done"))
        assertEquals(TicketStatus.Ended, store.load().tickets.single { it.id == ticket.id }.status)
        e.receive(trigger("b", text = "one more tweak"))
        val revived = store.load().tickets.single { it.id == ticket.id }
        assertEquals(TicketStatus.Open, revived.status)
        assertNull(revived.ended)

        // 人的停止:后再说话也不恢复(消息照收,状态不动)。
        e.stopTicket(ticket.id, "user cancelled")
        e.receive(trigger("c", text = "wait come back"))
        val stopped = store.load().tickets.single { it.id == ticket.id }
        assertEquals(TicketStatus.Ended, stopped.status)
        assertEquals("person", stopped.ended?.by)
        assertEquals(2, stopped.inputs.size)
    }

    @Test
    fun `bot message on subscribed thread fans out and queues eval`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket

        e.receive(trigger("ci", actor = "ci-bot", isPerson = false, addressed = false, text = "build failed"))
        val state = store.load()
        // 直通作为上下文送达,同时照常进 Eval(机器人消息不带认领)。
        assertEquals(1, state.tickets.single { it.id == ticket.id }.inputs.size)
        assertEquals(listOf("ci"), state.threads.single { it.key == "t1" }.queuedTriggerIds)
    }

    @Test
    fun `router failure keeps the batch queued`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, { _, _, _, _ -> throw RuntimeException("router down") })
        e.receive(trigger("a"))
        e.evaluateDue(autoPolicy)
        assertEquals(listOf("a"), store.load().threads.single { it.key == "t1" }.queuedTriggerIds)
    }

    @Test
    fun `watchdog requeues stale assignments`() = runTest {
        val store = MemorySuperagentStore()
        var clock = 1000L
        val e = Engine(store, acceptRouter(), newId = ::ids, now = { clock })
        e.receive(trigger("a"))
        val created = e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated
        e.assignmentStarted(created.assignment.id)
        clock += Engine.ASSIGNMENT_TIMEOUT_MS + 1
        val retried = e.watchdog()
        assertEquals(1, retried.size)
        val state = store.load()
        assertEquals(AssignmentStatus.Ended, state.assignments.first { it.id == created.assignment.id }.status)
        assertEquals("interrupted", state.assignments.first { it.id == created.assignment.id }.outcome)
        assertEquals(AssignmentStatus.Pending, state.assignments.first { it.id == retried[0].id }.status)
    }

    @Test
    fun `policy change revokes mandates that no longer hold`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a", actor = "alice"))
        e.evaluateDue(autoPolicy)
        e.policyChanged(autoPolicy.copy(allowedActors = mapOf("s1" to listOf("bob"))))
        val ticket = store.load().tickets.single()
        assertEquals(TicketStatus.Ended, ticket.status)
        assertEquals("policy", ticket.ended?.by)
    }

    @Test
    fun `attach delivers input to target ticket`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket

        // 未订阅线程上的人的消息先进 Eval;Router 判 attach。
        e.receive(trigger("b", threadKey = "t2", text = "about ticket ${ticket.id}", externalId = "e-b"))
        val attachRouter = Router { batch, _, _, _ -> batch.map { Decision.Attach(it.id, ticket.id, "points at it") } }
        val e2 = Engine(store, attachRouter, newId = ::ids, now = { 2000 })
        val outcomes = e2.evaluateDue(autoPolicy)
        assertTrue(outcomes[0] is EvalOutcome.Attached)
        assertEquals(1, store.load().tickets.single { it.id == ticket.id }.inputs.size)
    }

    @Test
    fun `runner drives assignment end to end`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val created = e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated
        val executor = SuperExecutor { t, _ -> ExecutorReport("did ${t.title}", outcome = "done") }
        val ran = Runner(e, executor).runAssignment(created.assignment.id)
        assertTrue(ran)
        val assignment = store.load().assignments.single { it.id == created.assignment.id }
        assertEquals(AssignmentStatus.Ended, assignment.status)
        assertEquals("did work", assignment.result)
        assertNotNull(assignment.endedAtMs)
        assertEquals(TicketStatus.Ended, store.load().tickets.single { it.id == created.ticket.id }.status)
    }

    @Test
    fun `failed run reports failed and keeps ticket open`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val created = e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated
        val executor = SuperExecutor { _, _ -> throw RuntimeException("device gone") }
        Runner(e, executor).runAssignment(created.assignment.id)
        val ticket = store.load().tickets.single { it.id == created.ticket.id }
        assertEquals(TicketStatus.Open, ticket.status)
        assertEquals("failed", store.load().assignments.single().outcome)
    }
}
