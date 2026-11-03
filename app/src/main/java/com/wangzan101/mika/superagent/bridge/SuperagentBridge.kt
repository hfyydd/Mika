package com.wangzan101.mika.superagent.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Worker 对用户暴露的报告；Super 模式把它映射到聊天卡片。 */
data class SuperWorkStatus(
    val ticketId: String,
    val goal: String,
    val line: String,
)

/**
 * App 进程里的 Super 模式桥：本地聊天入口把请求送进 LocalSource，
 * 运行服务把它接进 Engine 后，聊天按用户对话取回当前状态。
 * 桥只记 conversationId 与最新一行；权威账本仍在 files/superagent。
 */
class SuperagentBridge {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _statuses = MutableStateFlow(emptyMap<String, SuperWorkStatus>())
    val statuses: StateFlow<Map<String, SuperWorkStatus>> = _statuses.asStateFlow()

    /** 本地聊天这个对话已经建到哪个 Ticket。 */
    private val tickets = mutableMapOf<String, String>()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (!value) _statuses.value = emptyMap()
    }

    fun registerSource(source: SuperagentLocalSource) {
        sourceRef = source
    }

    fun unregisterSource() {
        sourceRef = null
    }

    fun recordStarted(conversationId: String, ticketId: String, goal: String) {
        synchronized(tickets) { tickets[conversationId] = ticketId }
        set(_statuses) { it + (conversationId to SuperWorkStatus(ticketId, goal, "accepted")) }
    }

    fun updateStatus(conversationId: String, ticketId: String, goal: String, line: String) {
        if (synchronized(tickets) { tickets[conversationId] } != ticketId) return
        set(_statuses) { it + (conversationId to SuperWorkStatus(ticketId, goal, line)) }
    }

    fun clear(conversationId: String) {
        synchronized(tickets) { tickets.remove(conversationId) }
        set(_statuses) { it - conversationId }
    }

    fun statusFor(conversationId: String): SuperWorkStatus? = _statuses.value[conversationId]

    /** 服务还没起时返回 false，聊天入口照常走 Mika 本体任务。 */
    fun send(conversationId: String, text: String): Boolean {
        if (!_enabled.value) return false
        sourceRef?.send(conversationId, externalId = "m${System.currentTimeMillis()}", actor = "owner", text = text)
        return true
    }

    private fun <T> set(target: MutableStateFlow<T>, transform: (T) -> T) {
        target.value = transform(target.value)
    }

    companion object {
        private var shared: SuperagentBridge? = null
        private var sourceRef: SuperagentLocalSource? = null

        /** 桥跟着 App 进程；入口服务、任务服务和聊天页用同一份。 */
        fun get(context: Context): SuperagentBridge {
            shared?.let { return it }
            val bridge = SuperagentBridge().also { shared = it }
            return bridge
        }
    }
}
