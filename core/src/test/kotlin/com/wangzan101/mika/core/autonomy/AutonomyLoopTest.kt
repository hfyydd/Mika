package com.wangzan101.mika.core.autonomy

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import com.wangzan101.mika.core.child.ChildRunHost
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class AutonomyLoopTest {

    private class ScriptedModel(private val replies: MutableList<String>) : ChatModel {
        override val capabilities = com.wangzan101.mika.core.model.Capabilities(toolCalls = false, vision = false, contextTokens = 128_000, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val text = if (replies.isNotEmpty()) replies.removeAt(0) else "{}"
            return ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)
        }
    }

    private class FakeSpawn : ChildRunHost {
        val calls = mutableListOf<RunSpec>()
        var phase = ChildPhase.Completed
        var resultText = "ok"
        override suspend fun spawn(spec: RunSpec, format: String?): ChildResult {
            calls += spec
            return ChildResult(spec.id, phase, resultText)
        }
    }

    private fun loop(model: ChatModel, store: GoalStore, spawn: ChildRunHost) =
        AutonomyLoop(store, GoalPlanner(model), GoalDecider(model), spawn, now = { 1000L })

    @Test
    fun `creates plan then runs first step`() = runTest {
        val store = MemoryGoalStore()
        val goal = Goal(id = "g1", threadId = "t1", title = "test", description = "do something", createdAtMs = 0)
        store.upsert(goal)

        val replies = mutableListOf(
            """[{"description":"step one","expected":"done"}]""", // planner
            """{"action":"RunStep","reason":"go"}""",             // decider
        )
        val model = ScriptedModel(replies)
        val spawn = FakeSpawn()
        loop(model, store, spawn).process(store.get("g1")!!)

        val updated = store.get("g1")!!
        assertEquals(GoalStatus.Done, updated.status)
        assertEquals(1, updated.steps.size)
        assertEquals("Done", updated.steps[0].outcome)
        assertEquals(1, spawn.calls.size)
        assertEquals("step one", spawn.calls[0].goal)
    }

    @Test
    fun `failed step increments failure count and keeps cursor`() = runTest {
        val store = MemoryGoalStore()
        val step = GoalStep(description = "do it")
        store.upsert(Goal(id = "g1", threadId = "t1", title = "t", description = "d",
            status = GoalStatus.Active, steps = listOf(step), createdAtMs = 0))

        val replies = mutableListOf("""{"action":"RunStep"}""")
        val model = ScriptedModel(replies)
        val spawn = FakeSpawn().apply { phase = ChildPhase.Interrupted; resultText = "crashed" }
        loop(model, store, spawn).process(store.get("g1")!!)

        val updated = store.get("g1")!!
        assertEquals(1, updated.consecutiveFailures)
        assertEquals(0, updated.cursor)
        assertEquals("Failed", updated.steps[0].outcome)
        assertEquals(1, updated.round)
    }

    @Test
    fun `decider returns Failed when max rounds reached`() = runTest {
        val store = MemoryGoalStore()
        store.upsert(Goal(id = "g1", threadId = "t1", title = "t", description = "d",
            status = GoalStatus.Active, steps = listOf(GoalStep("s")), round = 20, maxRounds = 20, createdAtMs = 0))
        val model = ScriptedModel(mutableListOf()) // no replies needed; guard fires first
        val spawn = FakeSpawn()
        loop(model, store, spawn).process(store.get("g1")!!)
        assertEquals(GoalStatus.Failed, store.get("g1")!!.status)
    }

    @Test
    fun `replan replaces steps and resets cursor`() = runTest {
        val store = MemoryGoalStore()
        store.upsert(Goal(id = "g1", threadId = "t1", title = "t", description = "d",
            status = GoalStatus.Active, steps = listOf(GoalStep("old"), GoalStep("old2")), cursor = 1,
            createdAtMs = 0))
        val replies = mutableListOf(
            """[{"description":"new step"}]""", // planner for replan
        )
        val model = ScriptedModel(replies)
        // Decider returns Replan; planner gets called for new steps
        val deciderModel = ScriptedModel(mutableListOf("""{"action":"Replan","reason":"old steps failed"}"""))
        val spawn = FakeSpawn()
        AutonomyLoop(store, GoalPlanner(model), GoalDecider(deciderModel), spawn, now = { 1000L })
            .process(store.get("g1")!!)

        val updated = store.get("g1")!!
        assertEquals(0, updated.cursor)
        assertEquals(1, updated.steps.size)
        assertEquals("new step", updated.steps[0].description)
        assertEquals(GoalStatus.Active, updated.status)
    }

    @Test
    fun `wait keeps goal waiting`() = runTest {
        val store = MemoryGoalStore()
        store.upsert(Goal(id = "g1", threadId = "t1", title = "t", description = "d",
            status = GoalStatus.Active, steps = listOf(GoalStep("s")), createdAtMs = 0))
        val model = ScriptedModel(mutableListOf("""{"action":"Wait","reason":"not yet"}"""))
        val spawn = FakeSpawn()
        loop(model, store, spawn).process(store.get("g1")!!)
        assertEquals(GoalStatus.Waiting, store.get("g1")!!.status)
        assertTrue(spawn.calls.isEmpty())
    }

    @Test
    fun `tick skips terminal goals`() = runTest {
        val store = MemoryGoalStore()
        store.upsert(Goal(id = "done", threadId = "t", title = "t", description = "d", status = GoalStatus.Done))
        store.upsert(Goal(id = "failed", threadId = "t", title = "t", description = "d", status = GoalStatus.Failed))
        val model = ScriptedModel(mutableListOf())
        val spawn = FakeSpawn()
        loop(model, store, spawn).tick()
        assertTrue(spawn.calls.isEmpty())
    }
}
