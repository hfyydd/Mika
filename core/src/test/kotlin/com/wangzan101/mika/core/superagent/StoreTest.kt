package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StoreTest {

    @Test
    fun `file store roundtrip and atomic replace`() {
        val path = Files.createTempDirectory("superagent-test").resolve("superagent-v1.json")
        val store = FileSuperagentStore(path)
        val trigger = Trigger("t1", SourceRef("test", "s1"), "e1", "th1", "alice", text = "hi", atMs = 1)
        val disposition = TriggerDisposition(triggerId = "t1", evalId = "ev1", atMs = 1)
        store.save(SuperagentState(
            triggers = listOf(trigger),
            decisions = mapOf("t1" to Decision.Accept("t1", "Work", "repo", "explicit")),
            dispositions = mapOf("t1" to disposition),
            readPositions = mapOf("test:s1" to "e1"),
        ))
        val loaded = FileSuperagentStore(path).load()
        assertEquals(1, loaded.triggers.size)
        assertEquals("t1", loaded.triggers[0].id)
        assertEquals("e1", loaded.readPositions["test:s1"])
        assertEquals("ev1", loaded.dispositions.getValue("t1").evalId)
        assertEquals("Work", (loaded.decisions.getValue("t1") as Decision.Accept).title)
    }

    @Test
    fun `corrupt file loads empty state`() {
        val path = Files.createTempDirectory("superagent-test").resolve("superagent-v1.json")
        Files.writeString(path, "{ not json")
        assertTrue(FileSuperagentStore(path).load().triggers.isEmpty())
    }
}
