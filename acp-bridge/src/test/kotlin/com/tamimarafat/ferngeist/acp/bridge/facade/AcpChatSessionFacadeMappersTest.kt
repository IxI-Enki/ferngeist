package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway working tree's shape, mapped into the chat domain the feature layer holds. */
class AcpChatSessionFacadeMappersTest {
    @Test
    fun `git status carries branch, ahead-behind, and every changed file field`() {
        val mapped =
            GatewayGitStatus(
                branch = "main",
                ahead = 2,
                behind = 1,
                changed =
                    listOf(
                        GatewayChangedFile(
                            path = "src/Main.kt",
                            status = "M",
                            added = 7,
                            removed = 3,
                            binary = true,
                            isDir = true,
                        ),
                    ),
            ).toChatGitStatus()

        assertEquals("main", mapped.branch)
        assertEquals(2, mapped.ahead)
        assertEquals(1, mapped.behind)
        val file = mapped.changed.single()
        assertEquals("src/Main.kt", file.path)
        assertEquals("M", file.status)
        assertEquals(7, file.added)
        assertEquals(3, file.removed)
        assertTrue(file.binary)
        assertTrue(file.isDir)
    }

    @Test
    fun `an empty status maps to empty defaults`() {
        val mapped = GatewayGitStatus().toChatGitStatus()

        assertEquals("", mapped.branch)
        assertEquals(0, mapped.ahead)
        assertEquals(0, mapped.behind)
        assertTrue(mapped.changed.isEmpty())
    }
}
