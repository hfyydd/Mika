package com.wangzan101.mika.core.superagent

/** Android 通知只对“确实在等人”的票发;同一票的 Waiting 报告去重。 */
object SuperagentNotifications {
    data class Draft(
        val ticketId: String,
        val title: String,
        val question: String,
        val threadKey: String,
    )

    fun drafts(state: SuperagentState): List<Draft> = TicketPanel.rows(state)
        .filter { it.isOpen }
        .mapNotNull { ticket ->
            val assignment = state.assignments.lastOrNull { it.ticketId == ticket.id }
            if (assignment == null || !waitsForPerson(assignment)) return@mapNotNull null
            Draft(ticket.id, ticket.title, assignment.question, ticket.threadKey)
        }
        .distinctBy { it.ticketId }
}
