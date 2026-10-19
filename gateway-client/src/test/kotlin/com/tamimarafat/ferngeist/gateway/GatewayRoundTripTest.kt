package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Round trips a launch must not spend: a protocol probe it already passed. */
class GatewayRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `verifyProtocol asks the gateway once while the pass is fresh`() =
        runTest {
            val requested = mutableListOf<String>()
            val repo = GatewayRepositoryImpl(HttpClient(statusEngine(requested, "v2")), json)

            repo.verifyProtocol("http", "gw.local:5788")
            repo.verifyProtocol("http", "gw.local:5788")
            repo.verifyProtocol("http", "other.local:5788")

            assertEquals(listOf("/v1/status", "/v1/status"), requested)
        }

    @Test
    fun `verifyProtocol does not remember a rejected protocol`() =
        runTest {
            val requested = mutableListOf<String>()
            val repo = GatewayRepositoryImpl(HttpClient(statusEngine(requested, "v9")), json)

            repeat(2) {
                try {
                    repo.verifyProtocol("http", "gw.local:5788")
                    fail("expected a protocol mismatch")
                } catch (_: IllegalStateException) {
                    // expected
                }
            }

            assertEquals(2, requested.size)
        }

    private fun statusEngine(
        requested: MutableList<String>,
        protocol: String,
    ) = MockEngine { request ->
        requested += request.url.encodedPath
        respond(
            """{"name":"gw","version":"dev","protocolVersion":"$protocol","remote":{"configured":true}}""",
            HttpStatusCode.OK,
            jsonHeaders,
        )
    }
}
