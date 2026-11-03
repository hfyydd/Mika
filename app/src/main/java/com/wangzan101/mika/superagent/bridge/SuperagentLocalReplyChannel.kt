package com.wangzan101.mika.superagent.bridge

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.superagent.ReplyChannel
import com.wangzan101.mika.core.superagent.ReplyEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Super 层的本地回复通道：把结果、问题和失败写回用户原来发话的那个对话。
 * Ticket 的 threadKey 由 LocalChatSource 固定为 conversationId。
 */
class SuperagentLocalReplyChannel(private val context: Context) : ReplyChannel {
    override suspend fun send(envelope: ReplyEnvelope): String = withContext(Dispatchers.IO) {
        val conversationId = envelope.threadKey
        if (envelope.source.kind != ChatSourceKinds.CHAT) return@withContext ""
        val text = envelope.text.ifBlank { return@withContext "empty:local" }
        ConversationStore.shared(context).append(
            conversationId,
            Turn(
                userText = "",
                outcome = text,
                status = when {
                    envelope.deliverHere -> "unverified"
                    else -> "succeeded"
                },
            ),
        )
        SuperagentBridge.get(context).clear(conversationId)
        "local:${conversationId}:${envelope.ticketId}"
    }
}

object ChatSourceKinds {
    const val CHAT = "chat"
    const val LOCAL = "local"
}
