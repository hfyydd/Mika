package com.wangzan101.mika.core.superagent

enum class PanelStage { Person, Running, Waiting, Ready, Blocked, Failed, Dropped, Open, Ended }

/** 面板排序和徽标只从账本计算;自由文本不参与状态判断。 */
object TicketPanel {
    fun rows(state: SuperagentState): List<Ticket> {
        val byTicket = state.assignments.groupBy { it.ticketId }
        return state.tickets.sortedWith(
            compareByDescending<Ticket> { it.isOpen && byTicket[it.id].orEmpty().any(::waitsForPerson) }
                .thenBy { stageRank(it, byTicket[it.id]?.maxByOrNull { assignment -> assignment.reports.size }) }
                .thenByDescending { it.updatedAtMs },
        )
    }

    fun stage(ticket: Ticket, assignment: Assignment?): PanelStage = when {
        !ticket.isOpen -> when (ticket.ended?.outcome) {
            "stopped" -> PanelStage.Ended
            "dropped" -> PanelStage.Dropped
            else -> PanelStage.Ended
        }
        waitsForPerson(assignment ?: Assignment("none", ticket.id)) -> PanelStage.Person
        assignment == null -> PanelStage.Open
        assignment.status == AssignmentStatus.Ended -> when (assignment.state) {
            ReportState.Ready -> PanelStage.Ready
            ReportState.Blocked -> PanelStage.Blocked
            ReportState.Failed -> PanelStage.Failed
            ReportState.Dropped -> PanelStage.Dropped
            else -> PanelStage.Open
        }
        assignment.state == ReportState.Waiting -> PanelStage.Waiting
        else -> PanelStage.Running
    }

    fun detail(state: SuperagentState, ticketId: String): TicketDetail? {
        val ticket = state.tickets.firstOrNull { it.id == ticketId } ?: return null
        val assignment = state.assignments.filter { it.ticketId == ticketId }.maxByOrNull { it.reports.size }
        val inputs = ticket.inputs.mapNotNull { input -> state.triggers.firstOrNull { it.id == input.triggerId } }
        return TicketDetail(ticket, assignment, inputs, stage(ticket, assignment))
    }
}

data class TicketDetail(
    val ticket: Ticket,
    val assignment: Assignment?,
    val inputs: List<Trigger>,
    val stage: PanelStage,
)

private fun stageRank(ticket: Ticket, assignment: Assignment?): Int = when (TicketPanel.stage(ticket, assignment)) {
        PanelStage.Person -> 0
        PanelStage.Running -> 1
        PanelStage.Waiting -> 2
        PanelStage.Ready -> 3
        PanelStage.Blocked -> 4
        PanelStage.Failed -> 5
        PanelStage.Dropped -> 6
        PanelStage.Open -> 7
        PanelStage.Ended -> 8
    }
