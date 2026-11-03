package com.wangzan101.mika.superagent

import android.content.Context
import com.wangzan101.mika.core.superagent.SuperagentState
import com.wangzan101.mika.core.superagent.SuperExecutor
import com.wangzan101.mika.core.superagent.TaskHostExecutor
import com.wangzan101.mika.core.superagent.Ticket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把 Engine 状态交给执行翻译层;Mika 的 Android 端口单独装配。
 * 原始 Trigger 也许在 [SuperagentState.triggers] 的 500 条窗口外:兜底回读最近落盘状态。
 */
class TicketHostTaskExecutor(
    context: Context,
    private val snapshot: suspend () -> SuperagentState,
) : SuperExecutor by TaskHostExecutor(
    host = AndroidAgentHost(context),
    rawRequest = { ticket ->
        snapshot().triggers
            .firstOrNull { it.id == ticket.triggerIds.firstOrNull() }?.text
            ?: FileTriggerArchive(context).read(ticket)
    },
)

/** 按 Ticket 的 Trigger 追加原文;Engine 的窗口裁剪后,Executor 仍能拿到原始请求。 */
class FileTriggerArchive(private val context: Context) {
    private val file = File(context.filesDir, "superagent/trigger-archive.jsonl")

    suspend fun read(ticket: Ticket): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val ids = ticket.triggerIds.toHashSet()
        file.useLines { lines ->
            lines.lastOrNull { line -> ids.any { id -> "\"id\":\"$id\"" in line } }
        }?.substringAfter("\"text\":\"")?.substringBefore("\",\"source\"")
    }
}
