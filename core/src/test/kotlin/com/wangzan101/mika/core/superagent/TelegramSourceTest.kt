package com.wangzan101.mika.core.superagent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: TelegramSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = TelegramSource(
            TelegramConfig("main", "token", botUsername = "mika_bot", baseUrl = server.url("/").toString()),
            OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `maps private and mentioned messages and advances cursor from engine state`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"ok":true,"result":[
              {"update_id":10,"message":{"message_id":1,"date":123,"chat":{"id":100,"type":"private"},
                 "from":{"id":7,"username":"alice","is_bot":false},"text":"please check"}},
              {"update_id":11,"message":{"message_id":2,"date":124,"chat":{"id":-200,"type":"supergroup"},
                 "from":{"id":8,"username":"bob","is_bot":false},
                 "entities":[{"offset":0,"length":5,"type":"mention"}],"text":"@mika_bot open it"}}
            ]}
        """.trimIndent()))
        val triggers = source.poll(SuperagentState())

        assertEquals(listOf("10", "11"), triggers.map { it.externalId })
        assertEquals("telegram:main:10", triggers[0].id)
        assertTrue(triggers[0].addressed)
        assertTrue(triggers[0].isPerson)
        assertEquals("@alice", triggers[0].actor)
        assertEquals(123_000, triggers[0].atMs)
        assertTrue(triggers[1].addressed)
        assertEquals("-200", triggers[1].threadKey)

        server.enqueue(MockResponse().setBody("""{"ok":true,"result":[]}"""))
        source.poll(SuperagentState(readPositions = mapOf("main" to "11")))
        server.takeRequest()
        val request = server.takeRequest()
        assertTrue(request.requestUrl?.queryParameter("offset") == "12")
    }

    @Test
    fun `group policy decides addressing without changing identity`() = runTest {
        val mentionOnly = TelegramSource(
            TelegramConfig("main", "token", groupPolicy = TelegramGroupPolicy.MentionOnly, baseUrl = server.url("/").toString()),
            OkHttpClient(),
        )
        server.enqueue(MockResponse().setBody("""
            {"ok":true,"result":[{"update_id":20,"message":{"date":1,"chat":{"id":-1,"type":"group"},
              "from":{"id":1,"username":"alice"},"text":"not aimed at agent"}}]}
        """.trimIndent()))
        assertFalse(mentionOnly.poll(SuperagentState()).single().addressed)
    }
}
