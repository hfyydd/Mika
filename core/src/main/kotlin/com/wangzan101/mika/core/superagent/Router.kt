package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * LLM 实现的 Router:一次调用判完整批,输出 JSON 数组,每项对应一个 Trigger。
 * 判决只有 accept / attach / ignore / stop 四种;改写用户请求、自造验收标准都不允许——
 * accept 的 title/scope 只做登记,Executor 读原始 Trigger(链式召回)。
 */
class LlmRouter(private val model: ChatModel) : Router {

    override suspend fun assess(batch: List<Trigger>, threadSummary: String, tickets: List<Ticket>, policy: Policy): List<Decision> {
        if (batch.isEmpty()) return emptyList()
        val system = """
            You are the router of a super-agent system. A phone assistant executes the work; you only decide routing.
            For each incoming trigger, output exactly one decision:
            - accept: new work; include "title" (short) and "scope" (one sentence describing what the work may touch).
            - attach: the trigger points at an existing ticket by id; include "ticketId".
            - ignore: not work for the agent; include a short reason.
            - stop: a person explicitly stops a ticket; include "ticketId".
            Only a trigger that explicitly points at a ticket may be attached to it; similar topic is not enough.
            Do not invent acceptance criteria or rewrite requests.
            Reply with ONLY a JSON array, one object per trigger, in order:
            [{"triggerId": "...", "action": "accept|attach|ignore|stop", "title": "...", "scope": "...", "ticketId": "...", "reason": "..."}]
        """.trimIndent()

        val user = buildString {
            appendLine("Policy: version=${policy.version}, acceptMode=${policy.acceptMode.name}")
            appendLine("Thread summary: $threadSummary")
            if (tickets.isNotEmpty()) {
                appendLine("Tickets on this thread:")
                tickets.forEach { t ->
                    appendLine("  - id=${t.id} [${t.status.name.lowercase()}] ${t.title} (mandate scope: ${t.mandate.scope})")
                }
            }
            appendLine("Triggers (judge each):")
            batch.forEach { t ->
                appendLine("  - id=${t.id} actor=${t.actor} person=${t.isPerson} addressed=${t.addressed} at=${t.atMs}")
                appendLine("    text: ${t.text.take(600)}")
            }
        }

        val reply = model.complete(system, listOf(Message(Role.User, listOf(Part.Text(user)), Author.Mika)), emptyList())
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, batch)
    }

    companion object {
        private val ACTION_PATTERN = Regex("\"action\"\\s*:\\s*\"(\\w+)\"")
        private val TRIGGER_ID_PATTERN = Regex("\"triggerId\"\\s*:\\s*\"([^\"]*)\"")
        private val TICKET_ID_PATTERN = Regex("\"ticketId\"\\s*:\\s*\"([^\"]*)\"")
        private val TITLE_PATTERN = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"")
        private val SCOPE_PATTERN = Regex("\"scope\"\\s*:\\s*\"([^\"]*)\"")
        private val REASON_PATTERN = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")

        private fun first(text: String, pattern: Regex, from: Int, to: Int): String? =
            pattern.findAll(text).firstOrNull { it.range.first in from..to }?.groupValues?.get(1)

        /**
         * 按触发器顺序切出各判决;某项解析失败按 ignore 处理(记下原因),
         * 输出与 [batch] 同序同长,调用方不需要对齐逻辑。
         */
        fun parse(text: String, batch: List<Trigger>): List<Decision> {
            val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val bounds = TRIGGER_ID_PATTERN.findAll(trimmed).map { it.range }.toList()
            if (bounds.isEmpty()) {
                return batch.map { Decision.Ignore(it.id, "router output unparseable") }
            }
            return batch.mapIndexed { i, trigger ->
                val range = bounds.getOrNull(i) ?: IntRange.EMPTY
                if (range.isEmpty()) return@mapIndexed Decision.Ignore(trigger.id, "router output missing entry")
                val from = range.first
                // 窗口:本项 triggerId 的起点,到下一项 triggerId 的起点(或全文末尾);字段都在本项自己的窗口里找。
                val to = bounds.getOrNull(i + 1)?.first?.minus(1) ?: trimmed.length
                val action = first(trimmed, ACTION_PATTERN, from, to) ?: return@mapIndexed Decision.Ignore(trigger.id, "missing action")
                val reason = first(trimmed, REASON_PATTERN, from, to) ?: ""
                when (action.lowercase()) {
                    "accept" -> Decision.Accept(trigger.id, first(trimmed, TITLE_PATTERN, from, to) ?: trigger.text.take(40), first(trimmed, SCOPE_PATTERN, from, to) ?: "", reason)
                    "attach" -> Decision.Attach(trigger.id, first(trimmed, TICKET_ID_PATTERN, from, to) ?: "", reason)
                    "stop" -> Decision.Stop(trigger.id, first(trimmed, TICKET_ID_PATTERN, from, to) ?: "", reason)
                    else -> Decision.Ignore(trigger.id, reason.ifBlank { "ignored by router" })
                }
            }
        }
    }
}
