package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MandateRevalidationTest {

    private var idCounter = 0
    private fun ids() = "id-${++idCounter}"

    private fun trigger(id: String, actor: String = "alice", isPerson: Boolean = true, addressed: Boolean = true) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = "e-$id", threadKey = "t1",
        actor = actor, isPerson = isPerson, addressed = addressed, text = "work", atMs = 100,
    )

    private fun router(strengthExplicit: Boolean = true): Router = Router { batch, _, _, _ ->
        batch.map {
            if (strengthExplicit) Decision.Accept(it.id, "work", "phone", "explicit")
            else Decision.Accept(
                it.id, "work", "phone", "inferred", matchedRule = "weekly-report",
                direction = "create", motivationRef = it.text, hostVerified = true,
            )
        }
    }

    private suspend fun openTicket(
        policy: Policy = Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("alice"))),
        triggerId: String = "a",
        actor: String = "alice",
        isPerson: Boolean = true,
        decisionRouter: Router = router(),
    ): SuperagentStore {
        val store = MemorySuperagentStore()
        Engine(store, decisionRouter, newId = ::ids, now = { 1000 }).let {
            it.receive(trigger(triggerId, actor = actor, isPerson = isPerson))
            it.evaluateDue(policy)
        }
        return store
    }

    @Test
    fun `revokes removed actor and reports effect`() = runTest {
        val store = openTicket()
        val effects = Engine(store, router(), newId = ::ids, now = { 2000 }).policyChanged(
            Policy("p2", AcceptMode.Auto, mapOf("s1" to listOf("bob"))),
        )
        assertEquals(listOf(PolicyEffect(store.load().tickets.single().id, "actor_not_allowed")), effects)
        assertEquals(TicketStatus.Ended, store.load().tickets.single().status)
    }

    @Test
    fun `revokes inactive source`() = runTest {
        val store = openTicket()
        val effects = Engine(store, router(), newId = ::ids, now = { 2000 }).policyChanged(
            Policy("p2", AcceptMode.Auto, mapOf("s1" to listOf("alice")), activeSources = mapOf("s1" to false)),
        )
        assertEquals("source_inactive", effects.single().reason)
    }

    @Test
    fun `revokes bot ticket when actor leaves allowlist`() = runTest {
        val store = openTicket(
            Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("ci-bot"))),
            "ci", actor = "ci-bot", isPerson = false, decisionRouter = router(strengthExplicit = false),
        )
        val e = Engine(store, router(strengthExplicit = false), newId = ::ids, now = { 1000 })
        // Replace the original mandate with the machine basis this test needs.
        store.save(store.load().let { s ->
            s.copy(tickets = s.tickets.map { t -> t.copy(mandate = t.mandate.copy(actor = "ci-bot", basis = "bot_allowlisted", strength = Strength.Inferred)) })
        })
        val effects = e.policyChanged(Policy("p2", AcceptMode.Auto, mapOf("s1" to listOf("alice"))))
        assertEquals("actor_not_allowed", effects.single().reason)
    }

    @Test
    fun `revokes disabled rule but keeps unknown rule policy open`() = runTest {
        val store = openTicket(Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("mallory"))), actor = "mallory")
        store.save(store.load().let { s ->
            s.copy(tickets = s.tickets.map { it.copy(mandate = it.mandate.copy(actor = "mallory", matchedRule = "weekly-report")) })
        })
        val disabled = Engine(store, router(), newId = ::ids, now = { 2000 }).policyChanged(
            Policy("p2", AcceptMode.Auto, mapOf("s1" to listOf("mallory")), enabledRules = mapOf("s1" to listOf("other"))),
        )
        assertEquals("rule_disabled", disabled.single().reason)

        val fresh = openTicket(Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("mallory"))), actor = "mallory")
        fresh.save(fresh.load().let { s ->
            s.copy(tickets = s.tickets.map { it.copy(mandate = it.mandate.copy(actor = "mallory", matchedRule = "weekly-report")) })
        })
        val enabled = Engine(fresh, router(), newId = ::ids, now = { 2000 }).policyChanged(
            Policy("p2", AcceptMode.Auto, mapOf("s1" to listOf("mallory")), enabledRules = mapOf("s1" to listOf("weekly-report"))),
        )
        assertTrue(enabled.isEmpty())
        assertTrue(fresh.load().tickets.single().isOpen)
    }

    @Test
    fun `revokes weakened admission cell only during revalidation`() = runTest {
        val store = openTicket()
        val effects = Engine(store, router(), newId = ::ids, now = { 2000 }).policyChanged(
            Policy(
                "p2", AcceptMode.Auto, mapOf("s1" to listOf("alice")),
                admissionTables = mapOf("s1" to mapOf("*" to AdmissionTable.DEFAULT.copy(allowedAddressed = AdmissionCell.Inferred))),
            ),
        )
        assertEquals("table_weakened", effects.single().reason)
        assertEquals(TicketStatus.Ended, store.load().tickets.single().status)
    }

    @Test
    fun `revokes when accept mode is no longer auto`() = runTest {
        val store = openTicket()
        val effects = Engine(store, router(), newId = ::ids, now = { 2000 }).policyChanged(
            Policy("p2", AcceptMode.Observe, mapOf("s1" to listOf("alice"))),
        )
        assertEquals("mode_not_auto", effects.single().reason)
    }
}
