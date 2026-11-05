package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 来源、政策与执行循环的装配参数;App 只提供真实现,Runtime 负责恢复和顺序。 */
class SuperagentRuntime(
    private val engine: Engine,
    private val runner: Runner,
    private val policy: suspend () -> Policy,
    private val sources: List<Source> = emptyList(),
    private val effects: EffectQueue? = null,
    private val outbox: SourceOutbox? = null,
    private val onEvalOutcomes: (List<EvalOutcome>) -> Unit = {},
    private val onTriggerStored: suspend (Trigger) -> Unit = {},
) {
    private var loop: Job? = null
    private var workerScope: CoroutineScope? = null
    private val executionMutex = Mutex()
    private val workers = ConcurrentHashMap.newKeySet<Job>()

    /** App 启动时只调一次:副作用恢复 + 崩溃时的 Running Assignment 换新。 */
    suspend fun recover(): List<Assignment> {
        val assignments = engine.snapshot().assignments.associateBy { it.id }
        effects?.recover { record ->
            val assignmentId = record.assignmentId.ifBlank { record.id.removePrefix("assignment:") }
            val assignment = assignments[assignmentId]
            // 已开跑的执行可能有外部结果;崩溃后让看门狗另派，不重放这条旧 Assignment。
            assignment?.status == AssignmentStatus.Pending
        }
        return engine.recoverAbandoned()
    }

    /** 拉来源、处理因事件到期被唤醒的输入;来源 poll 异常不影响其他来源。 */
    suspend fun pollOnce(): List<Engine.ReceiveResult> {
        val results = mutableListOf<Engine.ReceiveResult>()
        val snapshot = engine.snapshot()
        for (source in sources) {
            try {
                val ready = source.poll(snapshot)
                var remaining = ready
                try {
                    for (trigger in ready) {
                        val result = engine.receive(trigger)
                        remaining = remaining - trigger
                        if (result == Engine.ReceiveResult.Stored) onTriggerStored(trigger)
                        results += result
                    }
                } catch (e: Exception) {
                    source.rollback(remaining)
                    throw e
                }
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
        val scope = workerScope
        if (scope == null) {
            assignmentIds.forEach { runner.runAssignment(it) }
            return
        }
        assignmentIds.forEach { assignmentId ->
            val job = scope.launch {
                try {
                    executionMutex.withLock { runner.runAssignment(assignmentId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 执行异常由 Runner 写成 Failed 报告;装配层无法介入时不能拖垮守护循环。
                }
            }
            workers += job
            job.invokeOnCompletion { workers -= job }
        }
    }

    fun start(scope: CoroutineScope, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        check(loop == null) { "runtime already started" }
        workerScope = scope
        loop = scope.launch {
            // 崩溃留下的 Running Assignment 先换新;新派出的任务立刻接上,不靠下一轮轮询。
            try {
                dispatch(recover().map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 恢复失败留给下一轮;Engine 里的 Running 会在 watchdog 超时后换新。
            }
            while (isActive) {
                try {
                    pollOnce()
                    val outcomes = evaluateOnce()
                    if (outcomes.isNotEmpty()) onEvalOutcomes(outcomes)
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
        workers.forEach { it.cancel() }
        workers.clear()
        workerScope = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 15_000L
    }
}
