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
    val reason: String = "",
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
    private val tickets = HashMap<String, String>()

    /** 还在 Router/Eval 前的 Trigger;判完以后用它把失败原因送回聊天卡片。 */
    private val pendingTriggers = HashMap<String, String>()
    private val pendingGoals = HashMap<String, String>()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (!value) reset()
    }

    fun registerSource(source: SuperagentLocalSource) {
        sourceRef = source
    }

    /** Runtime 收完本地来源的一批消息后可按状态推进卡片。 */
    fun report(conversationId: String, ticketId: String, goal: String, line: String) {
        val conversation = localConversation(conversationId)
        synchronized(tickets) {
            if (tickets[conversation] == null) tickets[conversation] = ticketId
        }
        val knownGoal = _statuses.value[conversation]?.goal
        updateStatus(conversation, ticketId, knownGoal ?: goal, line)
    }

    fun unregisterSource() {
        sourceRef = null
    }

    /** Runtime 接受了这条请求:把 pending 换成真正的 Ticket,后面的进度都按它校验。 */
    fun accepted(conversationId: String, ticketId: String, goal: String) {
        synchronized(tickets) {
            tickets[conversationId] = ticketId
            pendingTriggers.values.removeAll(setOf(conversationId))
            pendingGoals.values.removeAll(setOf(conversationId))
        }
        val request = _statuses.value[conversationId]?.goal ?: goal
        set(_statuses) { it + (conversationId to SuperWorkStatus(ticketId, request, "working")) }
    }

    fun updateStatus(conversationId: String, ticketId: String, goal: String, line: String) {
        val bound = synchronized(tickets) { tickets[conversationId] }
        if (bound != null && bound != ticketId) return
        set(_statuses) { it + (conversationId to SuperWorkStatus(ticketId, goal, line)) }
    }

    fun clear(conversationId: String) {
        synchronized(tickets) {
            tickets.remove(conversationId)
            pendingTriggers.values.remove(conversationId)
            pendingGoals.values.remove(conversationId)
        }
        set(_statuses) { it - conversationId }
    }

    /** Router 明确不接这条 Super 请求:不能把用户留在永远转圈的卡片上。 */
    fun rejected(triggerId: String, reason: String) {
        val conversation = synchronized(pendingTriggers) { pendingTriggers.remove(triggerId) } ?: return
        val goal = synchronized(pendingGoals) { pendingGoals.remove(triggerId) }.orEmpty()
        synchronized(tickets) { tickets.remove(conversation) }
        set(_statuses) { it + (conversation to SuperWorkStatus(triggerId, goal, "failed", reason)) }
    }

    fun statusFor(conversationId: String): SuperWorkStatus? = _statuses.value[conversationId]

    /** 服务还没起时返回 false，聊天入口照常走 Mika 本体任务。 */
    /** 服务还没起时返回 false，聊天入口照常走 Mika 本体任务。记录当前对话的活跃任务状态。 */
    fun send(conversationId: String, text: String): Boolean {
        if (!_enabled.value) return false
        val externalId = "m${java.util.UUID.randomUUID()}"
        val triggerId = SuperagentLocalSource.triggerId(conversationId, externalId)
        sourceRef?.send(conversationId, externalId = externalId, actor = "owner", text = text)
        synchronized(pendingTriggers) { pendingTriggers[triggerId] = conversationId }
        synchronized(pendingGoals) { pendingGoals[triggerId] = text }
        set(_statuses) { it + (conversationId to SuperWorkStatus(triggerId, text, "working")) }
        return true
    }

    private fun reset() {
        synchronized(tickets) {
            tickets.clear()
            pendingTriggers.clear()
            pendingGoals.clear()
        }
        _statuses.value = emptyMap()
    }

    private fun localConversation(raw: String) = raw.removePrefix("chat:")

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
