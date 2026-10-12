package com.tamimarafat.ferngeist.feature.chat.ui

import org.junit.Assert.assertEquals
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
}
