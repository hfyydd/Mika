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

        val policy = ObservationLog.promote(Policy("p"), state, "t1")
        assertEquals(listOf("telegram:s1:addressed"), policy.enabledRules.getValue("s1"))
        assertEquals(policy, ObservationLog.promote(policy, SuperagentState(), "missing"))
    }
}
