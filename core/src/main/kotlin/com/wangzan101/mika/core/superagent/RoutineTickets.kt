package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.autonomy.Goal
import com.wangzan101.mika.core.autonomy.GoalStatus

/** 长期目标统一成一个显式例程票;不再保留第二条执行状态机。 */
object RoutineTickets {
    const val SOURCE_KIND = "routine"

    fun trigger(goal: Goal, policyVersion: String): Trigger = Trigger(
        id = "routine:${goal.id}",
        source = SourceRef(SOURCE_KIND, goal.id),
        externalId = "routine:${goal.id}:${goal.updatedAtMs}",
        threadKey = "routine:${goal.id}",
        actor = "owner",
        addressed = true,
        text = listOf(goal.title, goal.description, goal.currentStep?.description.orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n"),
        atMs = goal.updatedAtMs,
    )

    fun policy(goal: Goal, version: String = goal.id) = Policy(
        version = version,
        acceptMode = AcceptMode.Auto,
        allowedActors = mapOf(goal.id to listOf("owner")),
    )

    /** 只接受 owner 的显式例程请求;不开另一条 planner/decider 状态机。 */
    val router = Router { batch, _, _, _ ->
        batch.map { trigger ->
            if (trigger.id.startsWith("routine:")) {
                Decision.Accept(trigger.id, trigger.text.lines().first(), "routine", "explicit owner routine")
            } else {
                Decision.Ignore(trigger.id, "not a routine")
            }
        }
    }

    /**
     * 从 Superagent 票状态反推 Goal 状态;不动 Ticket/Assignment,Engine 仍是唯一写者。
     * 没有票表示还没提交;Waiting 的报告映射成等待;票结束时按结局收敛。
     */
    fun sync(goal: Goal, state: SuperagentState): Goal {
        val triggerId = "routine:${goal.id}"
        val ticket = state.tickets.lastOrNull { triggerId in it.triggerIds } ?: return goal
        val assignment = state.assignments.filter { it.ticketId == ticket.id }.maxByOrNull { it.reports.size }
        val status = when {
            ticket.isOpen && waitsForPerson(assignment ?: Assignment("none", ticket.id)) -> GoalStatus.Waiting
            ticket.isOpen -> GoalStatus.Active
            ticket.ended?.outcome == "done" || ticket.ended?.outcome == "merged" || ticket.ended?.outcome == "closed" -> GoalStatus.Done
            ticket.ended?.by == "person" -> GoalStatus.Abandoned
            else -> GoalStatus.Failed
        }
        return if (status == goal.status) goal else goal.copy(status = status, updatedAtMs = ticket.updatedAtMs)
    }
}
