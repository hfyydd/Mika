package com.wangzan101.mika.core.autonomy

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class GoalPlannerTest {

    @Test
    fun `parses a plain JSON array of steps`() {
        val steps = GoalPlanner.parseSteps("""[{"description":"open app","expected":"app open"}]""")
        assertEquals(1, steps.size)
        assertEquals("open app", steps[0].description)
        assertEquals("app open", steps[0].expected)
    }

    @Test
    fun `parses steps wrapped in markdown fences`() {
        val steps = GoalPlanner.parseSteps("```json\n[{\"description\":\"a\"},{\"description\":\"b\",\"expected\":\"c\"}]\n```")
        assertEquals(2, steps.size)
        assertEquals("b", steps[1].description)
        assertEquals("c", steps[1].expected)
    }

    @Test
    fun `returns empty on invalid json`() {
        assertTrue(GoalPlanner.parseSteps("not json").isEmpty())
        assertTrue(GoalPlanner.parseSteps("""{"not":"array"}""").isEmpty())
        assertTrue(GoalPlanner.parseSteps("").isEmpty())
    }

    @Test
    fun `skips entries without description`() {
        val steps = GoalPlanner.parseSteps("""[{"expected":"no desc"},{"description":"ok"}]""")
        assertEquals(1, steps.size)
        assertEquals("ok", steps[0].description)
    }

    @Test
    fun `caps at 20 steps`() {
        val many = (1..30).joinToString(",", prefix = "[", postfix = "]") { """{"description":"s$it"}""" }
        assertEquals(20, GoalPlanner.parseSteps(many).size)
    }
}
