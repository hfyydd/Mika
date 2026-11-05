package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationLogTest {
    private val trigger = Trigger("t1", SourceRef("telegram", "s1"), "u1", "thread", "@alice", text = "do it", atMs = 1)

    @Test
    fun `parses newest audit rows and promotes only the referenced source rule`() {
        val state = SuperagentState(
            triggers = listOf(trigger),
            observations = listOf("would accept t1: buy supplies", "broken entry"),
        )
        val rows = ObservationLog.rows(state)
        assertEquals(listOf(ObservationLog.Row("t1", "buy supplies")), rows)

        val stateWithDecision = state.copy(
            tickets = listOf(
                Ticket(
                    "tk", "thread", "work", "trigger:t1",
                    Mandate("p", "s1", "phone", Strength.Explicit, "allowed_address", "@alice", createdAtMs = 1),
                    triggerIds = listOf("t1"), createdAtMs = 1, updatedAtMs = 1,
                ),
            ),
            decisions = mapOf("t1" to Decision.Accept("t1", "work", "phone", "inferred", matchedRule = "weekly-report")),
        )
        val policy = ObservationLog.promote(Policy("p"), stateWithDecision, "t1")
        assertEquals(listOf("weekly-report"), policy.enabledRules.getValue("s1"))
        assertEquals(policy, ObservationLog.promote(policy, SuperagentState(), "missing"))
    }
}
