package com.wangzan101.mika.core.superagent

/**
 * 执行驱动:把 Engine 派出的 Assignment 交给 [SuperExecutor](Mika 本体),报告送回 Engine。
 * Mika 是底层支撑:它拿到的是 Ticket(含 Mandate 的 scope 与原始 Trigger 引用),
 * 自己经装配层把 scope 映射成能力子集、读原始请求,跑完回报;崩溃由 Engine 的看门狗接手。
 */
class Runner(
    private val engine: Engine,
    private val executor: SuperExecutor,
    private val effects: EffectQueue? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 领取一个 Assignment 并跑完;返回 true 表示报告已记录。 */
    suspend fun runAssignment(assignmentId: String): Boolean {
        val state = engine.let { it.snapshot() }
        val assignment = state.assignments.firstOrNull { it.id == assignmentId } ?: return false
        val ticket = state.tickets.firstOrNull { it.id == assignment.ticketId } ?: return false
        if (!ticket.isOpen) return false
        if (effects == null) return execute(assignmentId, ticket)
        effects.submit("assignment:$assignmentId", EffectKind.Assignment, ticket.id, assignmentId)
        effects.drain { record ->
            if (record.id == "assignment:$assignmentId") {
                execute(assignmentId, ticket)
                "assignment:$assignmentId"
            } else {
                error("queue is blocked by ${record.id}")
            }
        }
        return true
    }

    private suspend fun execute(assignmentId: String, ticket: Ticket): Boolean {
        engine.assignmentStarted(assignmentId)
        return try {
            val report = executor.run(ticket, threadSummary(engine, ticket.threadKey))
            engine.report(assignmentId, report)
            true
        } catch (e: Exception) {
            engine.report(assignmentId, ExecutorReport(ReportState.Failed, "执行失败: ${e.message ?: e::class.simpleName}"))
            true
        }
    }

    private fun threadSummary(engine: Engine, threadKey: String): String {
        val state = engine.snapshot()
        return state.triggers.filter { it.threadKey == threadKey }.takeLast(5)
            .joinToString(" | ") { "${it.actor}: ${it.text.take(120)}" }
    }
}
