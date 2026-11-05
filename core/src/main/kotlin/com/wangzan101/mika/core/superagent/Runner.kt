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
    private val outbox: SourceOutbox? = null,
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
        var report: ExecutorReport? = null
        effects.drain { record ->
            // 队列里同一时刻只取本条;其它 id 由自己的调用方处理,这里不阻塞也不报错。
            if (record.id == "assignment:$assignmentId") runAndReport(assignmentId, ticket).let {
                report = it
                "assignment:$assignmentId"
            } else "skipped:${record.id}"
        }
        report?.let { reply(assignmentId, ticket, it) }
        // 报告没写进 Engine 时如实返回 false,由 watchdog / 下轮恢复重派,不能假装完成。
        return report != null
    }

    private suspend fun execute(assignmentId: String, ticket: Ticket): Boolean {
        runAndReport(assignmentId, ticket)
        return true
    }

    private suspend fun runAndReport(assignmentId: String, ticket: Ticket): ExecutorReport {
        engine.assignmentStarted(assignmentId)
        return try {
            val report = executor.run(ticket, threadSummary(engine, ticket.threadKey))
            engine.report(assignmentId, report)
            report
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val report = ExecutorReport(ReportState.Failed, "执行失败: ${e.message ?: e::class.simpleName}")
            engine.report(assignmentId, report)
            report
        }
    }

    /** 结果只作为副作用排队;真正发回原来的来源实例由 outbox 在安全点执行。 */
    private suspend fun reply(assignmentId: String, ticket: Ticket, report: ExecutorReport) {
        val outbox = outbox ?: return
        val state = engine.snapshot()
        val current = state.assignments.firstOrNull { it.id == assignmentId } ?: return
        // 本地 Super 卡片自己显示 Waiting/Blocked；追问经直通送回原票，不能先把它持久化成最终回答。
        val localChat = ticket.threadKey.startsWith("chat:")
        val shouldReply = when (current.state) {
            ReportState.Ready, ReportState.Blocked, ReportState.Waiting, ReportState.Failed, ReportState.Dropped -> true
            ReportState.Progress -> false
            null -> false
        } && when (current.state) {
            ReportState.Waiting, ReportState.Blocked -> !localChat
            else -> true
        }
        if (!shouldReply) return
        val text = current.question.ifBlank { current.result.ifBlank { if (current.state == ReportState.Ready) "done" else return } }
        val triggerId = ticket.triggerIds.firstOrNull() ?: return
        val trigger = state.triggers.firstOrNull { it.id == triggerId } ?: return
        outbox.enqueue(ticket, trigger, text)
    }

    private fun threadSummary(engine: Engine, threadKey: String): String {
        val state = engine.snapshot()
        return state.triggers.filter { it.threadKey == threadKey }.takeLast(5)
            .joinToString(" | ") { "${it.actor}: ${it.text.take(120)}" }
    }
}
