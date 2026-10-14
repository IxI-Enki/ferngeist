package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.lazy.LazyListState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The space reserved under a just-sent prompt so it can sit at the top of the viewport. */
class RunwayTest {
    @Test
    fun `a short reply is stretched to the end of the viewport`() {
        // Prompt at 100, reply at 300, viewport ends at 1000, 40px below the reply: 760px of reply.
        assertEquals(760, runwayPx(promptOffset = 100, rowOffset = 300, viewportEndOffset = 1000, reservedPx = 40))
    }

    @Test
    fun `a turn taller than the viewport reserves nothing`() {
        assertEquals(0, runwayPx(promptOffset = 0, rowOffset = 1200, viewportEndOffset = 1000, reservedPx = 40))
    }

    @Test
    fun `a chat's first send puts the runway under the new prompt in the frame it appears`() {
        val old = ChatMessage(id = "old", role = ChatMessage.Role.USER)
        val reply = ChatMessage(id = "reply", role = ChatMessage.Role.ASSISTANT)
        val sent = ChatMessage(id = "sent", role = ChatMessage.Role.USER)
        val scroll = ChatScrollHandle(LazyListState())
        scroll.onMessages(listOf(old, reply))
        assertNull(scroll.runwayPrompt(listOf(old, reply)))

        scroll.onSendMessage()
        // Before the prompt is echoed back, the old one must not take the reserve.
        assertNull(scroll.runwayPrompt(listOf(old, reply)))
        assertEquals(sent, scroll.runwayPrompt(listOf(old, reply, sent)))
    }
}
