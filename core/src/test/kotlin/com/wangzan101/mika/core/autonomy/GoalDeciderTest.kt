package com.wangzan101.mika.core.autonomy

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class GoalDeciderTest {

    @Test
    fun `parses a simple decision`() {
        val d = GoalDecider.parse("""{"action":"RunStep","reason":"next step"}""")
        assertEquals(DecisionAction.RunStep, d.action)
        assertEquals("next step", d.reason)
    }

    @Test
    fun `parses replan with new steps`() {
        val d = GoalDecider.parse("""{"action":"Replan","steps":[{"description":"new path"}]}""")
        assertEquals(DecisionAction.Replan, d.action)
        assertEquals(1, d.newSteps.size)
        assertEquals("new path", d.newSteps[0].description)
    }

    @Test
    fun `action is case insensitive`() {
        assertEquals(DecisionAction.Done, GoalDecider.parse("""{"action":"done"}""").action)
        assertEquals(DecisionAction.Wait, GoalDecider.parse("""{"action":"WAIT"}""").action)
    }

    @Test
    fun `falls back to Wait on bad input`() {
        assertEquals(DecisionAction.Wait, GoalDecider.parse("not json").action)
        assertEquals(DecisionAction.Wait, GoalDecider.parse("""{"action":"???"}""").action)
        assertEquals(DecisionAction.Wait, GoalDecider.parse("""{"reason":"no action"}""").action)
    }
}
