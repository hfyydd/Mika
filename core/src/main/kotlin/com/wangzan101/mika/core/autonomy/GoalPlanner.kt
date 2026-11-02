package com.wangzan101.mika.core.autonomy

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Role

class GoalPlanner(private val model: ChatModel) {

    suspend fun plan(goal: Goal): List<GoalStep> {
        val system = """
            You are a planner for an AI assistant that operates apps and websites on a phone.
            Given a long-term goal, break it into concrete, ordered steps.
            Each step is one task the assistant can do in a single run.
            Steps should be as few as possible.
            Reply with ONLY a JSON array of objects with "description" and "expected".
        """.trimIndent()

        val history = goal.recentEvents().joinToString("\n") { "- ${it.action.name}: ${it.summary}" }
        val doneSteps = goal.steps.take(goal.cursor).joinToString("\n") { "- [done] ${it.description}" }
        val failedSteps = goal.steps.drop(goal.cursor).filter { it.outcome == "Failed" }
            .joinToString("\n") { "- [failed ${it.attempts}x] ${it.description} → ${it.result.take(200)}" }

        val user = buildString {
            appendLine("Goal: ${goal.title}")
            appendLine("Description: ${goal.description}")
            if (doneSteps.isNotBlank()) { appendLine("Already done:"); appendLine(doneSteps) }
            if (failedSteps.isNotBlank()) { appendLine("Failed steps (avoid same approach):"); appendLine(failedSteps) }
            if (history.isNotBlank()) { appendLine("Recent:"); appendLine(history) }
            appendLine("Plan the remaining steps.")
        }

        val reply = model.complete(system, listOf(Message(Role.User, listOf(Part.Text(user)), Author.Mika)), emptyList())
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return Companion.parseSteps(text)
    }

    companion object {
        private val DESC_PATTERN = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"")
        private val EXPECT_PATTERN = Regex("\"expected\"\\s*:\\s*\"([^\"]*)\"")

        fun parseSteps(text: String): List<GoalStep> {
            val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            // 逐段扫描:每遇到一个 "description" 就开启一个新步骤,后面的 "expected" 归它。
            val descMatches = DESC_PATTERN.findAll(trimmed).toList()
            if (descMatches.isEmpty()) return emptyList()
            val expectMatches = EXPECT_PATTERN.findAll(trimmed).toList()
            return descMatches.mapIndexed { i, desc ->
                val descEnd = desc.range.last
                // 找这个 description 之后、下一个 description 之前的 expected
                val nextDescStart = descMatches.getOrNull(i + 1)?.range?.first ?: trimmed.length
                val expected = expectMatches.firstOrNull { it.range.first > descEnd && it.range.first < nextDescStart }
                GoalStep(desc.groupValues[1].trim(), expected?.groupValues?.get(1)?.trim() ?: "")
            }.take(20)
        }
    }
}
