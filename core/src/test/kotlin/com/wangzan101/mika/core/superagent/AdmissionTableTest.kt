package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionTableTest {

    @Test
    fun `defaults match current admission behavior`() {
        assertEquals(AdmissionCell.Explicit, AdmissionTable.DEFAULT.cell(Identity.Allowed, Targeting.Address))
        assertEquals(AdmissionCell.Explicit, AdmissionTable.DEFAULT.cell(Identity.Allowed, Targeting.Followup))
        assertEquals(AdmissionCell.Inferred, AdmissionTable.DEFAULT.cell(Identity.Allowed, Targeting.Unaddressed))
        assertEquals(AdmissionCell.Inferred, AdmissionTable.DEFAULT.cell(Identity.Unlisted, Targeting.Address))
        assertEquals(AdmissionCell.Ignore, AdmissionTable.DEFAULT.cell(Identity.Unlisted, Targeting.Followup))
        assertEquals(AdmissionCell.Ignore, AdmissionTable.DEFAULT.cell(Identity.Unlisted, Targeting.Unaddressed))
        assertEquals(AdmissionCell.Inferred, AdmissionTable.DEFAULT.cell(Identity.Unknown, Targeting.Address))
    }

    @Test
    fun `per source and scope table overrides with wildcard fallback`() {
        val policy = Policy(
            version = "p1",
            admissionTables = mapOf(
                "telegram" to mapOf(
                    "reports" to AdmissionTable.DEFAULT.copy(unlistedAddressed = AdmissionCell.Ignore),
                    "*" to AdmissionTable.DEFAULT.copy(allowedAddressed = AdmissionCell.Inferred),
                ),
            ),
        )
        assertEquals(AdmissionCell.Ignore, policy.admissionTable("telegram", "reports").unlistedAddressed)
        assertEquals(AdmissionCell.Inferred, policy.admissionTable("telegram", "other").allowedAddressed)
        assertEquals(AdmissionCell.Explicit, policy.admissionTable("other", "reports").allowedAddressed)
    }

    @Test
    fun `explicit address cell wins before followup and config drives gate`() = runTest {
        val store = MemorySuperagentStore()
        val policy = Policy("p1", AcceptMode.Auto, mapOf("s1" to listOf("alice")))
        val e = Engine(
            store,
            Router { batch, _, _, _ -> batch.map { Decision.Accept(it.id, "work", "phone", "ok") } },
            newId = { "id-1" }, now = { 1000 },
        )
        e.receive(Trigger("a", SourceRef("test", "s1"), "e-a", "t1", "alice", addressed = true, text = "work", atMs = 1))
        val outcome = e.evaluateDue(policy)[0]

        assertEquals(Strength.Explicit, (outcome as EvalOutcome.TicketCreated).ticket.mandate.strength)
        assertTrue(store.load().decisions.getValue("a") is Decision.Accept)
    }
}
