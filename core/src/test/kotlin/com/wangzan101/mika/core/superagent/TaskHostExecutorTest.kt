package com.wangzan101.mika.core.superagent

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildResult
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHostExecutorTest {
    private fun ticket(scope: String) = Ticket(
        id = "t1", threadKey = "thread", title = "Summarize", instruction = "trigger:r1",
        mandate = Mandate("p1", "s1", scope, Strength.Explicit, "allowed_address", "alice", createdAtMs = 1),
        triggerIds = listOf("r1"), createdAtMs = 1, updatedAtMs = 1,
    )

    @Test
    fun `maps ticket to constrained unattended run and ready report`() = runTest {
        val specs = mutableListOf<com.wangzan101.mika.core.run.RunSpec>()
        val executor = TaskHostExecutor(
            host = { spec ->
                specs += spec
                ChildResult(spec.id, ChildPhase.Completed, "done")
            },
            rawRequest = { "Read inbox and reply with three facts" },
        )
        val report = executor.run(ticket("read the web page"), "alice: hello")

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertEquals("superagent-t1", spec.id.value)
        assertEquals(setOf("web"), spec.capabilities)
        assertEquals(true, spec.readOnly)
        assertEquals(RunOrigin.Trigger, spec.provenance.origin)
        assertEquals(Attendance.Unattended, spec.provenance.attendance)
        assertTrue("Read inbox and reply with three facts" in spec.goal)
        assertEquals(ReportState.Ready, report.state)
        assertEquals("done", report.outcome)
    }

    @Test
    fun `falls back to ticket instruction and maps interrupted to blocked`() = runTest {
        val executor = TaskHostExecutor(host = { spec ->
            ChildResult(spec.id, ChildPhase.Interrupted, "")
        })
        val report = executor.run(ticket("use the phone app"), "")

        assertEquals(setOf("phone"), TaskHostExecutor.mikaRunScope("use the phone app").capabilities)
        assertEquals(ReportState.Blocked, report.state)
        assertEquals("interrupted", report.question)
    }
}
