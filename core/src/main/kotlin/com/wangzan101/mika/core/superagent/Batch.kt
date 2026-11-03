package com.wangzan101.mika.core.superagent

/** 一批只选一个 Mandate 种子:显式强于推断,同级取最新,同刻保持输入顺序。 */
fun batchSeed(batch: List<Pair<Trigger, GateResult>>): Trigger? {
    var best: Pair<Trigger, GateResult>? = null
    for (candidate in batch) {
        if (candidate.second.strength == Strength.None) continue
        val current = best
        if (current == null) {
            best = candidate
            continue
        }
        val rank = when (candidate.second.strength) {
            Strength.Explicit -> 2
            Strength.Inferred -> 1
            Strength.None -> 0
        }
        val currentRank = when (current.second.strength) {
            Strength.Explicit -> 2
            Strength.Inferred -> 1
            Strength.None -> 0
        }
        if (rank > currentRank || (rank == currentRank && candidate.first.atMs > current.first.atMs)) best = candidate
    }
    return best?.first
}

/** Trigger 已经开过票时不能再当新请求;这是同一条消息双开票的硬闸。 */
fun startedTicketTriggers(tickets: List<Ticket>): Set<String> = tickets.flatMap { it.triggerIds }.toSet()
