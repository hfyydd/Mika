package com.wangzan101.mika.core.autonomy

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class GoalStoreTest {

    @Test
    fun `memory store roundtrip`() {
        val store = MemoryGoalStore()
        val goal = Goal(id = "g1", threadId = "t1", title = "test", description = "desc")
        store.upsert(goal)
        assertEquals("test", store.get("g1")?.title)
        store.remove("g1")
        assertTrue(store.get("g1") == null)
    }

    @Test
    fun `memory store upsert replaces`() {
        val store = MemoryGoalStore()
        store.upsert(Goal(id = "g1", threadId = "t", title = "v1", description = "d"))
        store.upsert(Goal(id = "g1", threadId = "t", title = "v2", description = "d"))
        assertEquals("v2", store.get("g1")?.title)
        assertEquals(1, store.all().size)
    }
}
