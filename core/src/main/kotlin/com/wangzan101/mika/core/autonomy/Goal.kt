package com.wangzan101.mika.core.autonomy

import kotlinx.serialization.Serializable

/** 目标的状态:只往前进,失败后可以重试(回到 Pending),完成或放弃是终态。 */
@Serializable
enum class GoalStatus {
    Pending, Active, Waiting, Done, Failed, Abandoned,
}

/** 一次决策的动作:决定这一轮醒来后做什么。 */
@Serializable
enum class DecisionAction {
    RunStep, Replan, Wait, Done, Failed,
}

/** 目标的执行步骤;由 [GoalPlanner] 生成,执行完打上结果。 */
@Serializable
data class GoalStep(
    val description: String,
    val expected: String = "",
    val result: String = "",
    val outcome: String = "",
    val attempts: Int = 0,
)

/** 目标执行过程里的一条记录(每次醒来的决策和执行结果)。 */
@Serializable
data class GoalEvent(
    val atMs: Long,
    val action: DecisionAction,
    val summary: String = "",
)

@Serializable
data class Goal(
    val id: String,
    val threadId: String,
    val title: String,
    val description: String,
    val status: GoalStatus = GoalStatus.Pending,
    val steps: List<GoalStep> = emptyList(),
    val cursor: Int = 0,
    val events: List<GoalEvent> = emptyList(),
    val consecutiveFailures: Int = 0,
    val maxRounds: Int = 20,
    val round: Int = 0,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
) {
    companion object {
        const val MAX_EVENTS = 50
        const val MAX_CONSECUTIVE_FAILURES = 3
    }

    val currentStep: GoalStep? get() = steps.getOrNull(cursor)
    val isTerminal: Boolean get() = status in setOf(GoalStatus.Done, GoalStatus.Failed, GoalStatus.Abandoned)
    fun recentEvents(n: Int = 10): List<GoalEvent> = events.takeLast(n)
}
