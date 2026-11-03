package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchTest {

    private fun trigger(id: String, atMs: Long, actor: String = "alice") = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = "e-$id", threadKey = "t1",
        actor = actor, addressed = true, text = "work", atMs = atMs,
    )

    private fun gate(strength: Strength) = GateResult(strength, "test")

    @Test
    fun `explicit beats inferred regardless of time`() {
        val batch = listOf(trigger("inferred", 200, actor = "mallory") to gate(Strength.Inferred), trigger("explicit", 100) to gate(Strength.Explicit))
        assertEquals("explicit", batchSeed(batch)?.id)
    }

    @Test
    fun `same strength takes latest trigger`() {
        val batch = listOf(trigger("old", 100) to gate(Strength.Explicit), trigger("new", 200) to gate(Strength.Explicit))
        assertEquals("new", batchSeed(batch)?.id)
    }

    @Test
    fun `equal strength and time keep input order`() {
        val batch = listOf(trigger("first", 100) to gate(Strength.Explicit), trigger("second", 100) to gate(Strength.Explicit))
        assertEquals("first", batchSeed(batch)?.id)
    }

    @Test
    fun `none or empty has no seed`() {
        assertNull(batchSeed(emptyList()))
        assertNull(batchSeed(listOf(trigger("a", 1) to GateResult(Strength.None, "no"))))
    }

    @Test
    fun `started triggers are collected once`() {
        val tickets = listOf(
            Ticket(
                id = "tk1", threadKey = "t1", title = "one", instruction = "trigger:a",
                mandate = Mandate("p1", "s1", "phone", Strength.Explicit, "addressed", "alice", createdAtMs = 1),
                triggerIds = listOf("a"), createdAtMs = 1, updatedAtMs = 1,
            ),
            Ticket(
                id = "tk2", threadKey = "t1", title = "two", instruction = "trigger:b",
                mandate = Mandate("p1", "s1", "phone", Strength.Explicit, "addressed", "alice", createdAtMs = 1),
                triggerIds = listOf("b"), createdAtMs = 1, updatedAtMs = 1,
            ),
        )
        assertEquals(setOf("a", "b"), startedTicketTriggers(tickets))
    }
}
