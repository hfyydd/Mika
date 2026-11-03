package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketPanelTest {
    private fun ticket(id: String, open: Boolean = true, at: Long = 1) = Ticket(
        id, "thread", "Ticket $id", "trigger:$id",
        Mandate("p", "s1", "scope", Strength.Explicit, "allowed_address", "alice", createdAtMs = at),
        listOf(id), createdAtMs = at, updatedAtMs = at,
    ).let {
        if (open) it else it.copy(status = TicketStatus.Ended, ended = TicketEnd(at, "executor", "done", "done"))
    }

    @Test
    fun `person attention sorts above other stages`() {
        val running = Assignment("a1", "t-running", status = AssignmentStatus.Running, state = ReportState.Progress)
        val person = Assignment("a2", "t-person", status = AssignmentStatus.Running, state = ReportState.Waiting, question = "captcha?")
        val state = SuperagentState(
            tickets = listOf(ticket("t-running"), ticket("t-person", at = 2)),
            assignments = listOf(running, person),
        )
        assertEquals(listOf("t-person", "t-running"), TicketPanel.rows(state).map { it.id })
    }

    @Test
    fun `detail exposes original inputs and computed stage`() {
        val trigger = Trigger("r2", SourceRef("telegram", "s1"), "u2", "thread", "alice", text = "add this", atMs = 2)
        val assignment = Assignment("a", "t1", status = AssignmentStatus.Running, state = ReportState.Waiting, question = "which account?")
        val withInput = ticket("t1").copy(inputs = listOf(TicketInput("i1", "t1", "r2", 2)))
        val state = SuperagentState(triggers = listOf(trigger), tickets = listOf(withInput), assignments = listOf(assignment))
        val detail = TicketPanel.detail(state, "t1")
        assertEquals(PanelStage.Person, detail?.stage)
        assertEquals(listOf("add this"), detail?.inputs?.map { it.text })
    }
}
