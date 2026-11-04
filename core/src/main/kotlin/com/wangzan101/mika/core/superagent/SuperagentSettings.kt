package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 用户显式打开的 Super 配置。 */
@Serializable
data class SuperagentSettings(
    val enabled: Boolean = false,
    val pollIntervalMs: Long = SuperagentRuntime.DEFAULT_INTERVAL_MS,
) {
    init {
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
    }
}

interface SuperagentSettingsStore {
    fun load(): SuperagentSettings
    fun save(settings: SuperagentSettings)
}

class FileSuperagentSettingsStore(private val path: Path) : SuperagentSettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): SuperagentSettings = if (!Files.exists(path)) {
        SuperagentSettings()
    } else {
        runCatching { json.decodeFromString<SuperagentSettings>(path.toFile().readText()) }.getOrElse { SuperagentSettings() }
    }

    override fun save(settings: SuperagentSettings) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.createDirectories(path.parent)
        tmp.toFile().writeText(json.encodeToString(settings))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
