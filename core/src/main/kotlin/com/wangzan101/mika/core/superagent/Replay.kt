package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ReplayCase(
    val name: String,
    val policy: Policy,
    val triggers: List<Trigger>,
    /** triggerId → recorded Router decision;回放不访问网络。 */
    val decisions: Map<String, Decision>,
)

@Serializable
data class ReplayOutcome(
    val kind: String,
    val triggerId: String? = null,
    val ticketId: String? = null,
    val mandateStrength: String? = null,
    val mandateBasis: String? = null,
    val reason: String? = null,
)

@Serializable
data class ReplayResult(val name: String, val outcomes: List<ReplayOutcome>)

@Serializable
data class ReplayRecord(val case: ReplayCase, val expected: ReplayResult)

/**
 * 领域回放:用 JSONL 里录下的 Router 判决离线重跑 Engine,
 * 归一化结果必须和 expected 完全一致;JSON 输出保持字段顺序,供逐字节比对。
 */
object Replay {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun router(case: ReplayCase): Router = Router { batch, _, _, _ ->
        batch.map { trigger -> case.decisions.getValue(trigger.id) }
    }

    suspend fun run(case: ReplayCase): ReplayResult {
        val store = MemorySuperagentStore()
        val engine = Engine(store, router(case), newId = { "ticket-${case.name}" }, now = { 1000 })
        case.triggers.forEach { engine.receive(it) }
        val outcomes = engine.evaluateDue(case.policy).map { outcome ->
            when (outcome) {
                is EvalOutcome.TicketCreated -> ReplayOutcome(
                    kind = "TicketCreated",
                    triggerId = outcome.ticket.triggerIds.single(),
                    ticketId = outcome.ticket.id,
                    mandateStrength = outcome.ticket.mandate.strength.name,
                    mandateBasis = outcome.ticket.mandate.basis,
                )
                is EvalOutcome.TicketResumed -> ReplayOutcome("TicketResumed", ticketId = outcome.ticket.id)
                is EvalOutcome.TicketStopped -> ReplayOutcome("TicketStopped", ticketId = outcome.ticket.id)
                is EvalOutcome.Attached -> ReplayOutcome("Attached", ticketId = outcome.ticket.id)
                is EvalOutcome.Ignored -> ReplayOutcome("Ignored", triggerId = outcome.decision.triggerId, reason = outcome.decision.reason)
                is EvalOutcome.WouldAccept -> ReplayOutcome("WouldAccept", triggerId = outcome.triggerId, reason = outcome.reason)
                is EvalOutcome.NotAdmitted -> ReplayOutcome("NotAdmitted", triggerId = outcome.triggerId, reason = outcome.reason)
            }
        }
        return ReplayResult(case.name, outcomes)
    }

    fun encode(records: List<ReplayRecord>): String =
        records.joinToString("") { json.encodeToString(it) + "\n" }

    fun decode(text: String): List<ReplayRecord> =
        text.lines().filter { it.isNotBlank() }.map { json.decodeFromString<ReplayRecord>(it) }

    fun encodedResult(result: ReplayResult): String = json.encodeToString(result)
}
