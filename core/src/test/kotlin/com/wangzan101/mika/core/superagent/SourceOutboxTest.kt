package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOutboxTest {
    private fun ticket() = Ticket(
        id = "t1", threadKey = "chat", title = "done", instruction = "trigger:r1",
        mandate = Mandate("p", "main", "scope", Strength.Explicit, "allowed_address", "@alice", createdAtMs = 1),
        triggerIds = listOf("r1"), createdAtMs = 1, updatedAtMs = 1,
    )

    private fun trigger() = Trigger(
        id = "r1", source = SourceRef("telegram", "main"), externalId = "u1", threadKey = "chat",
        actor = "@alice", text = "please do it", atMs = 1,
    )

    @Test
    fun `enqueues once and sends by source route with receipt`() = runTest {
        var clock = 1L
        val effects = EffectQueue(MemoryEffectStore(), now = { clock })
        val sent = mutableListOf<ReplyEnvelope>()
        val outbox = SourceOutbox(
            effects,
            mapOf("telegram:main" to ReplyChannel { envelope, _ ->
                if (sent.isNotEmpty()) error("offline")
                clock = 2_000
                sent += envelope
                "telegram-message:1"
            }),
        )
        outbox.enqueue(ticket(), trigger(), "done")
        outbox.enqueue(ticket(), trigger(), "done")

        assertEquals(1, outbox.flush())
        assertEquals(0, outbox.flush())
        assertEquals(1, sent.size)
        assertEquals("telegram-message:1", effects.all().single().receipt)
        assertEquals(EffectState.Done, effects.all().single().state)
    }

    @Test
    fun `unknown source stays pending for later channel assembly`() = runTest {
        val effects = EffectQueue(MemoryEffectStore(), now = { 1 })
        val outbox = SourceOutbox(effects, emptyMap())
        outbox.enqueue(ticket(), trigger(), "done")
        assertEquals(0, outbox.flush())
        assertEquals(EffectState.Pending, effects.all().single().state)
        assertTrue(effects.due().isNotEmpty())
    }
}
