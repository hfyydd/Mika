package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        threadKey: String = "t1", externalId: String = "e-$id", text: String = "do the thing", atMs: Long = 100,
    ) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = externalId, threadKey = threadKey,
        actor = actor, isPerson = isPerson, addressed = addressed, text = text, atMs = atMs,
    )

    private val autoPolicy = Policy(version = "p1", acceptMode = AcceptMode.Auto, allowedActors = mapOf("s1" to listOf("alice")))

    private fun acceptRouter(): Router = Router { batch, _, _, _ ->
        batch.map { Decision.Accept(it.id, title = "work", scope = "test scope", reason = "ok") }
    }

    private fun strictRouter(): Router = Router { batch, _, _, _ ->
        batch.map {
            Decision.Accept(
                it.id, title = "work", scope = "test scope", reason = "inferred",
                matchedRule = "rule-1", direction = "create", motivationRef = it.text.take(40), hostVerified = true,
            )
        }
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
        val disposition = state.dispositions.getValue("a")
        assertEquals("a", disposition.decisionId)
        assertTrue(disposition.evalId?.isNotBlank() == true)
        assertTrue(!disposition.requeued)
        assertTrue(state.decisions["a"] is Decision.Accept)
    }

    @Test
    fun `receive records initial disposition and followup fanout`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))

        val initial = store.load().dispositions.getValue("a")
        assertEquals("a", initial.triggerId)
        assertTrue(initial.fanout.isEmpty())
        assertTrue(initial.context.isEmpty())
        assertTrue(initial.requeued)

        val created = e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated
        e.receive(trigger("b", text = "also tweak it", externalId = "e-b"))
        val followup = store.load().dispositions.getValue("b")
        assertEquals(listOf(created.ticket.id), followup.fanout)
        assertTrue(followup.context.isEmpty())
        assertTrue(!followup.requeued)
        assertNull(followup.decisionId)
    }

    @Test
    fun `bot context and queue state are recorded together`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket

        e.receive(trigger("ci", actor = "ci-bot", isPerson = false, addressed = false, externalId = "e-ci"))
        val disposition = store.load().dispositions.getValue("ci")
        assertEquals(listOf(ticket.id), disposition.context)
        assertTrue(disposition.fanout.isEmpty())
        assertTrue(disposition.requeued)
    }

    @Test
    fun `gate rejection is audited as a decision and filtered disposition`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a", actor = "mallory", addressed = false))
        val outcomes = e.evaluateDue(autoPolicy)

        assertTrue(outcomes[0] is EvalOutcome.NotAdmitted)
        val state = store.load()
        val decision = state.decisions.getValue("a")
        assertTrue(decision is Decision.Ignore)
        assertEquals("not_admitted:not_admitted", decision.reason)
        val disposition = state.dispositions.getValue("a")
        assertEquals("not_admitted:not_admitted", disposition.filtered)
        assertEquals("a", disposition.decisionId)
        assertTrue(!disposition.requeued)
    }

    @Test
    fun `duplicate receive does not create a second disposition`() {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        e.receive(trigger("a2", externalId = "e-a"))

        assertEquals(setOf("a"), store.load().dispositions.keys)
    }

    @Test
    fun `handback and ack are idempotent ledger updates`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket
        e.receive(trigger("b", text = "a follow-up", externalId = "e-b"))

        e.handback("b", ticket.id, "outside ticket scope")
        e.handback("b", ticket.id, "outside ticket scope")
        e.ack("b", ticket.id)
        e.ack("b", ticket.id)

        val disposition = store.load().dispositions.getValue("b")
        assertEquals(1, disposition.handbacks.size)
        assertEquals(ticket.id, disposition.handbacks.single().ticketId)
        assertEquals("outside ticket scope", disposition.handbacks.single().reason)
        assertEquals(listOf(ticket.id), disposition.acked)
    }

    @Test
    fun `handback removes input and requeues when no ticket owns it`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket
        e.receive(trigger("b", text = "out of scope", externalId = "e-b"))

        assertTrue(e.handback("b", ticket.id, "outside scope"))
        val state = store.load()
        assertTrue(state.tickets.single().inputs.isEmpty())
        assertEquals(listOf("b"), state.threads.single().queuedTriggerIds)
        assertTrue(state.dispositions.getValue("b").requeued)

        val e2 = Engine(store, Router { batch, _, _, _ ->
            batch.map { Decision.Ignore(it.id, "not work after handback") }
        }, newId = ::ids, now = { 3000 })
        e2.evaluateDue(autoPolicy)
        val decided = store.load().dispositions.getValue("b")
        assertTrue(store.load().decisions.getValue("b") is Decision.Ignore)
        assertEquals("b", decided.decisionId)
        assertTrue(!decided.requeued)
    }

    @Test
    fun `ack removes the unread input exactly once`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket
        e.receive(trigger("b", externalId = "e-b"))

        assertTrue(e.ack("b", ticket.id))
        assertFalse(e.ack("b", ticket.id))
        assertTrue(store.load().tickets.single().inputs.isEmpty())
    }

    @Test
    fun `ticket completion releases unread followups but not acknowledged ones`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        val ticket = (e.evaluateDue(autoPolicy)[0] as EvalOutcome.TicketCreated).ticket
        e.receive(trigger("b", externalId = "e-b"))
        e.receive(trigger("c", externalId = "e-c"))
        e.ack("b", ticket.id)
        e.report(e.snapshot().assignments.single { it.ticketId == ticket.id }.id, ExecutorReport(ReportState.Ready, "done", outcome = "done"))

        val state = store.load()
        assertEquals(TicketStatus.Ended, state.tickets.single().status)
        assertTrue(state.tickets.single().inputs.isEmpty())
        assertEquals(listOf("c"), state.threads.single().queuedTriggerIds)
        assertTrue(state.dispositions.getValue("c").requeued)
        assertFalse(state.dispositions.getValue("b").requeued)
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
        e.report(e.snapshot().assignments.last().id, ExecutorReport(ReportState.Ready, "finished", outcome = "done"))
        assertEquals(TicketStatus.Ended, store.load().tickets.single { it.id == ticket.id }.status)
        e.receive(trigger("b", text = "one more tweak"))
        val revived = store.load().tickets.single { it.id == ticket.id }
        assertEquals(TicketStatus.Open, revived.status)
        assertNull(revived.ended)

        // 人的停止:未读跟进释放回 Eval,状态不因旧输入恢复。
        e.stopTicket(ticket.id, "user cancelled")
        e.receive(trigger("c", text = "wait come back"))
        val stopped = store.load().tickets.single { it.id == ticket.id }
        assertEquals(TicketStatus.Ended, stopped.status)
        assertEquals("person", stopped.ended?.by)
        assertEquals(0, stopped.inputs.size)
        assertEquals(listOf("b", "c"), store.load().threads.single { it.key == "t1" }.queuedTriggerIds)
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
        val state = store.load()
        assertEquals(listOf("a"), state.threads.single { it.key == "t1" }.queuedTriggerIds)
        assertTrue(state.dispositions.getValue("a").evalId?.isNotBlank() == true)
        assertTrue(state.dispositions.getValue("a").requeued)
    }

    @Test
    fun `batch accepts only the strongest seed`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a", atMs = 100))
        e.receive(trigger("inf", actor = "mallory", atMs = 200, externalId = "e-inf"))
        e.evaluateDue(autoPolicy)

        val state = store.load()
        assertEquals(1, state.tickets.size)
        assertEquals(listOf("a"), state.tickets.single().triggerIds)
        assertEquals("secondary_in_batch", (state.decisions.getValue("inf") as Decision.Ignore).reason)
    }

    @Test
    fun `same strength accepts only the latest trigger`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("old", atMs = 100))
        e.receive(trigger("new", atMs = 200, externalId = "e-new"))
        e.evaluateDue(autoPolicy)

        val state = store.load()
        assertEquals(1, state.tickets.size)
        assertEquals(listOf("new"), state.tickets.single().triggerIds)
    }

    @Test
    fun `trigger that already started a ticket cannot accept again`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("a"))
        e.evaluateDue(autoPolicy)
        val state0 = store.load()
        store.save(state0.copy(
            threads = state0.threads.map { it.copy(queuedTriggerIds = listOf("a")) },
            dispositions = state0.dispositions + ("a" to state0.dispositions.getValue("a").copy(requeued = true, decisionId = null)),
        ))

        e.evaluateDue(autoPolicy)
        val state = store.load()
        assertEquals(1, state.tickets.size)
        assertEquals("startedTicket", (state.decisions.getValue("a") as Decision.Ignore).reason)
    }

    @Test
    fun `allowlisted addressed bot is inferred but never explicit`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("bot", actor = "ci-bot", isPerson = false, addressed = true, text = "ci asked for report"))
        val outcome = Engine(
            store, strictRouter(), newId = ::ids, now = { 1000 },
        ).evaluateDue(autoPolicy.copy(allowedActors = mapOf("s1" to listOf("alice", "ci-bot"))))[0]

        val created = outcome as EvalOutcome.TicketCreated
        assertEquals(Strength.Inferred, created.ticket.mandate.strength)
        assertEquals("bot_allowlisted", created.ticket.mandate.basis)
    }

    @Test
    fun `allowlisted unaddressed bot cannot use followup strength`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("person"))
        e.evaluateDue(autoPolicy)

        e.receive(trigger("ci", actor = "ci-bot", isPerson = false, addressed = false, externalId = "e-ci"))
        val outcome = e.evaluateDue(autoPolicy.copy(allowedActors = mapOf("s1" to listOf("alice", "ci-bot"))))[0]
        assertTrue(outcome is EvalOutcome.NotAdmitted)
        assertEquals("not_admitted:bot_requires_address", (store.load().decisions.getValue("ci") as Decision.Ignore).reason)
    }

    @Test
    fun `unlisted bot is rejected even when addressed`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("bot", actor = "unknown-bot", isPerson = false, addressed = true))
        val outcome = e.evaluateDue(autoPolicy.copy(allowedActors = mapOf("s1" to listOf("ci-bot"))))[0]

        assertTrue(outcome is EvalOutcome.NotAdmitted)
        assertEquals("not_admitted:bot_not_admitted", (store.load().decisions.getValue("bot") as Decision.Ignore).reason)
    }

    @Test
    fun `human classification comes only from source`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        // 名字像 bot,但 Source 判定为人:按人门禁走 explicit。
        e.receive(trigger("human", actor = "ci-bot"))
        val outcome = e.evaluateDue(autoPolicy.copy(allowedActors = mapOf("s1" to listOf("ci-bot"))))[0]

        val created = outcome as EvalOutcome.TicketCreated
        assertEquals(Strength.Explicit, created.ticket.mandate.strength)
        assertEquals("addressed", created.ticket.mandate.basis)
    }

    @Test
    fun `senderless person event follows admission table`() = runTest {
        val store = MemorySuperagentStore()
        val e = engine(store, acceptRouter())
        e.receive(trigger("anon", actor = ""))
        val outcome = Engine(store, strictRouter(), newId = ::ids, now = { 1000 }).evaluateDue(autoPolicy)[0]

        val created = outcome as EvalOutcome.TicketCreated
        assertEquals(Strength.Inferred, created.ticket.mandate.strength)
        assertEquals("addressed", created.ticket.mandate.basis)
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
        val executor = SuperExecutor { t, _ -> ExecutorReport(ReportState.Ready, "did ${t.title}", outcome = "done") }
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
        assertEquals(ReportState.Failed, store.load().assignments.single().state)
    }
}
