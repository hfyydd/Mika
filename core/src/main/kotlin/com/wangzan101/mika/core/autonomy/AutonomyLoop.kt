package com.wangzan101.mika.core.autonomy

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import com.wangzan101.mika.core.child.ChildRunHost
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec

class AutonomyLoop(
    private val store: GoalStore,
    private val planner: GoalPlanner,
    private val decider: GoalDecider,
    private val spawn: ChildRunHost,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun tick() {
        store.all().filter { !it.isTerminal }.forEach { goal ->
            try { process(goal) } catch (_: Exception) { }
        }
    }

    suspend fun process(original: Goal) {
        var goal = original

        if (goal.steps.isEmpty() && goal.status == GoalStatus.Pending) {
            val steps = planner.plan(goal)
            if (steps.isEmpty()) {
                save(goal.copy(status = GoalStatus.Failed, updatedAtMs = now(),
                    events = goal.events + GoalEvent(now(), DecisionAction.Failed, "规划失败")))
                return
            }
            goal = goal.copy(steps = steps, status = GoalStatus.Active, updatedAtMs = now())
            save(goal)
        }

        val decision = decider.decide(goal)

        when (decision.action) {
            DecisionAction.RunStep -> {
                val step = goal.currentStep ?: return
                val result = executeStep(goal, step)
                val outcome = if (result.phase == ChildPhase.Completed) "Done" else "Failed"
                val updatedStep = step.copy(result = result.text, outcome = outcome, attempts = step.attempts + 1)
                val updatedSteps = goal.steps.toMutableList().also { it[goal.cursor] = updatedStep }
                val newFailures = if (outcome == "Failed") goal.consecutiveFailures + 1 else 0
                val newCursor = if (outcome == "Done") goal.cursor + 1 else goal.cursor
                goal = goal.copy(steps = updatedSteps, cursor = newCursor, consecutiveFailures = newFailures,
                    round = goal.round + 1, updatedAtMs = now(),
                    events = (goal.events + GoalEvent(now(), DecisionAction.RunStep, "${step.description} → $outcome"))
                        .takeLast(Goal.MAX_EVENTS))
                if (newCursor >= goal.steps.size) goal = goal.copy(status = GoalStatus.Done, updatedAtMs = now())
                save(goal)
            }
            DecisionAction.Replan -> {
                val steps = planner.plan(goal)
                goal = goal.copy(
                    steps = if (steps.isEmpty()) goal.steps else steps,
                    cursor = 0,
                    status = if (steps.isEmpty()) GoalStatus.Waiting else GoalStatus.Active,
                    updatedAtMs = now(),
                    events = (goal.events + GoalEvent(now(), DecisionAction.Replan, decision.reason)).takeLast(Goal.MAX_EVENTS),
                )
                save(goal)
            }
            DecisionAction.Wait -> {
                goal = goal.copy(status = GoalStatus.Waiting, updatedAtMs = now(),
                    events = (goal.events + GoalEvent(now(), DecisionAction.Wait, decision.reason)).takeLast(Goal.MAX_EVENTS))
                save(goal)
            }
            DecisionAction.Done -> {
                goal = goal.copy(status = GoalStatus.Done, updatedAtMs = now(),
                    events = (goal.events + GoalEvent(now(), DecisionAction.Done, decision.reason)).takeLast(Goal.MAX_EVENTS))
                save(goal)
            }
            DecisionAction.Failed -> {
                goal = goal.copy(status = GoalStatus.Failed, updatedAtMs = now(),
                    events = (goal.events + GoalEvent(now(), DecisionAction.Failed, decision.reason)).takeLast(Goal.MAX_EVENTS))
                save(goal)
            }
        }
    }

    private suspend fun executeStep(goal: Goal, step: GoalStep): ChildResult =
        spawn.spawn(RunSpec(
            id = RunId("goal-${goal.id}-${goal.cursor}-${goal.round}"),
            goal = step.description,
            threadId = goal.threadId,
            provenance = RunProvenance(origin = RunOrigin.Child),
            stepBudget = 15,
        ), null)

    private fun save(goal: Goal) { store.upsert(goal) }
}
