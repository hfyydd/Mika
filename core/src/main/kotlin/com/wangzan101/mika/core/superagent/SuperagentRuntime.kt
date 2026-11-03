package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 来源、政策与执行循环的装配参数;App 只提供真实现,Runtime 负责恢复和顺序。 */
class SuperagentRuntime(
    private val engine: Engine,
    private val runner: Runner,
    private val policy: suspend () -> Policy,
    private val sources: List<Source> = emptyList(),
    private val effects: EffectQueue? = null,
    private val outbox: SourceOutbox? = null,
) {
    private var loop: Job? = null

    /** App 启动时只调一次:副作用恢复 + 崩溃时的 Running Assignment 换新。 */
    suspend fun recover(): List<Assignment> {
        effects?.recover()
        return engine.recoverAbandoned()
    }

    /** 拉来源、处理因事件到期被唤醒的输入;来源 poll 异常不影响其他来源。 */
    suspend fun pollOnce(): List<Engine.ReceiveResult> {
        val results = mutableListOf<Engine.ReceiveResult>()
        val snapshot = engine.snapshot()
        for (source in sources) {
            try {
                source.poll(snapshot).forEach { results += engine.receive(it) }
            } catch (_: Exception) {
                // 来源断线由各自的连接器重试;快照里的 readPositions 保证重启后不重放。
            }
        }
        return results
    }

    /** Eval 一次;成功的新票交给持久效果队列。 */
    suspend fun evaluateOnce(): List<EvalOutcome> {
        val outcomes = engine.evaluateDue(policy())
        dispatch(outcomes.filterIsInstance<EvalOutcome.TicketCreated>().map { it.assignment.id })
        return outcomes
    }

    /** Engine 看门狗产生的新 Assignment 也进入同一队列。 */
    suspend fun watchdogOnce(): List<Assignment> {
        val retried = engine.watchdog()
        dispatch(retried.map { it.id })
        return retried
    }

    suspend fun dispatch(assignmentIds: List<String>) {
        if (effects == null) {
            assignmentIds.forEach { runner.runAssignment(it) }
            return
        }
        assignmentIds.forEach { runner.runAssignment(it) }
    }

    fun start(scope: CoroutineScope, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        check(loop == null) { "runtime already started" }
        loop = scope.launch {
            recover()
            while (isActive) {
                try {
                    pollOnce()
                    evaluateOnce()
                    watchdogOnce()
                    outbox?.flush()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 一个循环失败不杀守护循环;队列退避和 Engine 的不可用账本控制节奏。
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 15_000L
    }
}
