package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 回复原路所需的全部引用;不携带改写后的上下文。 */
@Serializable
data class ReplyEnvelope(
    val source: SourceRef,
    val threadKey: String,
    val ticketId: String,
    val text: String,
    /** 原请求原文；本地聊天用它把回复并回同一张问答卡。 */
    val triggerText: String = "",
    /** true 表示这不是提问原线程，而是被授权的主动推送。 */
    val deliverHere: Boolean = false,
)

/** 来源回执;实现必须凭 [ReplyEnvelope] + [EffectRecord.id] 幂等。 */
fun interface ReplyChannel {
    suspend fun send(envelope: ReplyEnvelope): String
}

/**
 * 把“完成任务”和“把结果送回去”分开:前者进 Engine,后者先落 [EffectQueue],
 * 再按 source kind:instance 原路送。断线时行留在 Pending;Uncertain 语义由渠道自己的凭据判断。
 */
class SourceOutbox(
    private val effects: EffectQueue,
    private val channels: Map<String, ReplyChannel>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun enqueue(ticket: Ticket, trigger: Trigger, result: String, deliverHere: Boolean = false) {
        val envelope = ReplyEnvelope(
            trigger.source,
            trigger.threadKey,
            ticket.id,
            result,
            deliverHere = deliverHere,
            triggerText = trigger.text,
        )
        val payload = json.encodeToString(envelope)
        effects.submit("reply:${ticket.id}:${payload.hashCode()}", EffectKind.Reply, ticket.id, payload = payload)
    }

    suspend fun flush(): Int {
        val pending = effects.all().filter { it.kind == EffectKind.Reply && it.state != EffectState.Done }
        var count = 0
        for (record in pending) {
            val envelope = runCatching { json.decodeFromString<ReplyEnvelope>(record.payload) }.getOrNull() ?: continue
            val channel = channels["${envelope.source.kind}:${envelope.source.instance}"] ?: continue
            count += effects.drain { row ->
                if (row.id != record.id) error("queue is blocked by ${row.id}")
                if (row.state == EffectState.Done) return@drain row.receipt.ifBlank { "already:${row.id}" }
                channel.send(envelope)
            }
        }
        return count
    }
}
