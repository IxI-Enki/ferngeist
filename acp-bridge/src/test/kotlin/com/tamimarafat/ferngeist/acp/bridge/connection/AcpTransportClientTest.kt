package com.tamimarafat.ferngeist.acp.bridge.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpTransportClientTest {

    @Test
    fun `resilient config detects session mode`() {
        val config = AcpConnectionConfig(
            host = "192.168.1.5:5788",
            sessionId = "sess-42",
            attachToken = "at-1",
        )
        assertTrue(config.isResilientSession)
    }

    @Test
    fun `non-resilient config returns false`() {
        val config = AcpConnectionConfig(
            host = "192.168.1.5:5788",
        )
        assertFalse(config.isResilientSession)
    }

    @Test
    fun `resilient config isResilient is true even without attachToken`() {
        val config = AcpConnectionConfig(
            host = "192.168.1.5:5788",
            sessionId = "sess-42",
        )
        assertTrue(config.isResilientSession)
    }
}
