package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {

    private val policy = Policy(
        version = "replay-p1",
        acceptMode = AcceptMode.Auto,
        allowedActors = mapOf("s1" to listOf("alice", "ci-bot")),
    )

    private fun trigger(id: String, actor: String, isPerson: Boolean, threadKey: String = id) = Trigger(
        id = id, source = SourceRef("replay", "s1"), externalId = "e-$id", threadKey = threadKey,
        actor = actor, isPerson = isPerson, addressed = true, text = "replay $id", atMs = 100,
    )

    private fun records(): List<ReplayRecord> {
        val explicitTrigger = trigger("explicit", "alice", true)
        val inferredTrigger = trigger("inferred", "mallory", true)
        val machineTrigger = trigger("machine", "ci-bot", false)
        val strict = { id: String ->
            Decision.Accept(
                id, "replay work", "replay scope", "inferred", matchedRule = "replay-rule",
                direction = "create", motivationRef = "replay $id", hostVerified = true,
            )
        }
        return listOf(
            ReplayRecord(
                ReplayCase("explicit", policy, listOf(explicitTrigger), mapOf("explicit" to Decision.Accept("explicit", "work", "phone", "explicit"))),
                ReplayResult("explicit", listOf(ReplayOutcome("TicketCreated", "explicit", "ticket-explicit", "Explicit", "allowed_address"))),
            ),
            ReplayRecord(
                ReplayCase("inferred", policy, listOf(inferredTrigger), mapOf("inferred" to strict("inferred"))),
                ReplayResult("inferred", listOf(ReplayOutcome("TicketCreated", "inferred", "ticket-inferred", "Inferred", "unlisted_address"))),
            ),
            ReplayRecord(
                ReplayCase("machine", policy, listOf(machineTrigger), mapOf("machine" to strict("machine"))),
                ReplayResult("machine", listOf(ReplayOutcome("TicketCreated", "machine", "ticket-machine", "Inferred", "bot_allowlisted"))),
            ),
        )
    }

    @Test
    fun `jsonl replay is stable across explicit inferred and machine paths`() = runTest {
        val records = records()
        val text = Replay.encode(records)
        assertEquals(text, Replay.encode(Replay.decode(text)))

        for (record in Replay.decode(text)) {
            val actual = Replay.run(record.case)
            assertEquals(record.expected, actual)
            assertEquals(Replay.encodedResult(record.expected), Replay.encodedResult(actual))
        }
    }

    @Test
    fun `sample replay remains byte identical`() = runTest {
        val text = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/superagent/replay.jsonl"),
        )
        val records = Replay.decode(text)
        assertEquals(listOf("explicit", "inferred", "machine"), records.map { it.case.name })
        assertEquals(text, Replay.encode(records))
        for (record in records) {
            val actual = Replay.run(record.case)
            assertTrue(actual.outcomes.isNotEmpty())
            assertEquals(record.expected, actual)
        }
    }
}
