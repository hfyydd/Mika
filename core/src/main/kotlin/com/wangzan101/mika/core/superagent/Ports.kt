package com.wangzan101.mika.core.superagent

/**
 * Eval 的评估端:对一个线程上的一批 Trigger 做判决。
 * Router 只做路由判决,不执行任务、不读执行细节;一次调用判完整批(批量摊薄往返,对应 DSH 的 orchestrator_route 数组)。
 * 实现要么是 LLM([LlmRouter]),要么是测试桩。抛异常按「Router 不可用」处理,Trigger 重新排队。
 */
fun interface Router {
    /** 输入:批次 + 线程快照 + 该线程全部 Ticket(含已结束的);输出与输入同序、一一对应的判决。 */
    suspend fun assess(batch: List<Trigger>, threadSummary: String, tickets: List<Ticket>, policy: Policy): List<Decision>
}

/** Executor 的报告:结束时给结果;waiting 表示在等人(结构化字段,不从自由文本猜)。 */
data class ExecutorReport(val result: String, val question: String = "", val outcome: String = "done")

/**
 * 执行端口:Mika 本体(AgentLoop 经 TaskCenter/ChildRunHost)在这里接进来。
 * 实现方拿到 Ticket 后自己读原始 Trigger 与上下文(chain of recall:传引用,不传改写摘要),
 * 在 Mandate 的 scope 内干活,结束时经 [Engine.report] 回报;异常恢复由 Engine 的看门狗负责。
 */
fun interface SuperExecutor {
    suspend fun run(ticket: Ticket, threadSummary: String): ExecutorReport
}

/** 来源端口:一个可复用的事件流实现;kind 声明自己怎么算事件、怎么分线程。重启不重放由游标保证。 */
interface Source {
    val kind: String
    /** 拉一批新事件;返回前事件才算收到,游标由 Engine 在入库后更新。 */
    suspend fun poll(state: SuperagentState): List<Trigger>
}
