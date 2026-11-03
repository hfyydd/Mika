package com.wangzan101.mika.core.superagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class PolicyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `policy store round trips a versioned policy`() {
        val path: Path = folder.newFile("policy.json").toPath()
        val store = FilePolicyStore(path)
        val policy = PolicyEditor.addActor(Policy(version = DEFAULT_POLICY_VERSION), "telegram:main", "@alice")

        store.save(policy)

        assertEquals(policy, store.load())
        assertEquals("@alice", store.load().allowedActors.getValue("telegram:main").single())
        assertTrue(policy.version.startsWith("superagent#"))
    }

    @Test
    fun `missing or corrupt policy falls back to a safe closed policy`() {
        val missing = FilePolicyStore(folder.newFile("missing.json").toPath())
        assertEquals(AcceptMode.Off, missing.load().acceptMode)

        val corrupt = folder.newFile("corrupt.json")
        corrupt.writeText("{not-json}")
        assertEquals(AcceptMode.Off, FilePolicyStore(corrupt.toPath()).load().acceptMode)
    }
}
