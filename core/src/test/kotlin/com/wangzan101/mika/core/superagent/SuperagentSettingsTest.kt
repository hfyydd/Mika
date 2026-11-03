package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuperagentSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `settings persist telegram source and validate interval`() {
        val store = FileSuperagentSettingsStore(folder.newFile("settings.json").toPath())
        val settings = SuperagentSettings(
            enabled = true,
            pollIntervalMs = 5_000,
            telegram = TelegramConfig("main", "token", listOf("@alice")),
        )

        store.save(settings)

        assertEquals(settings, store.load())
        assertTrue(store.load().telegramReady)
        assertThrows(IllegalArgumentException::class.java) { SuperagentSettings(pollIntervalMs = 0) }
    }

    @Test
    fun `disabled or unconfigured telegram is not ready`() {
        assertFalse(SuperagentSettings(enabled = false).telegramReady)
        assertFalse(SuperagentSettings(enabled = true, telegram = TelegramConfig("", "token")).telegramReady)
    }
}
