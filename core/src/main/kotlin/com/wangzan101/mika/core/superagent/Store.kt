package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 全部状态的内存视图;Engine 是唯一写者。 */
@Serializable
data class SuperagentState(
    val threads: List<Thread> = emptyList(),
    val triggers: List<Trigger> = emptyList(),
    val tickets: List<Ticket> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    /** 已记录的判决(id = triggerId);幂等与审计用。 */
    val decisions: Map<String, String> = emptyMap(),
    /** 收件游标,按 source instance 记;重启后不重放事件。 */
    val readPositions: Map<String, String> = emptyMap(),
    /** Observe 模式的「本会接受」记录。 */
    val observations: List<String> = emptyList(),
)

interface SuperagentStore {
    fun load(): SuperagentState
    fun save(state: SuperagentState)
}

class MemorySuperagentStore(initial: SuperagentState = SuperagentState()) : SuperagentStore {
    private var state = initial

    @Synchronized override fun load() = state
    @Synchronized override fun save(state: SuperagentState) { this.state = state }
}

/** 单文件 JSON 快照,原子替换写入(与 FileRunStateStore 同一模式);进程不会读到半截。 */
class FileSuperagentStore(private val path: Path) : SuperagentStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): SuperagentState {
        if (!Files.exists(path)) return SuperagentState()
        return try {
            json.decodeFromString<SuperagentState>(Files.readString(path))
        } catch (_: Exception) {
            SuperagentState()
        }
    }

    override fun save(state: SuperagentState) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.createDirectories(path.parent)
        Files.writeString(tmp, json.encodeToString(state))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
