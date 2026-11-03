package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {

    private var idCounter = 0
    private fun ids() = "id-${++idCounter}"

    private fun engine(store: SuperagentStore = MemorySuperagentStore(), router: Router, clock: () -> Long) =
        Engine(store, router, newId = ::ids, now = clock)

    private fun trigger(id: String) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = "e-$id", threadKey = "t1",
        actor = "alice", addressed = true, text = "work", atMs = 100,
    )

    private val router = Router { batch, _, _, _ ->
        batch.map { Decision.Accept(it.id, "work", "phone", "ok") }
    }
    private val policy = Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("alice")))

    private suspend fun created(store: SuperagentStore): EvalOutcome.TicketCreated {
        val clock = arrayOf(1000L)
        val e = engine(store, router) { clock[0] }
        e.receive(trigger("a"))
        return e.evaluateDue(policy)[0] as EvalOutcome.TicketCreated
    }

    @Test
    fun `progress and waiting stay alive and waiting is computed from fields`() = runTest {
        val store = MemorySuperagentStore()
        val work = created(store)
        store.let {
            val e = Engine(it, router, newId = ::ids, now = { 2000 })
            e.report(work.assignment.id, ExecutorReport(ReportState.Progress, "opening app"))
            e.report(work.assignment.id, ExecutorReport(ReportState.Waiting, "need a choice", question = "which account?"))
        }

        val assignment = store.load().assignments.single { it.id == work.assignment.id }
        assertEquals(AssignmentStatus.Running, assignment.status)
        assertEquals(ReportState.Waiting, assignment.state)
        assertEquals(2, assignment.reports.size)
        assertTrue(waitsForPerson(assignment))
        assertTrue(store.load().tickets.single { it.id == work.ticket.id }.isOpen)
    }

    @Test
    fun `waiting does not guess from result text`() {
        val asking = Assignment("a", "tk", AssignmentStatus.Running, state = ReportState.Waiting, result = "question?", question = "which?")
        val mentioning = Assignment("b", "tk", AssignmentStatus.Running, state = ReportState.Ready, result = "question?", question = "which?")
        val silent = Assignment("c", "tk", AssignmentStatus.Running, state = ReportState.Waiting, question = "")
        assertTrue(waitsForPerson(asking))
        assertFalse(waitsForPerson(mentioning))
        assertFalse(waitsForPerson(silent))
    }

    @Test
    fun `ready done merged and closed end assignment and ticket`() = runTest {
        for (outcome in listOf("done", "merged", "closed")) {
            val store = MemorySuperagentStore()
            val work = created(store)
            Engine(store, router, newId = ::ids, now = { 2000 }).report(
                work.assignment.id, ExecutorReport(ReportState.Ready, "complete", outcome = outcome),
            )
            val loaded = store.load()
            assertEquals(AssignmentStatus.Ended, loaded.assignments.single().status)
            assertEquals(outcome, loaded.tickets.single { it.id == work.ticket.id }.ended?.outcome)
        }
    }

    @Test
    fun `blocked and failed end the assignment but keep the ticket open`() = runTest {
        for (state in listOf(ReportState.Blocked, ReportState.Failed)) {
            val store = MemorySuperagentStore()
            val work = created(store)
            Engine(store, router, newId = ::ids, now = { 2000 }).report(work.assignment.id, ExecutorReport(state, "stopped early"))
            val loaded = store.load()
            assertEquals(state, loaded.assignments.single().state)
            assertEquals(AssignmentStatus.Ended, loaded.assignments.single().status)
            assertTrue(loaded.tickets.single { it.id == work.ticket.id }.isOpen)
        }
    }

    @Test
    fun `dropped ends the ticket as dropped`() = runTest {
        val store = MemorySuperagentStore()
        val work = created(store)
        Engine(store, router, newId = ::ids, now = { 2000 }).report(work.assignment.id, ExecutorReport(ReportState.Dropped, "cannot continue"))
        val loaded = store.load()
        assertEquals(AssignmentStatus.Ended, loaded.assignments.single().status)
        assertEquals("dropped", loaded.tickets.single { it.id == work.ticket.id }.ended?.outcome)
    }

    @Test
    fun `person stop remains the stopped ending`() = runTest {
        val store = MemorySuperagentStore()
        val work = created(store)
        Engine(store, router, newId = ::ids, now = { 2000 }).stopTicket(work.ticket.id, "cancel")
        assertEquals("stopped", store.load().tickets.single().ended?.outcome)
    }
}
