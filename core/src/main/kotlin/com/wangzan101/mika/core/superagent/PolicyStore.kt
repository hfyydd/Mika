package com.wangzan101.mika.core.superagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val DEFAULT_POLICY_VERSION = "superagent#0"

/** Policy 的人力编辑要跨进程生效;状态由 Engine 管,策略单独落盘。 */
interface PolicyStore {
    fun load(): Policy
    fun save(policy: Policy)
}

class MemoryPolicyStore(initial: Policy = Policy(version = DEFAULT_POLICY_VERSION)) : PolicyStore {
    private var policy = initial

    @Synchronized override fun load() = policy
    @Synchronized override fun save(policy: Policy) { this.policy = policy }
}

class FilePolicyStore(private val path: Path) : PolicyStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): Policy = if (!Files.exists(path)) {
        Policy(version = DEFAULT_POLICY_VERSION)
    } else {
        runCatching { json.decodeFromString<Policy>(path.toFile().readText()) }.getOrDefault(Policy(version = DEFAULT_POLICY_VERSION))
    }

    override fun save(policy: Policy) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.createDirectories(path.parent)
        tmp.toFile().writeText(json.encodeToString(policy))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

}

