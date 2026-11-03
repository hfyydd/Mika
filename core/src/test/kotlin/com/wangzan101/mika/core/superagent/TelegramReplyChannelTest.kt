package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramReplyChannelTest {
    private lateinit var server: MockWebServer
    private lateinit var channel: TelegramReplyChannel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        channel = TelegramReplyChannel(
            TelegramReplyConfig("main", "token", baseUrl = server.url("/").toString()),
            OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts to source thread and returns message id receipt`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":42}}"""))
        val envelope = ReplyEnvelope(SourceRef("telegram", "main"), "-100", "t1", "done", deliverHere = true)
        assertEquals("42", channel.send(envelope))
        server.takeRequest().let {
            assertEquals("/bottoken/sendMessage", it.requestUrl?.encodedPath)
            val body = it.body.readUtf8()
            assertTrue(body.contains(""""chat_id":"-100""""))
            assertTrue(body.contains("done"))
        }
    }
}
