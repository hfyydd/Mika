package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private fun trigger(id: String) = Trigger(
        id = id, source = SourceRef("test", "s1"), externalId = "e-$id", threadKey = "t1",
        actor = "alice", text = "hello", atMs = 0,
    )

    @Test
    fun `parses one decision per trigger in order`() {
        val batch = listOf(trigger("a"), trigger("b"), trigger("c"))
        val text = """
            [
              {"triggerId":"a","action":"accept","title":"Fix bug","scope":"repo only","reason":"explicit"},
              {"triggerId":"b","action":"ignore","reason":"not work"},
              {"triggerId":"c","action":"attach","ticketId":"tk1","reason":"points at it"}
            ]
        """.trimIndent()
        val decisions = LlmRouter.parse(text, batch)
        assertEquals(3, decisions.size)
        assertTrue(decisions[0] is Decision.Accept)
        assertEquals("Fix bug", (decisions[0] as Decision.Accept).title)
        assertTrue(decisions[1] is Decision.Ignore)
        assertTrue(decisions[2] is Decision.Attach)
        assertEquals("tk1", (decisions[2] as Decision.Attach).ticketId)
    }

    @Test
    fun `handles markdown fences`() {
        val batch = listOf(trigger("a"))
        val text = "```json\n[{\"triggerId\":\"a\",\"action\":\"accept\",\"title\":\"T\",\"scope\":\"S\"}]\n```"
        assertTrue(LlmRouter.parse(text, batch)[0] is Decision.Accept)
    }

    @Test
    fun `missing entries and bad actions become ignore`() {
        val batch = listOf(trigger("a"), trigger("b"))
        val text = """[{"triggerId":"a","action":"???","reason":"weird"}]"""
        val decisions = LlmRouter.parse(text, batch)
        assertEquals(2, decisions.size)
        assertTrue(decisions[0] is Decision.Ignore)
        assertTrue(decisions[1] is Decision.Ignore)
    }

    @Test
    fun `unparseable output ignores everything`() {
        val batch = listOf(trigger("a"))
        assertTrue(LlmRouter.parse("not json at all", batch)[0] is Decision.Ignore)
    }
}
