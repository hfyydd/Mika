package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable

/**
 * Super-agent 的领域模型,按 DeepSeek super-agent 设计(dsh-superagent)移植:
 * Source → Trigger → Thread → Eval → Ticket → Assignment;Mika 本体(AgentLoop、手机/网页/沙箱能力)是执行层,
 * 经 [SuperExecutor] 端口接进来,这一层不依赖任何能力包。
 *
 * 与 Mika 已有机制的对应:threadKey ↔ 对话 threadId;Mandate 的 scope ↔ RunSpec.capabilities/readOnly;
 * Assignment ↔ TaskCenter 的 Run;回复账本 ↔ Outbox。
 */

/** 来源:一条事件流。kind 是可复用的实现(定时、通知、中继渠道),instance 是一份配置。 */
@Serializable
data class SourceRef(val kind: String, val instance: String)

/** 请求强度:名单内的人点名或跟进是 explicit;其余按准入表和 accept 模式判。 */
@Serializable
enum class Strength { Explicit, Inferred, None }

/**
 * 一次不可变事件。[externalId] 是来源侧的幂等键,同一事件收两次只记一次;
 * [addressed] 表示是否点名了 agent(mention、私信、@);[isPerson] 区分人和机器人/CI。
 */
@Serializable
data class Trigger(
    val id: String,
    val source: SourceRef,
    val externalId: String,
    /** 来源定义的会话线(群、issue、对话 id)。 */
    val threadKey: String,
    val actor: String,
    val isPerson: Boolean = true,
    val addressed: Boolean = false,
    val text: String,
    val atMs: Long,
)

/**
 * 会话线:同一 threadKey 的 Trigger 按时间序排;至多一个 Eval 在跑,期间的 Trigger 并入下一批。
 * 订阅它的 Ticket 直通收后续消息,不经 Eval、不带授权。
 */
@Serializable
data class Thread(
    val key: String,
    val subscriptions: Set<String> = emptySet(),
    /** 排队等 Eval 的 Trigger;Eval 完成后清空。 */
    val queuedTriggerIds: List<String> = emptyList(),
)

/**
 * 一次接受所授予的授权,不可变:范围更宽的工作要开新 Ticket;
 * 只有人的停止撤回它,或 Policy 变更后重验不再成立时撤销。
 * [scope] 由装配层映射成 RunSpec 的能力子集与只读限制。
 */
@Serializable
data class Mandate(
    val policyVersion: String,
    /** 发起工作走的来源实例;重验时按它查名单。 */
    val sourceInstance: String,
    val scope: String,
    val strength: Strength,
    /** 准入依据:addressed / followup / admission / bot。 */
    val basis: String,
    val actor: String,
    val matchedRule: String = "",
    val createdAtMs: Long,
)

@Serializable
enum class TicketStatus { Open, Ended }

@Serializable
data class TicketEnd(val atMs: Long, val by: String, val why: String, val outcome: String)

/**
 * 被授权的工作单位。只有 Eval 能创建;生命周期 open → ended,
 * 人在它线程上的消息可以让它恢复(被人的停止结束的除外),与 Executor 实例无关。
 * [instruction] 是 Eval 接受时记下的原始请求引用,不改写成计划(Executor 自己读原 Trigger)。
 */
