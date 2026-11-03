package com.wangzan101.mika.core.superagent

/**
 * 计算一个人的 Trigger 当前应直通给哪些 Ticket;返回空集合表示它要进 Eval。
 * 账本是唯一事实源:退回和只读上下文不产生拥有权,已经评估/重排队的 Trigger 不能再当跟进。
 */
fun followupOwners(
    trigger: Trigger,
    tickets: List<Ticket>,
    dispositions: Map<String, TriggerDisposition> = emptyMap(),
): List<String> {
    if (!trigger.isPerson) return emptyList()
    val disposition = dispositions[trigger.id]
    val handbacked = disposition?.handbacks?.map { it.ticketId }?.toSet() ?: emptySet()
    val contextOnly = disposition?.let { it.context.toSet() - it.fanout.toSet() } ?: emptySet()
    if (disposition?.requeued == true || disposition?.decisionId != null) return emptyList()

    return tickets.asSequence()
        .filter { it.threadKey == trigger.threadKey }
        .filter { it.id !in handbacked }
        .filter { it.id !in contextOnly }
        .filter { it.ended?.by != "person" }
        .map { it.id }
        .toList()
}
