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
    private val evalDeadlineMs: Long = EVAL_DEADLINE_MS,
) {
    companion object {
        const val MAX_TRIGGERS = 500
        const val MAX_OBSERVATIONS = 200
        const val EVAL_DEADLINE_MS = 5 * 60_000L
        const val MAX_EVAL_REQUEUES = 2
        /** 看门狗:Assignment 跑了这么久还没报告,按中断处理,重新派一个(Executor 自查状态再续)。 */
        const val ASSIGNMENT_TIMEOUT_MS = 30 * 60_000L
    }

    sealed interface ReceiveResult {
        data object Stored : ReceiveResult
        data object Duplicate : ReceiveResult
    }

    private data class EvalSchedule(val state: SuperagentState, val runnableIds: List<String>)

    /** 供 Runner 与诊断读当前快照;Engine 仍是唯一写者。 */
    fun snapshot(): SuperagentState = store.load()

    /** 来源交来一个事件:幂等入库 → 订阅直通(不带授权)→ 该进 Eval 的排队。 */
    fun receive(trigger: Trigger): ReceiveResult {
        val state = store.load()
        val externalKey = "${trigger.source.kind}:${trigger.source.instance}:${trigger.externalId}"
        if (state.triggers.any { it.source == trigger.source && it.externalId == trigger.externalId } ||
            externalKey in state.seenExternalIds
        )
            return ReceiveResult.Duplicate

        val ownerIds = followupOwners(trigger, state.tickets, state.dispositions).toSet()
        val tickets = state.tickets.toMutableList()
        var queued = !trigger.isPerson || ownerIds.isEmpty()
        val fanoutTicketIds = mutableListOf<String>()
        val contextTicketIds = mutableListOf<String>()

        for ((index, ticket) in tickets.withIndex()) {
            if (trigger.isPerson) {
                if (ticket.id !in ownerIds) continue
                val stopped = ticket.ended?.by == "person"
                val revived = if (!stopped && !ticket.isOpen) ticket.copy(status = TicketStatus.Open, ended = null) else ticket
                tickets[index] = revived.copy(
                    inputs = revived.inputs + TicketInput(newId(), revived.id, trigger.id, trigger.atMs),
                    updatedAtMs = now(),
                )
                fanoutTicketIds += ticket.id
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

        val admittedAtMs = now()
        val disposition = TriggerDisposition(
            triggerId = trigger.id,
            fanout = fanoutTicketIds.distinct(),
            context = contextTicketIds.distinct(),
            requeued = queued,
            atMs = trigger.atMs,
            admittedAtMs = admittedAtMs.takeIf { queued },
            evalDeadlineAtMs = (admittedAtMs + evalDeadlineMs).takeIf { queued },
        )
        store.save(state.copy(
            triggers = (state.triggers + trigger).takeLast(MAX_TRIGGERS),
            threads = threads,
            tickets = tickets,
            dispositions = state.dispositions + (trigger.id to disposition),
            readPositions = state.readPositions + ("${trigger.source.kind}:${trigger.source.instance}" to trigger.externalId),
            seenExternalIds = state.seenExternalIds + (externalKey to trigger.id),
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
            val schedule = manageEvalSchedule(state, thread.key, queuedIds)
            if (schedule.state != state) store.save(schedule.state)
            val batch = schedule.runnableIds.mapNotNull { id -> state.triggers.firstOrNull { it.id == id } }
            if (batch.isEmpty()) {
                store.save(store.load().let { s ->
                    s.copy(threads = s.threads.map { if (it.key == thread.key) it.copy(queuedTriggerIds = emptyList()) else it })
                })
                continue
            }

            // 门禁:只看名单、是否点名、线程上有没有 Ticket;不问模型。
            val tickets = schedule.state.tickets.filter { it.threadKey == thread.key }
            val gated = batch.map { it to gate(it, thread.key, policy, tickets) }
            val admitted = gated.filter { it.second.strength != Strength.None }
            val rejected = gated.filter { it.second.strength == Strength.None }
            val evalId = newId()
            stampEvalBatch(admitted, evalId)
            recordRejected(rejected, outcomes)
            val seed = batchSeed(admitted)
            val started = startedTicketTriggers(tickets)

            val decisions = try {
                router.assess(admitted.map { it.first }, threadSummary(thread.key), tickets, policy)
            } catch (_: Exception) {
                // Router 不可用:整批留在队列里;门禁拒绝已先落账。
                continue
            }

            decisions.zip(admitted).forEach { (routerDecision, pair) ->
                val (trigger, gateResult) = pair
                val decision = when {
                    trigger.id in started -> Decision.Ignore(trigger.id, "startedTicket")
                    routerDecision is Decision.Accept && trigger.id != seed?.id -> Decision.Ignore(trigger.id, "secondary_in_batch")
                    else -> routerDecision
                }
                outcomes += apply(store, decision, trigger, gateResult, policy)
            }
            val incomplete = decisions.size != admitted.size ||
                decisions.any { it is Decision.Ignore && it.reason == "router output missing entry" }
            if (incomplete) continue
            store.save(store.load().let { s ->
                s.copy(threads = s.threads.map { if (it.key == thread.key) it.copy(queuedTriggerIds = emptyList()) else it })
            })
        }
        return outcomes
    }

    private fun gate(trigger: Trigger, threadKey: String, policy: Policy, tickets: List<Ticket>): GateResult {
        val allowed = policy.allows(trigger.source.instance, trigger.actor)
        val threadHasTickets = tickets.isNotEmpty()
        if (!trigger.isPerson) {
            // 机器/CI 即使在名单里也不获得 explicit/followup;未名单机器不进推断。
            return when {
                allowed && trigger.addressed -> GateResult(Strength.Inferred, "bot_allowlisted")
                allowed -> GateResult(Strength.None, "bot_requires_address")
                else -> GateResult(Strength.None, "bot_not_admitted")
            }
        }

        val identity = when {
            trigger.actor.isBlank() -> Identity.Unknown
            allowed -> Identity.Allowed
            else -> Identity.Unlisted
        }
        val targeting = when {
            trigger.addressed -> Targeting.Address
            threadHasTickets -> Targeting.Followup
            else -> Targeting.Unaddressed
        }
        val table = policy.admissionTable(trigger.source.instance)
        return when (table.cell(identity, targeting)) {
            AdmissionCell.Explicit -> GateResult(Strength.Explicit, "${identity.name.lowercase()}_${targeting.name.lowercase()}")
            AdmissionCell.Inferred -> GateResult(Strength.Inferred, "${identity.name.lowercase()}_${targeting.name.lowercase()}")
            AdmissionCell.Ignore -> GateResult(Strength.None, "table_${identity.name.lowercase()}_${targeting.name.lowercase()}")
        }
    }

    /** 超时先重排两次;再次超时标为 unavailable。暂停不计时,Router 异常不属于候选超时。 */
    private fun manageEvalSchedule(state: SuperagentState, threadKey: String, queuedIds: List<String>): EvalSchedule {
        val runnableIds = mutableListOf<String>()
        val retainedIds = mutableListOf<String>()
        val dispositions = state.dispositions.toMutableMap()
        for (id in queuedIds) {
            val trigger = state.triggers.firstOrNull { it.id == id }
            val disposition = dispositions[id]
            if (trigger == null || disposition == null || disposition.unavailableAtMs != null) continue
            val paused = disposition.evalPausedUntilMs
            if (paused != null && now() < paused) {
                retainedIds += id
                continue
            }
            val deadline = disposition.evalDeadlineAtMs
            if (deadline == null || now() <= deadline) {
                runnableIds += id
                retainedIds += id
                continue
            }
            if (disposition.evalRequeues >= MAX_EVAL_REQUEUES) {
                dispositions[id] = disposition.copy(unavailableAtMs = now(), requeued = false)
                continue
            }
            dispositions[id] = disposition.copy(
                admittedAtMs = now(),
                evalDeadlineAtMs = now() + evalDeadlineMs,
                evalPausedUntilMs = null,
                evalRequeues = disposition.evalRequeues + 1,
                requeued = true,
            )
            retainedIds += id
            runnableIds += id
        }
        val nextState = state.copy(
            threads = state.threads.map { if (it.key == threadKey) it.copy(queuedTriggerIds = retainedIds) else it },
            dispositions = dispositions,
        )
        return EvalSchedule(nextState, runnableIds)
    }

    /** 暂停期间冻结候选;deadline 按暂停长度顺移。 */
    fun pauseEval(triggerId: String, untilMs: Long): Boolean {
        val state = store.load()
        val disposition = state.dispositions[triggerId] ?: return false
        if (disposition.unavailableAtMs != null || untilMs <= now()) return false
        val extension = untilMs - now()
        val updated = disposition.copy(
            evalDeadlineAtMs = (disposition.evalDeadlineAtMs ?: now()) + extension,
            evalPausedUntilMs = untilMs,
        )
        store.save(state.copy(dispositions = state.dispositions + (triggerId to updated)))
        return true
    }

    fun resumeEval(triggerId: String): Boolean {
        val state = store.load()
        val disposition = state.dispositions[triggerId] ?: return false
        if (disposition.evalPausedUntilMs == null || disposition.unavailableAtMs != null) return false
        store.save(state.copy(dispositions = state.dispositions + (triggerId to disposition.copy(evalPausedUntilMs = null))))
        return true
    }

    /** 面板确认一条不可用候选;记录保留,不再参与 Eval。 */
    fun dismissUnavailable(triggerId: String): Boolean {
        val state = store.load()
        val disposition = state.dispositions[triggerId] ?: return false
        if (disposition.unavailableAtMs == null) return false
        store.save(state.copy(
            threads = state.threads.map { it.copy(queuedTriggerIds = it.queuedTriggerIds - triggerId) },
            dispositions = state.dispositions + (triggerId to disposition.copy(requeued = false)),
        ))
        return true
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
                if (gateResult.strength == Strength.Inferred) {
                    val missing = when {
                        decision.matchedRule.isBlank() -> "inferred_missing_rule"
                        decision.direction.isBlank() -> "inferred_missing_direction"
                        decision.motivationRef.isBlank() -> "inferred_missing_motivation"
                        !decision.hostVerified -> "inferred_missing_host_check"
                        else -> ""
                    }
                    if (missing.isNotBlank()) {
                        current.save(decisionState(state, trigger, Decision.Ignore(trigger.id, missing)) {
                            it.copy(filtered = missing)
                        })
                        return EvalOutcome.NotAdmitted(trigger.id, missing)
                    }
                }
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
                val identity = when {
                    trigger.actor.isBlank() -> Identity.Unknown
                    policy.allows(trigger.source.instance, trigger.actor) -> Identity.Allowed
                    else -> Identity.Unlisted
                }
                val targeting = if (gateResult.basis.endsWith("_address")) Targeting.Address else Targeting.Followup
                if (policy.admissionTable(trigger.source.instance, decision.scope).cell(identity, targeting) == AdmissionCell.Ignore) {
                    val rejected = decisionState(state, trigger, Decision.Ignore(trigger.id, "not_admitted:scope")) {
                        it.copy(filtered = "scope_not_admitted")
                    }
                    current.save(rejected)
                    return EvalOutcome.NotAdmitted(trigger.id, "scope not admitted")
                }
                val mandate = Mandate(
                    policyVersion = policy.version, sourceInstance = trigger.source.instance, scope = decision.scope, strength = gateResult.strength,
                    basis = gateResult.basis, actor = trigger.actor,
                    matchedRule = if (gateResult.strength == Strength.Inferred) decision.matchedRule else decision.reason,
                    createdAtMs = now(),
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
                val ticket = routedTicket(state, trigger, decision.ticketId, policy, "attach")
                    ?: return EvalOutcome.Ignored(
                        Decision.Ignore(trigger.id, "attach target unavailable").also { missing ->
                            current.save(decisionState(state, trigger, missing) { it.copy(filtered = "attach_target_unavailable") })
                        }
                    )
                val withInput = ticket.copy(
                    inputs = ticket.inputs + TicketInput(newId(), ticket.id, trigger.id, trigger.atMs),
                    updatedAtMs = now(),
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
                val ticket = routedTicket(state, trigger, decision.ticketId, policy, "stop")
                    ?: return EvalOutcome.Ignored(Decision.Ignore(trigger.id, "stop target unavailable").also { missing ->
                        current.save(decisionState(state, trigger, missing) { it.copy(filtered = "stop_target_unavailable") })
                    })
                val ended = ticket.copy(
                    status = TicketStatus.Ended,
                    ended = TicketEnd(now(), "person", decision.reason.ifBlank { "stopped" }, "stopped"),
                    updatedAtMs = now(),
                )
                val stoppedState = decisionState(state, trigger, decision).copy(
                    tickets = state.tickets.map { if (it.id == ticket.id) ended else it },
                )
                current.save(releaseUnreadFollowups(stoppedState, ticket.id))
                return EvalOutcome.TicketStopped(ended)
            }
            is Decision.Ignore -> {
                current.save(decisionState(state, trigger, decision))
                return EvalOutcome.Ignored(decision)
            }
        }
    }

    /** Router 输出只是候选;attach/stop 必须由同线程、名单内的人指向开放票。 */
    private fun routedTicket(
        state: SuperagentState,
        trigger: Trigger,
        ticketId: String,
        policy: Policy,
        action: String,
    ): Ticket? {
        if (!trigger.isPerson || !policy.allows(trigger.source.instance, trigger.actor)) return null
        return state.tickets.firstOrNull {
            it.id == ticketId && it.threadKey == trigger.threadKey && it.isOpen &&
                it.mandate.actor == trigger.actor
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

    /** 工人交回一条消息;无人拥有时移出旧票、重排队,最终必须得到新判决。 */
    fun handback(triggerId: String, ticketId: String, reason: String): Boolean {
        val state = store.load()
        val trigger = state.triggers.firstOrNull { it.id == triggerId } ?: return false
        val disposition = state.dispositions[triggerId] ?: return false
        if (disposition.handbacks.any { it.ticketId == ticketId }) return false
        val updated = disposition.copy(
            handbacks = disposition.handbacks + Handback(ticketId, reason, now()),
        )
        var next = state.copy(
            tickets = state.tickets.map { ticket ->
                if (ticket.id == ticketId) ticket.copy(inputs = ticket.inputs.filter { it.triggerId != triggerId })
                else ticket
            },
            dispositions = state.dispositions + (triggerId to updated),
        )
        if (followupOwners(trigger, next.tickets, next.dispositions).isEmpty()) {
            val handbackDisposition = updated.copy(requeued = true)
            next = next.copy(
                threads = next.threads.map { thread ->
                    if (thread.key == trigger.threadKey && triggerId !in thread.queuedTriggerIds)
                        thread.copy(queuedTriggerIds = thread.queuedTriggerIds + triggerId)
                    else thread
                },
                dispositions = next.dispositions + (triggerId to handbackDisposition),
            )
        }
        store.save(next)
        return true
    }

    /** Executor 确认读走一条直通消息;移除未读输入,后续票结束不会再重评。 */
    fun ack(triggerId: String, ticketId: String): Boolean {
        val state = store.load()
        val disposition = state.dispositions[triggerId] ?: return false
        if (ticketId in disposition.acked) return false
        val updated = disposition.copy(acked = disposition.acked + ticketId)
        store.save(state.copy(
            tickets = state.tickets.map { ticket ->
                if (ticket.id == ticketId) ticket.copy(inputs = ticket.inputs.filter { it.triggerId != triggerId })
                else ticket
            },
            dispositions = state.dispositions + (triggerId to updated),
        ))
        return true
    }

    /** 票结束时,还没有被 ack 的 person 跟进不能困在死票里;释放回原线程 Eval。 */
    private fun releaseUnreadFollowups(state: SuperagentState, ticketId: String): SuperagentState {
        val ticket = state.tickets.firstOrNull { it.id == ticketId } ?: return state
        if (ticket.inputs.isEmpty()) return state
        val releaseIds = ticket.inputs.map { it.triggerId }.filter { id ->
            state.triggers.firstOrNull { it.id == id }?.isPerson == true
        }.toSet()
        if (releaseIds.isEmpty()) return state
        return state.copy(
            tickets = state.tickets.map {
                if (it.id == ticketId) it.copy(inputs = it.inputs.filter { input -> input.triggerId !in releaseIds }) else it
            },
            threads = state.threads.map { thread ->
                if (thread.key == ticket.threadKey)
                    thread.copy(queuedTriggerIds = (thread.queuedTriggerIds + releaseIds).distinct())
                else thread
            },
            dispositions = state.dispositions.mapValues { (id, disposition) ->
                if (id in releaseIds) disposition.copy(requeued = true) else disposition
            },
        )
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

    /** 六态报告;Progress/Waiting 保活,Ready/Blocked/Failed/Dropped 结束本 Assignment。 */
    fun report(assignmentId: String, report: ExecutorReport) {
        val state = store.load()
        val assignment = state.assignments.firstOrNull { it.id == assignmentId } ?: return
        val terminal = report.state in setOf(ReportState.Ready, ReportState.Blocked, ReportState.Failed, ReportState.Dropped)
        val alive = report.state in setOf(ReportState.Progress, ReportState.Waiting)
        val reported = assignment.copy(
            status = when {
                terminal -> AssignmentStatus.Ended
                alive && assignment.status == AssignmentStatus.Pending -> AssignmentStatus.Running
                else -> assignment.status
            },
            startedAtMs = assignment.startedAtMs ?: now().takeIf { alive || terminal },
            state = report.state,
            endedAtMs = now().takeIf { terminal },
            result = report.result,
            question = report.question,
            outcome = report.outcome,
            reports = assignment.reports + AssignmentReport(
                atMs = now(), state = report.state, result = report.result,
                question = report.question, outcome = report.outcome,
            ),
        )
        val endOutcome = when (report.state) {
            ReportState.Ready -> report.outcome.takeIf { it in setOf("done", "merged", "closed") }
            ReportState.Dropped -> "dropped"
            else -> null
        }
        val nextState = state.copy(
            assignments = state.assignments.map { if (it.id == assignmentId) reported else it },
            tickets = state.tickets.map { ticket ->
                if (ticket.id == assignment.ticketId && ticket.isOpen && endOutcome != null) ticket.copy(
                    status = TicketStatus.Ended,
                    ended = TicketEnd(now(), "executor", report.result.take(200), endOutcome),
                    updatedAtMs = now(),
                ) else ticket
            },
        )
        store.save(if (endOutcome == null) nextState else releaseUnreadFollowups(nextState, assignment.ticketId))
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
            if (stale.any { it.id == a.id }) a.copy(
                status = AssignmentStatus.Ended, state = ReportState.Failed, endedAtMs = now(), outcome = "interrupted",
            )
            else a
        } + retried))
        return retried
    }

    /** 进程重启恢复:旧进程留下的 Running 没有守护者,立刻终结并另派新 Assignment。 */
    fun recoverAbandoned(): List<Assignment> {
        val state = store.load()
        val abandoned = state.assignments.filter { it.status == AssignmentStatus.Running }
        if (abandoned.isEmpty()) return emptyList()
        val retried = abandoned.map { Assignment(id = newId(), ticketId = it.ticketId) }
        store.save(state.copy(assignments = state.assignments.map { a ->
            if (abandoned.any { it.id == a.id }) a.copy(
                status = AssignmentStatus.Ended,
                state = ReportState.Failed,
                endedAtMs = now(),
                outcome = "interrupted",
            ) else a
        } + retried))
        return retried
    }

    /** Policy 变更:重验每个开放 Ticket 的 Mandate,不再成立的撤销(交给人的停止去收尾,不删进行中的工作)。 */
    fun policyChanged(next: Policy): List<PolicyEffect> {
        val state = store.load()
        var nextState = state.copy(tickets = state.tickets.map { ticket ->
            val reason = if (ticket.isOpen) mandateRevocationReason(ticket, next) else ""
            if (reason.isNotBlank()) ticket.copy(
                status = TicketStatus.Ended,
                ended = TicketEnd(now(), "policy", reason, "revoked"),
                updatedAtMs = now(),
            ) else ticket
        })
        val revokedIds = state.tickets.filter { original ->
            original.isOpen && nextState.tickets.firstOrNull { it.id == original.id }?.isOpen == false
        }.map { it.id }
        store.save(revokedIds.fold(nextState) { current, ticketId -> releaseUnreadFollowups(current, ticketId) })
        return revokedIds.map { id -> PolicyEffect(id, mandateRevocationReason(state.tickets.first { it.id == id }, next)) }
    }

    private fun mandateRevocationReason(ticket: Ticket, next: Policy): String {
        val mandate = ticket.mandate
        if (next.acceptMode != AcceptMode.Auto) return "mode_not_auto"
        if (next.activeSources[mandate.sourceInstance] == false) return "source_inactive"
        if (next.allowedActors[mandate.sourceInstance]?.contains(mandate.actor) != true) return "actor_not_allowed"
        val enabled = next.enabledRules[mandate.sourceInstance]
        if (mandate.matchedRule.isNotBlank() && enabled != null && mandate.matchedRule !in enabled) return "rule_disabled"
        val cell = tableCellForMandate(mandate, next)
        val required = when (mandate.strength) {
            Strength.Explicit -> AdmissionCell.Explicit
            Strength.Inferred -> AdmissionCell.Inferred
            Strength.None -> AdmissionCell.Ignore
        }
        if (cellRank(cell) < cellRank(required)) return "table_weakened"
        return ""
    }

    private fun tableCellForMandate(mandate: Mandate, policy: Policy): AdmissionCell {
        val identity = when {
            mandate.actor.isBlank() -> Identity.Unknown
            policy.allows(mandate.sourceInstance, mandate.actor) -> Identity.Allowed
            else -> Identity.Unlisted
        }
        val targeting = when {
            mandate.basis.endsWith("_address") || mandate.basis == "addressed" || mandate.basis == "bot_allowlisted" -> Targeting.Address
            mandate.basis.endsWith("_followup") || mandate.basis == "followup" -> Targeting.Followup
            else -> Targeting.Unaddressed
        }
        return policy.admissionTable(mandate.sourceInstance).cell(identity, targeting)
    }

    private fun cellRank(cell: AdmissionCell) = when (cell) {
        AdmissionCell.Ignore -> 0
        AdmissionCell.Inferred -> 1
        AdmissionCell.Explicit -> 2
    }

    /** 人的停止:撤回 Mandate;这种结束不因后续消息恢复。 */
    fun stopTicket(ticketId: String, why: String) {
        val state = store.load()
        val nextState = state.copy(tickets = state.tickets.map {
            if (it.id == ticketId && it.isOpen) it.copy(
                status = TicketStatus.Ended,
                ended = TicketEnd(now(), "person", why, "stopped"),
                updatedAtMs = now(),
            ) else it
        })
        store.save(releaseUnreadFollowups(nextState, ticketId))
    }
}