@Serializable
data class Ticket(
    val id: String,
    val threadKey: String,
    val title: String,
    val instruction: String,
    val mandate: Mandate,
    val triggerIds: List<String>,
    val status: TicketStatus = TicketStatus.Open,
    val ended: TicketEnd? = null,
    /** 直通送达、还没被 Executor 确认读走的后续消息。 */
    val inputs: List<TicketInput> = emptyList(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val isOpen: Boolean get() = status == TicketStatus.Open
}

/** 订阅直通送到 Ticket 的一条后续消息;不带授权,Executor 可以在 scope 内消化或交回。 */
@Serializable
data class TicketInput(val id: String, val ticketId: String, val triggerId: String, val atMs: Long)

/** 工人把一条直通消息退回:账本保留退回原因,后续归属规则用它排除这条消息。 */
@Serializable
data class Handback(val ticketId: String, val reason: String, val atMs: Long)

/**
 * 一个 Trigger 从入库到最终判决的生命周期账本。
 * [fanout] 是作为拥有者消息直通送达的 Ticket;[context] 是只读上下文送达的 Ticket;
 * [evalId] 标记它参加过的 Eval 批次;[decisionId] 指向状态里的判决账本。
 */
@Serializable
data class TriggerDisposition(
    val triggerId: String,
    val filtered: String? = null,
    val evalId: String? = null,
    val decisionId: String? = null,
    val fanout: List<String> = emptyList(),
    val context: List<String> = emptyList(),
    val requeued: Boolean = false,
    val handbacks: List<Handback> = emptyList(),
    val acked: List<String> = emptyList(),
    val atMs: Long,
    val admittedAtMs: Long? = null,
    val evalDeadlineAtMs: Long? = null,
    val evalPausedUntilMs: Long? = null,
    val evalRequeues: Int = 0,
    val unavailableAtMs: Long? = null,
)

@Serializable
enum class AssignmentStatus { Pending, Running, Ended }

/** Executor 的六态报告;等待人必须由 Waiting + question 字段算出,不从自由文本猜。 */
@Serializable
enum class ReportState { Progress, Waiting, Ready, Blocked, Failed, Dropped }

@Serializable
data class AssignmentReport(
    val atMs: Long,
    val state: ReportState,
    val result: String = "",
    val question: String = "",
    val outcome: String = "",
)

/** Ticket 与一个 Executor 实例的绑定;独立于 Ticket 的生命周期,pending → running → ended。 */
@Serializable
data class Assignment(
    val id: String,
    val ticketId: String,
    val status: AssignmentStatus = AssignmentStatus.Pending,
    val state: ReportState? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    /** Executor 的最终报告;question 非空表示在等人。 */
    val result: String = "",
    val question: String = "",
    val outcome: String = "",
    val reports: List<AssignmentReport> = emptyList(),
)

/** 报告驱动的票结局;stopped 由人的停止写入,Executor 不能伪造。 */
val TicketEndOutcomes = setOf("done", "merged", "closed", "dropped", "stopped")

fun waitsForPerson(assignment: Assignment) =
    assignment.state == ReportState.Waiting && assignment.question.isNotBlank()

/** Eval 对一个 Trigger 的判决;accept 创建 Ticket,attach 归给已有 Ticket,stop 停掉一个。 */
@Serializable
sealed interface Decision {
    val triggerId: String
    val reason: String

    @Serializable
    data class Accept(
        override val triggerId: String,
        val title: String,
        val scope: String,
        override val reason: String,
        /** 推断路径必填;显式路径可省。 */
        val matchedRule: String = "",
        val direction: String = "",
        val motivationRef: String = "",
        val hostVerified: Boolean = false,
    ) : Decision

    @Serializable
    data class Attach(override val triggerId: String, val ticketId: String, override val reason: String) : Decision

    @Serializable
    data class Ignore(override val triggerId: String, override val reason: String) : Decision

    @Serializable
    data class Stop(override val triggerId: String, val ticketId: String, override val reason: String) : Decision
}

@Serializable
enum class AcceptMode { /** 不接受推断请求 */ Off, /** 记录「本会接受」,不实际开工 */ Observe, /** 接受 */ Auto }

/** 版本化的准许规则;每个来源有自己的名单,不同来源的身份不互认。 */
@Serializable
data class Policy(
    val version: String,
    val acceptMode: AcceptMode = AcceptMode.Off,
    /** source instance → 允许发起工作的人(来源自己的身份口径)。 */
    val allowedActors: Map<String, List<String>> = emptyMap(),
    /** source instance → scope → 准入表;scope 用 `*` 作 fallback。 */
    val admissionTables: Map<String, Map<String, AdmissionTable>> = emptyMap(),
    /** 显式配置 false 才算停听;缺失来源保持兼容。 */
    val activeSources: Map<String, Boolean> = emptyMap(),
    /** source instance → 启用规则;缺失来源表示规则全部可用。 */
    val enabledRules: Map<String, List<String>> = emptyMap(),
) {
    fun allows(sourceInstance: String, actor: String) = allowedActors[sourceInstance]?.contains(actor) == true
}

/** 准入判定:gate 只看名单、是否点名、线程上有没有 Ticket,不问模型。 */
data class GateResult(val strength: Strength, val basis: String)

/** 一次 Eval 的产出:给 Executor 的接受(含 Ticket),或记录下来的别的判决。 */
sealed interface EvalOutcome {
    data class TicketCreated(val ticket: Ticket, val assignment: Assignment) : EvalOutcome
    data class TicketResumed(val ticket: Ticket) : EvalOutcome
    data class TicketStopped(val ticket: Ticket) : EvalOutcome
    data class Attached(val ticket: Ticket) : EvalOutcome
    data class Ignored(val decision: Decision.Ignore) : EvalOutcome
    /** Observe 模式:记下「本会接受」,不创建 Ticket。 */
    data class WouldAccept(val triggerId: String, val reason: String) : EvalOutcome
    /** gate 判为 not admitted 或 Router 不可用。 */
    data class NotAdmitted(val triggerId: String, val reason: String) : EvalOutcome
}
