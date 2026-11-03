package com.wangzan101.mika.core.superagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class TelegramReplyConfig(
    val instance: String,
    val token: String,
    val baseUrl: String = "https://api.telegram.org",
)

/** Telegram 结果回发;凭据是 Telegram message_id,由调用方放进自己的送达账本。 */
class TelegramReplyChannel(
    private val config: TelegramReplyConfig,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ReplyChannel {
    override suspend fun send(envelope: ReplyEnvelope): String {
        val payload = json.encodeToString(
            kotlinx.serialization.json.buildJsonObject {
                put("chat_id", kotlinx.serialization.json.JsonPrimitive(envelope.threadKey))
                put("text", kotlinx.serialization.json.JsonPrimitive(envelope.text.take(LIMIT)))
                put("disable_web_page_preview", kotlinx.serialization.json.JsonPrimitive(true))
            },
        )
        val url = "${config.baseUrl.trimEnd('/')}/bot${config.token}/sendMessage"
        val response = http.newCall(
            Request.Builder().url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("telegram http ${response.code}")
            body
        }
        val root = json.parseToJsonElement(response).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) throw IllegalStateException("telegram reply rejected")
        val messageId = root["result"]?.jsonObject?.get("message_id")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("telegram reply missing receipt")
        return messageId
    }

    companion object {
        const val LIMIT = 4_096
    }
}
