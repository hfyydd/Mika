package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Test

class SuperagentNotificationsTest {
    @Test
    fun `only waiting questions become notifications and stay deduplicated`() {
        fun ticket(id: String) = Ticket(
            id, "thread", "Ticket $id", "trigger:$id",
            Mandate("p", "s1", "scope", Strength.Explicit, "allowed_address", "alice", createdAtMs = 1),
            listOf(id), createdAtMs = 1, updatedAtMs = 1,
        )
        val running = Assignment("a1", "t1", status = AssignmentStatus.Running, state = ReportState.Progress)
        val waiting = Assignment("a2", "t2", status = AssignmentStatus.Running, state = ReportState.Waiting, question = "Need login")
        val duplicate = Assignment("a3", "t2", status = AssignmentStatus.Running, state = ReportState.Waiting, question = "Need login")
        val state = SuperagentState(tickets = listOf(ticket("t2"), ticket("t1")), assignments = listOf(running, waiting, duplicate))
        assertEquals(
            listOf(SuperagentNotifications.Draft("t2", "Ticket t2", "Need login", "thread")),
            SuperagentNotifications.drafts(state),
        )
    }
}
