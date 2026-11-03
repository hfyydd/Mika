package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class EffectKind { Assignment, Ack, Reply }
enum class EffectState { Pending, Attempting, Done, Failed }

/** 一行一个副作用;副作用执行前先落 Attempting,执行后写凭据或退避时间。 */
@Serializable
data class EffectRecord(
    val id: String,
    val kind: EffectKind,
    val ticketId: String,
    val assignmentId: String = "",
    val payload: String = "",
    val state: EffectState = EffectState.Pending,
    val seq: Long = 0,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    val availableAtMs: Long = 0,
    val completedAtMs: Long? = null,
    val receipt: String = "",
    val lastError: String = "",
)

interface EffectStore {
    fun all(): List<EffectRecord>
    fun save(record: EffectRecord)
}

class MemoryEffectStore(initial: List<EffectRecord> = emptyList()) : EffectStore {
    private val records = initial.associateByTo(linkedMapOf()) { it.id }

    @Synchronized override fun all() = records.values.sortedBy { it.seq }
    @Synchronized override fun save(record: EffectRecord) { records[record.id] = record }
}

class FileEffectStore(private val path: Path) : EffectStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memory = MemoryEffectStore(load())

    private fun load(): List<EffectRecord> {
        if (!Files.exists(path)) return emptyList()
        return try {
            json.decodeFromString<List<EffectRecord>>(Files.readString(path))
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun all() = memory.all()

    override fun save(record: EffectRecord) {
        memory.save(record)
        val records = memory.all()
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.createDirectories(path.parent)
        Files.writeString(tmp, json.encodeToString(records))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * 持久副作用队列。同一 id 只能提交一次;每张票按 seq 保序,全局一次执行一条。
 * 重启后 Attempting 会回到 Pending,调用方必须按 [EffectRecord.id] 幂等。
 */
class EffectQueue(private val store: EffectStore, private val now: () -> Long = System::currentTimeMillis) {
    private val seq = (store.all().maxOfOrNull { it.seq } ?: 0L)

    fun all(): List<EffectRecord> = store.all()

    fun submit(id: String, kind: EffectKind, ticketId: String, assignmentId: String = "", payload: String = ""): EffectRecord {
        store.all().firstOrNull { it.id == id }?.let { return it }
        val record = EffectRecord(
            id = id, kind = kind, ticketId = ticketId, assignmentId = assignmentId,
            payload = payload, seq = seq + store.all().size + 1, availableAtMs = now(),
        )
        store.save(record)
        return record
    }

    /** 程序重启后的唯一恢复入口:Attempting 是上次崩溃的未知点,交给幂等 action 决定是否跳过。 */
    fun recover() {
        store.all().filter { it.state == EffectState.Attempting }.forEach {
            store.save(it.copy(state = EffectState.Pending))
        }
    }

    fun due(nowMs: Long = now()): List<EffectRecord> =
        store.all().filter { it.state == EffectState.Pending && it.availableAtMs <= nowMs }

    /**
     * 执行到没有到期行为止;action 返回凭据,抛 CancellationException 不消耗重试次数。
     * 返回本轮成功的行数。
     */
    suspend fun drain(action: suspend (EffectRecord) -> String): Int {
        var completed = 0
        while (true) {
            val next = store.all().filter { it.state == EffectState.Pending }.minByOrNull { it.seq } ?: break
            if (next.availableAtMs > now()) break
            val record = next
            store.save(record.copy(state = EffectState.Attempting))
            try {
                val receipt = action(record)
                store.save(record.copy(
                    state = EffectState.Done, attempts = record.attempts + 1,
                    completedAtMs = now(), receipt = receipt,
                ))
                completed++
            } catch (e: kotlinx.coroutines.CancellationException) {
                store.save(record.copy(state = EffectState.Pending))
                throw e
            } catch (e: Exception) {
                val attempts = record.attempts + 1
                if (attempts >= record.maxAttempts) {
                    store.save(record.copy(state = EffectState.Failed, attempts = attempts, lastError = e.message.orEmpty()))
                } else {
                    store.save(record.copy(
                        state = EffectState.Pending, attempts = attempts,
                        availableAtMs = now() + BACKOFF_MS * (1L shl (attempts - 1)),
                        lastError = e.message.orEmpty(),
                    ))
                }
            }
        }
        return completed
    }

    companion object {
        const val BACKOFF_MS = 1_000L
    }
}
