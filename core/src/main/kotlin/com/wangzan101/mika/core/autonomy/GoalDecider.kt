package com.wangzan101.mika.core.autonomy

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class Decision(val action: DecisionAction, val reason: String = "", val newSteps: List<GoalStep> = emptyList())

class GoalDecider(private val model: ChatModel) {

    suspend fun decide(goal: Goal): Decision {
        if (goal.round >= goal.maxRounds) return Decision(DecisionAction.Failed, "轮数用尽")
        if (goal.consecutiveFailures >= Goal.MAX_CONSECUTIVE_FAILURES)
            return Decision(DecisionAction.Failed, "连续失败 ${goal.consecutiveFailures} 次")

        val system = """
            You are the decision engine for a long-running AI goal on a phone.
            Decide what to do next. Options: RunStep, Replan, Wait, Done, Failed.
            Reply ONLY JSON: {"action": "...", "reason": "..."}
            For Replan add "steps": [{"description": "...", "expected": "..."}]
        """.trimIndent()

        val state = buildString {
            appendLine("Goal: ${goal.title}")
            appendLine("Description: ${goal.description}")
            appendLine("Status: ${goal.status.name}, round ${goal.round}/${goal.maxRounds}, failures: ${goal.consecutiveFailures}")
            if (goal.steps.isNotEmpty()) {
                appendLine("Steps:")
                goal.steps.forEachIndexed { i, s ->
                    val mark = if (i < goal.cursor) "[done]" else if (i == goal.cursor) "[→]" else "[ ]"
                    appendLine("  $mark ${s.description}" + if (s.result.isNotBlank()) " → ${s.outcome}: ${s.result.take(150)}" else "")
                }
            }
            goal.recentEvents().forEach { appendLine("  ${it.action.name}: ${it.summary.take(200)}") }
        }

        val reply = model.complete(system, listOf(Message(Role.User, listOf(Part.Text(state)), Author.Mika)), emptyList())
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return Companion.parse(text)
    }

    companion object {
        private val ACTION_PATTERN = Regex("\"action\"\\s*:\\s*\"(\\w+)\"")
        private val REASON_PATTERN = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")
        private val STEP_DESC_PATTERN = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"")
        private val STEP_EXPECT_PATTERN = Regex("\"expected\"\\s*:\\s*\"([^\"]*)\"")

        fun parse(text: String): Decision {
            val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val actionName = ACTION_PATTERN.find(trimmed)?.groupValues?.get(1)
                ?: return Decision(DecisionAction.Wait, "未找到 action")
            val action = DecisionAction.entries.find { it.name.equals(actionName.trim(), ignoreCase = true) }
                ?: return Decision(DecisionAction.Wait, "未知 action: $actionName")
            val reason = REASON_PATTERN.find(trimmed)?.groupValues?.get(1) ?: ""
            val descMatches = STEP_DESC_PATTERN.findAll(trimmed).toList()
            val expectMatches = STEP_EXPECT_PATTERN.findAll(trimmed).toList()
            val steps = descMatches.mapIndexed { i, m ->
                GoalStep(m.groupValues[1].trim(), expectMatches.getOrNull(i)?.groupValues?.get(1)?.trim() ?: "")
            }
            return Decision(action, reason, steps)
        }
    }
}
