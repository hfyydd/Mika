package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec

/**
 * Mika TaskCenter/ChildRunHost 的最小执行端口。App 装配层负责把 [RunSpec] 交给真的 TaskCenter;
 * 这里只做 Ticket → RunSpec 的边界翻译,并保留“原请求是引用、不是改写”的纪律。
 */
fun interface TaskHost {
    suspend fun run(spec: RunSpec): ChildResult
}

data class MikaRunScope(val capabilities: Set<String>, val readOnly: Boolean)

class TaskHostExecutor(
    private val host: TaskHost,
    /** 返回票里的原始 Trigger 文本;装配层用 Engine 快照查找,不把改写后的摘要传给执行器。 */
    private val rawRequest: suspend (Ticket) -> String? = { null },
    /** 看门狗重派时给新 Assignment 独立 RunId,TaskCenter 才不会按旧 Run 去重。 */
    private val runId: suspend (Ticket) -> String = { "superagent-${it.id}" },
) : SuperExecutor {
    override suspend fun run(ticket: Ticket, threadSummary: String): ExecutorReport {
        val request = rawRequest(ticket)?.take(TRIGGER_TEXT_LIMIT)
            ?: ticket.instruction.removePrefix("trigger:").ifBlank { ticket.title }
        val goal = buildString {
            append(ticket.title)
            append("\n\nOriginal request (use this as the source of truth; do not paraphrase it):\n")
            append(request)
            if (threadSummary.isNotBlank()) {
                append("\n\nRecent thread references:\n")
                append(threadSummary.take(THREAD_LIMIT))
            }
        }
        val scope = mikaRunScope(ticket.mandate.scope)
        val result = host.run(
            RunSpec(
                id = RunId(runId(ticket)),
                goal = goal,
                threadId = ticket.threadKey,
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
                capabilities = scope.capabilities,
                readOnly = scope.readOnly,
            ),
        )
        return when (result.phase) {
            ChildPhase.Completed -> ExecutorReport(ReportState.Ready, result.text, outcome = "done")
            ChildPhase.Interrupted -> ExecutorReport(
                ReportState.Blocked,
                result.text,
                question = result.text.ifBlank { "interrupted" },
            )
            ChildPhase.Unknown -> ExecutorReport(ReportState.Failed, result.text.ifBlank { "run outcome unknown" })
            ChildPhase.Running -> ExecutorReport(ReportState.Progress, result.text)
        }
    }

    companion object {
        const val TRIGGER_TEXT_LIMIT = 8_000
        const val THREAD_LIMIT = 2_000

        /** Scope 登记句只做保守映射;认不出的授权不给能力,并退到只读。 */
        fun mikaRunScope(scope: String): MikaRunScope {
            val text = scope.lowercase()
            val capabilities = buildSet {
                if ("phone" in text || "app" in text || "手机" in text) add("phone")
                if ("web" in text || "网页" in text || "网站" in text || "browser" in text) add("web")
                if ("sandbox" in text || "shell" in text || "代码" in text || "文件" in text) add("sandbox")
            }
            val readIntent = Regex("""\b(read|observe|research|summar\w*|analy\w*|report)\b""").containsMatchIn(text) ||
                listOf("read only", "read-only", "只读", "观察", "调研", "查").any { it in text }
            val writeIntent = Regex("""\b(open|click|type|send|post|create|edit|delete|install|run|execute)\b""").containsMatchIn(text) ||
                listOf("打开", "点击", "输入", "发送", "发布", "创建", "编辑", "删除", "安装", "运行", "执行").any { it in text }
            val readOnly = capabilities.isEmpty() || (readIntent && !writeIntent)
            return MikaRunScope(capabilities, readOnly)
        }
    }
}
