package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuperagentSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `settings persist enabled state and validate interval`() {
        val store = FileSuperagentSettingsStore(folder.newFile("settings.json").toPath())
        val settings = SuperagentSettings(
            enabled = true,
            pollIntervalMs = 5_000,
        )

        store.save(settings)

        assertEquals(settings, store.load())
        assertTrue(store.load().enabled)
        assertThrows(IllegalArgumentException::class.java) { SuperagentSettings(pollIntervalMs = 0) }
    }
}
