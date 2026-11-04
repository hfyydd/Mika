package com.wangzan101.mika.core.autonomy

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class GoalSnapshot(val goals: List<Goal> = emptyList())

interface GoalStore {
    fun upsert(goal: Goal)
    fun get(id: String): Goal?
    fun all(): List<Goal>
    fun remove(id: String)
}

class MemoryGoalStore(initial: List<Goal> = emptyList()) : GoalStore {
    private val goals = linkedMapOf<String, Goal>()

    init { initial.forEach { goals[it.id] = it } }

    @Synchronized override fun upsert(goal: Goal) { goals[goal.id] = goal }
    @Synchronized override fun get(id: String) = goals[id]
    @Synchronized override fun all() = goals.values.toList()
    @Synchronized override fun remove(id: String) { goals.remove(id) }
}

class FileGoalStore(private val path: Path) : GoalStore {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val memory = MemoryGoalStore(load())

    private fun load(): List<Goal> {
        if (!Files.exists(path)) return emptyList()
        return try {
            json.decodeFromString<GoalSnapshot>(path.toFile().readText()).goals
        } catch (_: Exception) { emptyList() }
    }

    private fun persist() {
        val snapshot = GoalSnapshot(memory.all())
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.createDirectories(path.parent)
        tmp.toFile().writeText(json.encodeToString(snapshot))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun upsert(goal: Goal) { memory.upsert(goal); persist() }
    override fun get(id: String) = memory.get(id)
    override fun all() = memory.all()
    override fun remove(id: String) { memory.remove(id); persist() }
}
