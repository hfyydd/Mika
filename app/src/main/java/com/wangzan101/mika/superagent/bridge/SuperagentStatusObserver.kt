package com.wangzan101.mika.superagent.bridge

import android.content.Context
import com.wangzan101.mika.core.superagent.ReportState
import com.wangzan101.mika.core.superagent.SuperagentState

/**
 * Runtime 每次轮询后把 Super 状态推进到本地聊天；只在账本变化时更新。
 */
class SuperagentStatusObserver(
    private val context: Context,
) {
    fun apply(state: SuperagentState) {
        val bridge = SuperagentBridge.get(context)
        for (ticket in state.tickets) {
            val assignment = state.assignments.lastOrNull { it.ticketId == ticket.id } ?: continue
            val line = when (assignment.state) {
                ReportState.Progress -> "working"
                ReportState.Waiting -> "waiting"
                ReportState.Ready -> "done"
                ReportState.Blocked -> "waiting"
                ReportState.Failed, ReportState.Dropped -> "failed"
                null -> "working"
            }
            // 结束卡片要留给用户看到结果；下一条 Super 消息会覆盖同一对话的状态。
            bridge.report(ticket.threadKey, ticket.id, ticket.title, line)
        }
    }
}
