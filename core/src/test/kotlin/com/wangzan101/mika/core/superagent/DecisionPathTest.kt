package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionPathTest {

    private var idCounter = 0
    private fun ids() = "id-${++idCounter}"

    private fun trigger(id: String, actor: String = "mallory", addressed: Boolean = true) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = "e-$id", threadKey = "t1",
        actor = actor, addressed = addressed, text = "please file the report", atMs = 100,
    )
    private val policy = Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("alice")))

    private fun strictAccept(id: String) = Decision.Accept(
        id, "File report", "reports", "matches standing request", "weekly-report",
        "create", trigger(id).text, true,
    )

    @Test
    fun `explicit path accepts without strict evidence`() = runTest {
        val store = MemorySuperagentStore()
        val e = Engine(store, Router { batch, _, _, _ ->
            batch.map { Decision.Accept(it.id, "work", "phone", "explicit user request") }
        }, newId = ::ids, now = { 1000 })
        e.receive(trigger("a", actor = "alice"))
        val outcome = e.evaluateDue(policy)[0]

        val created = outcome as EvalOutcome.TicketCreated
        assertEquals("explicit user request", created.ticket.mandate.matchedRule)
        assertTrue(store.load().decisions.getValue("a") is Decision.Accept)
    }

    @Test
    fun `strict inferred path accepts only complete evidence`() = runTest {
        val store = MemorySuperagentStore()
        val e = Engine(store, Router { batch, _, _, _ -> batch.map { strictAccept(it.id) } }, newId = ::ids, now = { 1000 })
        e.receive(trigger("a"))
        val outcome = e.evaluateDue(policy)[0]

        val created = outcome as EvalOutcome.TicketCreated
        assertEquals("weekly-report", created.ticket.mandate.matchedRule)
        assertEquals("create", (store.load().decisions.getValue("a") as Decision.Accept).direction)
    }

    @Test
    fun `inferred accept missing any strict element is rejected by host`() = runTest {
        val cases = listOf(
            strictAccept("a").copy(matchedRule = "") to "inferred_missing_rule",
            strictAccept("a").copy(direction = "") to "inferred_missing_direction",
            strictAccept("a").copy(motivationRef = "") to "inferred_missing_motivation",
            strictAccept("a").copy(hostVerified = false) to "inferred_missing_host_check",
        )
        for ((decision, reason) in cases) {
            val store = MemorySuperagentStore()
            val e = Engine(store, Router { _, _, _, _ -> listOf(decision) }, newId = ::ids, now = { 1000 })
            e.receive(trigger("a"))
            val outcome = e.evaluateDue(policy)[0]

            assertTrue(outcome is EvalOutcome.NotAdmitted)
            assertEquals(reason, store.load().dispositions.getValue("a").filtered)
            assertTrue(store.load().tickets.isEmpty())
        }
    }

    @Test
    fun `router parser keeps strict fields in order`() {
        val batch = listOf(trigger("a"), trigger("b"))
        val text = """
            [
              {"triggerId":"a","action":"accept","title":"T","scope":"S","reason":"explicit"},
              {"triggerId":"b","action":"accept","title":"T","scope":"S","reason":"inferred","matchedRule":"rule","direction":"create","motivationRef":"quote","hostVerified":true}
            ]
        """.trimIndent()
        val decisions = LlmRouter.parse(text, batch)
        assertTrue((decisions[0] as Decision.Accept).matchedRule.isEmpty())
        assertEquals("rule", (decisions[1] as Decision.Accept).matchedRule)
        assertTrue((decisions[1] as Decision.Accept).hostVerified)
    }
}
