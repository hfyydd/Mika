package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnershipTest {

    private fun ticket(id: String, threadKey: String = "t1", status: TicketStatus = TicketStatus.Open, endedBy: String? = null) = Ticket(
        id = id,
        threadKey = threadKey,
        title = "work",
        instruction = "trigger:t",
        mandate = Mandate("p1", "s1", "phone", Strength.Explicit, "addressed", "alice", createdAtMs = 1),
        triggerIds = listOf("t"),
        status = status,
        ended = endedBy?.let { TicketEnd(2, it, "ended", "ended") },
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun trigger(id: String, threadKey: String = "t1", isPerson: Boolean = true) = Trigger(
        id = id,
        source = SourceRef("test", "s1"),
        externalId = "e-$id",
        threadKey = threadKey,
        actor = "alice",
        isPerson = isPerson,
        text = "follow up",
        atMs = 3,
    )

    @Test
    fun `open ticket on the person thread owns the followup`() {
        assertEquals(listOf("tk1"), followupOwners(trigger("a"), listOf(ticket("tk1"))))
    }

    @Test
    fun `non person and other thread have no followup owner`() {
        assertEquals(emptyList<String>(), followupOwners(trigger("a", isPerson = false), listOf(ticket("tk1"))))
        assertEquals(emptyList<String>(), followupOwners(trigger("a", threadKey = "other"), listOf(ticket("tk1"))))
    }

    @Test
    fun `person stopped ticket does not own but executor ended ticket recovers`() {
        val tickets = listOf(ticket("stopped", status = TicketStatus.Ended, endedBy = "person"), ticket("done", status = TicketStatus.Ended, endedBy = "executor"))
        assertEquals(listOf("done"), followupOwners(trigger("a"), tickets))
    }

    @Test
    fun `policy revoked ticket cannot be revived as followup`() {
        val tickets = listOf(ticket("revoked", status = TicketStatus.Ended, endedBy = "policy"))
        assertEquals(emptyList<String>(), followupOwners(trigger("a"), tickets))
    }

    @Test
    fun `handback and read only context do not own`() {
        val handback = TriggerDisposition(
            triggerId = "a", fanout = listOf("tk1"), handbacks = listOf(Handback("tk1", "outside scope", 2)), atMs = 1,
        )
        assertEquals(emptyList<String>(), followupOwners(trigger("a"), listOf(ticket("tk1")), mapOf("a" to handback)))

        val context = TriggerDisposition(triggerId = "a", context = listOf("tk1"), atMs = 1)
        assertEquals(emptyList<String>(), followupOwners(trigger("a"), listOf(ticket("tk1")), mapOf("a" to context)))
    }

    @Test
    fun `queued evaluation and completed decision do not become followups`() {
        val queued = TriggerDisposition(triggerId = "a", requeued = true, atMs = 1)
        assertEquals(emptyList<String>(), followupOwners(trigger("a"), listOf(ticket("tk1")), mapOf("a" to queued)))

        val decided = TriggerDisposition(triggerId = "a", decisionId = "a", atMs = 1)
        assertEquals(emptyList<String>(), followupOwners(trigger("a"), listOf(ticket("tk1")), mapOf("a" to decided)))
    }
}
