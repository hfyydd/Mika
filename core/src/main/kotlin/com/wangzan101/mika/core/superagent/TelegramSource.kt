package com.wangzan101.mika.core.superagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class TelegramGroupPolicy { MentionOnly, AllowAll }

@Serializable
data class TelegramConfig(
    val instance: String,
    val token: String,
    /** 允许发起工作的 Telegram username 或数字 id;空名单表示只收事件并按 not admitted 记账。 */
    val allowFrom: List<String> = emptyList(),
    val groupPolicy: TelegramGroupPolicy = TelegramGroupPolicy.MentionOnly,
    val botUsername: String = "",
    val baseUrl: String = "https://api.telegram.org",
)

/**
 * Telegram 长轮询来源。游标直接用 Telegram 的 update_id;Engine 落库后才推进,
 * 进程重启时从 readPositions 续 offset,已收到的事件不重放。
 */
class TelegramSource(
    private val config: TelegramConfig,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Source {
    override val kind = "telegram"

    override suspend fun poll(state: SuperagentState): List<Trigger> {
        val cursor = state.readPositions[config.instance]?.toLongOrNull()
        val offset = (cursor?.plus(1) ?: 0L).takeIf { it > 0 }
        val url = buildString {
            append(config.baseUrl.trimEnd('/'))
            append("/bot"); append(config.token)
            append("/getUpdates?timeout=25&allowed_updates=%5B%22message%22%2C%22channel_post%22%5D")
            offset?.let { append("&offset="); append(it) }
        }
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "telegram http ${response.code}" }
            response.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(body).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        return root["result"]?.jsonArray?.mapNotNull(::trigger).orEmpty()
    }

    private fun trigger(update: kotlinx.serialization.json.JsonElement): Trigger? {
        val o = update.jsonObject
        val id = o["update_id"]?.jsonPrimitive?.longOrNull ?: return null
        val message = (o["message"] ?: o["channel_post"])?.jsonObject ?: return null
        val chat = message["chat"]?.jsonObject ?: return null
        val chatId = chat["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val from = message["from"]?.jsonObject
        val senderId = from?.get("id")?.jsonPrimitive?.contentOrNull
        val username = from?.get("username")?.jsonPrimitive?.contentOrNull
        val actor = username?.let { "@$it" } ?: senderId ?: return null
        val isPerson = from?.get("is_bot")?.jsonPrimitive?.booleanOrNull != true
        val text = message["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val private = chat["type"]?.jsonPrimitive?.contentOrNull == "private"
        val mention = hasMention(message) || text.startsWith("/task")
        val addressed = private || when (config.groupPolicy) {
            TelegramGroupPolicy.AllowAll -> true
            TelegramGroupPolicy.MentionOnly -> mention
        }
        return Trigger(
            id = "telegram:${config.instance}:$id",
            source = SourceRef(kind, config.instance),
            externalId = id.toString(),
            threadKey = chatId,
            actor = actor,
            isPerson = isPerson,
            addressed = addressed,
            text = text,
            atMs = (message["date"]?.jsonPrimitive?.longOrNull ?: 0L) * 1_000,
        )
    }

    private fun hasMention(message: JsonObject): Boolean {
        val entities = message["entities"]?.jsonArray ?: message["caption_entities"]?.jsonArray ?: return false
        return entities.any { entity ->
            val type = entity.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            type == "mention" || type == "text_mention" || type == "bot_command"
        }
    }
}
