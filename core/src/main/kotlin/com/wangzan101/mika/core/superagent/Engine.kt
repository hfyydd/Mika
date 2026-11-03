package com.wangzan101.mika.core.superagent

/**
 * 领域规则的唯一写者:Trigger 入库、订阅直通、门禁、批量 Eval、Ticket 生命周期、Assignment 与看门狗。
 * 每个命令整体改状态再落盘;外部副作用(执行、回复)经端口在调用方执行。
 * 规则按 DSH 领域模型收口:看到消息不等于可以下令;订阅直通不带授权;范围更宽的工作开新 Ticket。
 */
class Engine(
    private val store: SuperagentStore,
    private val router: Router,
    private val executor: SuperExecutor? = null,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().take(12) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_TRIGGERS = 500
        const val MAX_OBSERVATIONS = 200
        /** 看门狗:Assignment 跑了这么久还没报告,按中断处理,重新派一个(Executor 自查状态再续)。 */
        const val ASSIGNMENT_TIMEOUT_MS = 30 * 60_000L
    }

    sealed interface ReceiveResult {
        data object Stored : ReceiveResult
        data object Duplicate : ReceiveResult
    }

    /** 供 Runner 与诊断读当前快照;Engine 仍是唯一写者。 */
    fun snapshot(): SuperagentState = store.load()

    /** 来源交来一个事件:幂等入库 → 订阅直通(不带授权)→ 该进 Eval 的排队。 */
    fun receive(trigger: Trigger): ReceiveResult {
        val state = store.load()
        if (state.triggers.any { it.source == trigger.source && it.externalId == trigger.externalId })
            return ReceiveResult.Duplicate

        val tickets = state.tickets.toMutableList()
        var queued = true
        val fanoutTicketIds = mutableListOf<String>()
        val contextTicketIds = mutableListOf<String>()

        for ((index, ticket) in tickets.withIndex()) {
            if (ticket.threadKey != trigger.threadKey) continue
            // 人的消息落在自己的线程上:被停止过的照旧收着;没被停止的已结束 Ticket 立即恢复,不进 Eval。
            if (trigger.isPerson) {
                queued = false
                fanoutTicketIds += ticket.id
                val stopped = ticket.ended?.by == "person"
                val revived = if (!stopped && !ticket.isOpen) ticket.copy(status = TicketStatus.Open, ended = null) else ticket
                tickets[index] = revived.copy(
                    inputs = revived.inputs + TicketInput(newId(), revived.id, trigger.id, trigger.atMs),
                    updatedAtMs = now(),
                )
            } else if (ticket.isOpen) {
                contextTicketIds += ticket.id
                tickets[index] = ticket.copy(
                    inputs = ticket.inputs + TicketInput(newId(), ticket.id, trigger.id, trigger.atMs),
                    updatedAtMs = now(),
                )
            }
        }

        // 机器人和没被任何 Ticket 认领的人的消息进 Eval;被 Ticket 认领的人的消息不进。
        val threads = state.threads.toMutableList()
        val threadIndex = threads.indexOfFirst { it.key == trigger.threadKey }
        val thread = if (threadIndex >= 0) threads[threadIndex] else Thread(key = trigger.threadKey)
        val newThread = if (queued) thread.copy(queuedTriggerIds = thread.queuedTriggerIds + trigger.id) else thread
        if (threadIndex >= 0) threads[threadIndex] = newThread else threads.add(newThread)

        val disposition = TriggerDisposition(
            triggerId = trigger.id,
            fanout = fanoutTicketIds.distinct(),
            context = contextTicketIds.distinct(),
            requeued = queued,
            atMs = trigger.atMs,
        )
        store.save(state.copy(
            triggers = (state.triggers + trigger).takeLast(MAX_TRIGGERS),
            threads = threads,
            tickets = tickets,
            dispositions = state.dispositions + (trigger.id to disposition),
            readPositions = state.readPositions + (trigger.source.instance to trigger.externalId),
        ))
        return ReceiveResult.Stored
    }

    /** 把所有线程上排队的 Trigger 各跑一轮 Eval(每线程一批、串行);Router 出错时该批原样保留,下次再试。 */
    suspend fun evaluateDue(policy: Policy): List<EvalOutcome> {
        val state = store.load()
        val outcomes = mutableListOf<EvalOutcome>()
        for (thread in state.threads.toList()) {
            val queuedIds = thread.queuedTriggerIds
            if (queuedIds.isEmpty()) continue
            val batch = queuedIds.mapNotNull { id -> state.triggers.firstOrNull { it.id == id } }
            if (batch.isEmpty()) {
                store.save(store.load().let { s ->
                    s.copy(threads = s.threads.map { if (it.key == thread.key) it.copy(queuedTriggerIds = emptyList()) else it })
                })
                continue
            }

            // 门禁:只看名单、是否点名、线程上有没有 Ticket;不问模型。
            val tickets = store.load().tickets.filter { it.threadKey == thread.key }
            val gated = batch.map { it to gate(it, thread.key, policy, tickets) }
            val admitted = gated.filter { it.second.strength != Strength.None }
            val rejected = gated.filter { it.second.strength == Strength.None }
            val evalId = newId()
            stampEvalBatch(admitted, evalId)
            recordRejected(rejected, outcomes)

            val decisions = try {
                router.assess(admitted.map { it.first }, threadSummary(thread.key), tickets, policy)
            } catch (_: Exception) {
                // Router 不可用:整批留在队列里;门禁拒绝已先落账。
                continue
            }

            decisions.zip(admitted).forEach { (decision, pair) ->
                val (trigger, gateResult) = pair
                outcomes += apply(store, decision, trigger, gateResult, policy)
            }
            store.save(store.load().let { s ->
                s.copy(threads = s.threads.map { if (it.key == thread.key) it.copy(queuedTriggerIds = emptyList()) else it })
            })
        }
        return outcomes
    }

    private fun gate(trigger: Trigger, threadKey: String, policy: Policy, tickets: List<Ticket>): GateResult {
        val allowed = policy.allows(trigger.source.instance, trigger.actor)
        val threadHasTickets = tickets.isNotEmpty()
        return when {
            allowed && trigger.addressed -> GateResult(Strength.Explicit, "addressed")
            allowed && threadHasTickets -> GateResult(Strength.Explicit, "followup")
            allowed -> GateResult(Strength.Inferred, "admission")
            trigger.addressed && trigger.isPerson -> GateResult(Strength.Inferred, "addressed")
            trigger.addressed -> GateResult(Strength.Inferred, "bot")
            else -> GateResult(Strength.None, "not_admitted")
        }
    }

    private fun recordRejected(rejected: List<Pair<Trigger, GateResult>>, outcomes: MutableList<EvalOutcome>) {
        if (rejected.isEmpty()) return
        val state = store.load()
        store.save(state.copy(
            decisions = state.decisions + rejected.associate { (t, g) ->
                t.id to Decision.Ignore(t.id, "not_admitted:${g.basis}")
            },
            dispositions = state.dispositions + rejected.associate { (t, g) ->
                val base = state.dispositions[t.id] ?: TriggerDisposition(triggerId = t.id, atMs = t.atMs)
                t.id to base.copy(
                    filtered = "not_admitted:${g.basis}",
                    decisionId = t.id,
                    requeued = false,
                )
            },
        ))
        rejected.forEach { (t, _) -> outcomes += EvalOutcome.NotAdmitted(t.id, "not admitted") }
    }

    /** Router 是外部副作用;批次编号先落盘,失败重试时能看到这条 Trigger 参加过哪次 Eval。 */
    private fun stampEvalBatch(admitted: List<Pair<Trigger, GateResult>>, evalId: String) {
        if (admitted.isEmpty()) return
        val state = store.load()
        store.save(state.copy(dispositions = state.dispositions + admitted.associate { (trigger, _) ->
            val base = state.dispositions[trigger.id] ?: TriggerDisposition(triggerId = trigger.id, atMs = trigger.atMs)
            trigger.id to base.copy(evalId = evalId, requeued = true)
        }))
    }

    private fun threadSummary(threadKey: String): String {
        val state = store.load()
        val recent = state.triggers.filter { it.threadKey == threadKey }.takeLast(5)
        return recent.joinToString(" | ") { "${it.actor}: ${it.text.take(80)}" }
    }

    /** 应用一个判决:accept 开 Ticket(含 Mandate 与首个 Assignment),attach/stop/ignore 各自落账。 */
    private fun apply(
        current: SuperagentStore, decision: Decision, trigger: Trigger, gateResult: GateResult, policy: Policy,
    ): EvalOutcome {
        val state = current.load()
        when (decision) {
            is Decision.Accept -> {
                val recorded = decisionState(state, trigger, decision)
                if (policy.acceptMode == AcceptMode.Observe) {
                    current.save(recorded.copy(
                        observations = (state.observations + "would accept ${trigger.id}: ${decision.title}").takeLast(MAX_OBSERVATIONS),
                    ))
                    return EvalOutcome.WouldAccept(trigger.id, decision.reason)
                }
                if (policy.acceptMode == AcceptMode.Off && gateResult.strength == Strength.Inferred) {
                    val rejected = decisionState(state, trigger, Decision.Ignore(trigger.id, "not_admitted:mode_off")) {
                        it.copy(filtered = "mode_off")
                    }
                    current.save(rejected)
                    return EvalOutcome.NotAdmitted(trigger.id, "accept mode is off")
                }
                val mandate = Mandate(
                    policyVersion = policy.version, sourceInstance = trigger.source.instance, scope = decision.scope, strength = gateResult.strength,
                    basis = gateResult.basis, actor = trigger.actor, matchedRule = decision.reason, createdAtMs = now(),
                )
                val ticket = Ticket(
                    id = newId(), threadKey = trigger.threadKey, title = decision.title, instruction = "trigger:${trigger.id}",
                    mandate = mandate, triggerIds = listOf(trigger.id), createdAtMs = now(), updatedAtMs = now(),
                )
                val assignment = Assignment(id = newId(), ticketId = ticket.id)
                current.save(recorded.copy(
                    tickets = state.tickets + ticket,
                    assignments = state.assignments + assignment,
                    threads = state.threads.map { if (it.key == ticket.threadKey) it.copy(subscriptions = it.subscriptions + ticket.id) else it },
                ))
                return EvalOutcome.TicketCreated(ticket, assignment)
            }
            is Decision.Attach -> {
                val ticket = state.tickets.firstOrNull { it.id == decision.ticketId } ?: return EvalOutcome.Ignored(
                    Decision.Ignore(trigger.id, "attach target missing").also { missing ->
                        current.save(decisionState(state, trigger, missing) { it.copy(filtered = "attach_target_missing") })
                    }
                )
                val withInput = ticket.copy(
                    inputs = ticket.inputs + TicketInput(newId(), ticket.id, trigger.id, trigger.atMs),
                    status = TicketStatus.Open, ended = null, updatedAtMs = now(),
                )
                val recorded = decisionState(state, trigger, decision) { disposition ->
                    disposition.copy(context = (disposition.context + ticket.id).distinct())
                }
                current.save(recorded.copy(
                    tickets = state.tickets.map { if (it.id == ticket.id) withInput else it },
                ))
                return EvalOutcome.Attached(withInput)
            }
            is Decision.Stop -> {
                val ticket = state.tickets.firstOrNull { it.id == decision.ticketId }
                    ?: return EvalOutcome.Ignored(Decision.Ignore(trigger.id, "stop target missing").also { missing ->
                        current.save(decisionState(state, trigger, missing) { it.copy(filtered = "stop_target_missing") })
                    })
                val ended = ticket.copy(
                    status = TicketStatus.Ended,
                    ended = TicketEnd(now(), "person", decision.reason.ifBlank { "stopped" }, "stopped"),
                    updatedAtMs = now(),
                )
                current.save(decisionState(state, trigger, decision).copy(
                    tickets = state.tickets.map { if (it.id == ticket.id) ended else it },
                ))
                return EvalOutcome.TicketStopped(ended)
            }
            is Decision.Ignore -> {
                current.save(decisionState(state, trigger, decision))
                return EvalOutcome.Ignored(decision)
            }
        }
    }

    /** 判决与 disposition 必须同快照落盘,保证账本读到的是同一次状态迁移。 */
    private fun decisionState(
        state: SuperagentState,
        trigger: Trigger,
        decision: Decision,
        transform: (TriggerDisposition) -> TriggerDisposition = { it },
    ): SuperagentState {
        val base = state.dispositions[trigger.id] ?: TriggerDisposition(triggerId = trigger.id, atMs = trigger.atMs)
        val disposition = transform(
            base.copy(decisionId = trigger.id, requeued = false)
        )
        return state.copy(
            decisions = state.decisions + (trigger.id to decision),
            dispositions = state.dispositions + (trigger.id to disposition),
        )
    }

    /** 工人交回一条消息;同一 Ticket 只记一次,保留第一条原因作为审计依据。 */
    fun handback(triggerId: String, ticketId: String, reason: String): Boolean {
        val state = store.load()
        val disposition = state.dispositions[triggerId] ?: return false
        if (disposition.handbacks.any { it.ticketId == ticketId }) return false
        val updated = disposition.copy(handbacks = disposition.handbacks + Handback(ticketId, reason, now()))
        store.save(state.copy(dispositions = state.dispositions + (triggerId to updated)))
        return true
    }

    /** Executor 确认读走一条直通消息;重复 ack 幂等。 */
    fun ack(triggerId: String, ticketId: String): Boolean {
        val state = store.load()
        val disposition = state.dispositions[triggerId] ?: return false
        if (ticketId in disposition.acked) return false
        val updated = disposition.copy(acked = disposition.acked + ticketId)
        store.save(state.copy(dispositions = state.dispositions + (triggerId to updated)))
        return true
    }

    /** Executor 开跑;只有 Pending 能转 Running,幂等。 */
    fun assignmentStarted(assignmentId: String) {
        val state = store.load()
        store.save(state.copy(assignments = state.assignments.map {
            if (it.id == assignmentId && it.status == AssignmentStatus.Pending)
                it.copy(status = AssignmentStatus.Running, startedAtMs = now())
            else it
        }))
    }

    /** Executor 的报告;question 非空 = 在等人,其后的人的消息恢复它,机器人消息不打扰。 */
    fun report(assignmentId: String, report: ExecutorReport) {
        val state = store.load()
        val assignment = state.assignments.firstOrNull { it.id == assignmentId } ?: return
        store.save(state.copy(assignments = state.assignments.map {
            if (it.id == assignmentId) it.copy(
                status = AssignmentStatus.Ended, endedAtMs = now(),
                result = report.result, question = report.question, outcome = report.outcome,
            ) else it
        }))
        if (report.outcome == "done") {
            store.save(store.load().let { s -> s.copy(tickets = s.tickets.map {
                if (it.id == assignment.ticketId && it.isOpen) it.copy(
                    status = TicketStatus.Ended,
                    ended = TicketEnd(now(), "executor", report.result.take(200), "done"),
                    updatedAtMs = now(),
                ) else it
            })})
        }
    }

    /** 看门狗:跑超时没报告的 Assignment 记为中断,另派一个新的(Executor 靠自查恢复,不重放外部动作的凭据)。 */
    fun watchdog(): List<Assignment> {
        val state = store.load()
        val stale = state.assignments.filter {
            it.status == AssignmentStatus.Running && it.startedAtMs != null && now() - it.startedAtMs!! > ASSIGNMENT_TIMEOUT_MS
        }
        if (stale.isEmpty()) return emptyList()
        val retried = stale.map { Assignment(id = newId(), ticketId = it.ticketId) }
        store.save(state.copy(assignments = state.assignments.map { a ->
            if (stale.any { it.id == a.id }) a.copy(status = AssignmentStatus.Ended, endedAtMs = now(), outcome = "interrupted")
            else a
        } + retried))
        return retried
    }

    /** Policy 变更:重验每个开放 Ticket 的 Mandate,不再成立的撤销(交给人的停止去收尾,不删进行中的工作)。 */
    fun policyChanged(next: Policy) {
        val state = store.load()
        store.save(state.copy(tickets = state.tickets.map { ticket ->
            val holds = when (ticket.mandate.basis) {
                // 名单里没配这个来源时视为开放;配了就按名单重验,发起人不再被允许即撤销。
                else -> next.allowedActors[ticket.mandate.sourceInstance]?.let { it.contains(ticket.mandate.actor) } ?: true
            }
            if (ticket.isOpen && !holds) ticket.copy(
                status = TicketStatus.Ended,
                ended = TicketEnd(now(), "policy", "mandate no longer holds under policy ${next.version}", "revoked"),
                updatedAtMs = now(),
            ) else ticket
        }))
    }

    /** 人的停止:撤回 Mandate;这种结束不因后续消息恢复。 */
    fun stopTicket(ticketId: String, why: String) {
        val state = store.load()
        store.save(state.copy(tickets = state.tickets.map {
            if (it.id == ticketId && it.isOpen) it.copy(
                status = TicketStatus.Ended,
                ended = TicketEnd(now(), "person", why, "stopped"),
                updatedAtMs = now(),
            ) else it
        }))
    }
}
