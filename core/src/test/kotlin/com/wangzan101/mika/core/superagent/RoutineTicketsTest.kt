package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.autonomy.Goal
import com.wangzan101.mika.core.autonomy.GoalStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutineTicketsTest {
    private fun goal() = Goal("g1", "thread", "Follow up weekly", "Collect three updates", status = GoalStatus.Pending, updatedAtMs = 10)

    @Test
    fun `goal becomes one explicit routine ticket`() = runTest {
        val store = MemorySuperagentStore()
        val engine = Engine(store, RoutineTickets.router, newId = { "ticket-g1" }, now = { 20 })
        val g = goal()
        engine.receive(RoutineTickets.trigger(g, "p1"))
        engine.evaluateDue(RoutineTickets.policy(g))

        val state = engine.snapshot()
        assertEquals(listOf("ticket-g1"), state.tickets.map { it.id })
        assertEquals(Strength.Explicit, state.tickets.single().mandate.strength)
        assertEquals(GoalStatus.Active, RoutineTickets.sync(g, state).status)
        assertEquals(1, state.threads.single().subscriptions.size)
    }

    @Test
    fun `ticket end converges routine goal`() {
        val base = engineState()
        val done = base.copy(tickets = base.tickets.map {
            it.copy(status = TicketStatus.Ended, ended = TicketEnd(30, "executor", "all updates collected", "done"))
        })
        assertEquals(GoalStatus.Done, RoutineTickets.sync(goal(), done).status)
    }

    private fun engineState(): SuperagentState {
        val ticket = Ticket(
            "ticket-g1", "routine:g1", "Follow up weekly", "trigger:routine:g1",
            Mandate("p", "g1", "routine", Strength.Explicit, "allowed_address", "owner", createdAtMs = 20),
            listOf("routine:g1"), createdAtMs = 20, updatedAtMs = 30,
        )
        return SuperagentState(tickets = listOf(ticket))
    }
}
