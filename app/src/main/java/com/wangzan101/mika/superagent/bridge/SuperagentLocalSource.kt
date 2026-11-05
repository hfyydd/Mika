package com.wangzan101.mika.superagent.bridge

import com.wangzan101.mika.core.superagent.Source
import com.wangzan101.mika.core.superagent.SourceRef
import com.wangzan101.mika.core.superagent.SuperagentState
import com.wangzan101.mika.core.superagent.Trigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地聊天的触发器队列：来源进程只有这一份，聊天入口存进 [send],
 * Runtime 每次轮询后批量取走，其余账本规则照旧由 Engine 管。
 */
class SuperagentLocalSource : Source {
    override val kind = "chat"
    private val queue = ArrayDeque<Trigger>()
    private val _history = MutableStateFlow(emptyList<Trigger>())
    val history: StateFlow<List<Trigger>> = _history.asStateFlow()

    /** conversationId 作为 threadKey；同一段聊天直通已有 Ticket。 */
    fun send(conversationId: String, externalId: String, actor: String, text: String): Trigger {
        val triggerId = triggerId(conversationId, externalId)
        val trigger = Trigger(
            id = triggerId,
            source = SourceRef(kind, SOURCE_INSTANCE),
            externalId = externalId,
            threadKey = "chat:$conversationId",
            actor = actor,
            isPerson = true,
            addressed = true,
            text = text,
            atMs = System.currentTimeMillis(),
        )
        synchronized(queue) { queue.addLast(trigger) }
        return trigger
    }

    override suspend fun poll(state: SuperagentState): List<Trigger> {
        val ready = synchronized(queue) {
            val list = queue.toList()
            queue.clear()
            list
        }
        if (ready.isNotEmpty()) {
            _history.update { (it + ready).takeLast(200) }
        }
        return ready
    }

    override suspend fun rollback(triggers: List<Trigger>) {
        if (triggers.isEmpty()) return
        synchronized(queue) {
            triggers.asReversed().forEach { queue.addFirst(it) }
        }
        _history.update { current -> current - triggers.toSet() }
    }

    fun clearArchived() {
        _history.value = emptyList()
    }

    private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        value = transform(value)
    }

    companion object {
        const val SOURCE_INSTANCE = "local"

        /** Bridge 和 Source 必须用同一个编号约定，Runtime 才能把 Router 判决送回原对话。 */
        fun triggerId(conversationId: String, externalId: String) = "chat:$conversationId:$externalId"
    }
}

