package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PolicyEditorTest {
    private val policy = Policy("base#2", allowedActors = mapOf("s1" to listOf("alice")))

    @Test
    fun `actor and admission edits bump policy version`() {
        val added = PolicyEditor.addActor(policy, "s1", "bob")
        val removed = PolicyEditor.removeActor(added, "s1", "alice")
        val cell = PolicyEditor.setCell(removed, "s1", identity = Identity.Unlisted, targeting = Targeting.Address, cell = AdmissionCell.Ignore)

        assertEquals(listOf("bob"), cell.allowedActors.getValue("s1"))
        assertEquals(AdmissionCell.Ignore, cell.admissionTable("s1").unlistedAddressed)
        assertEquals("base#5", cell.version)
        assertFalse(PolicyEditor.addActor(cell, "s1", "bob").version.endsWith("#6"))
    }

    @Test
    fun `accept mode is reversible edit but every change is versioned`() {
        val observe = PolicyEditor.setAcceptMode(policy, AcceptMode.Observe)
        val auto = PolicyEditor.setAcceptMode(observe, AcceptMode.Auto)
        assertEquals(AcceptMode.Observe, observe.acceptMode)
        assertEquals("base#3", observe.version)
        assertEquals(AcceptMode.Auto, auto.acceptMode)
        assertEquals("base#4", auto.version)
    }
}
