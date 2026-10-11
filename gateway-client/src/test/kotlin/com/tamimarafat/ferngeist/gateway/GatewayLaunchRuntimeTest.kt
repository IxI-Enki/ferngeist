package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gateway launch is one protocol-v2 connect call: the gateway spawns (or reuses)
 * the agent's runtime and hands back the session to attach to. There is no start
 * stage, no runtime pick, and no client-side retry — the gateway owns the agent
 * process lifetime, and a failed connect surfaces the gateway's own error.
 */
class GatewayLaunchRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a launch connects the agent and returns the v2 handoff`() =
        runTest {
            val requested = mutableListOf<String>()
            val result =
                launchGatewayRuntime(
                    gatewayRepository = repo(requested),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                )

            val launched = result.getOrThrow()
            assertEquals(SOURCE, launched.gatewaySource)
            assertEquals("runtime-1", launched.handoff.runtimeId)
            assertEquals("sess-1", launched.handoff.sessionId)
            assertEquals("attach-1", launched.handoff.attachToken)
            assertEquals(listOf("POST /v1/agents/pi-acp/connect"), requested)
        }

    @Test
    fun `restartRuntime posts to the runtime restart endpoint and parses the v2 response`() =
        runTest {
            val requested = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requested += "${request.method.value} ${request.url.encodedPath}"
                    respond("""{"sessionId":"sess-7","attachToken":"at-7"}""", HttpStatusCode.OK)
                }
            val repository = GatewayRepositoryImpl(HttpClient(engine), json)

            val restarted = repository.restartRuntime("http", "gw.local:5788", "cred", "runtime-3", emptyMap())

            assertEquals("sess-7", restarted.sessionId)
            assertEquals("at-7", restarted.attachToken)
            assertEquals(listOf("POST /v1/runtimes/runtime-3/restart"), requested)
        }

    @Test
    fun `a connect failure surfaces the gateway error text verbatim`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"error":"agent process exited before the runtime was ready"}""",
                        HttpStatusCode.BadGateway,
                    )
                }
            val result =
                launchGatewayRuntime(
                    gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                    gatewaySourceRepository = FakeGatewaySources(),
                    gatewaySource = SOURCE,
                    agentId = "pi-acp",
                )

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                "the agent's exit error must reach the caller: $message",
                message.contains("agent process exited before the runtime was ready"),
            )
            assertFalse("a failed launch must not report success", result.isSuccess)
        }

    private fun repo(requested: MutableList<String>): GatewayRepositoryImpl {
        val engine =
            MockEngine { request ->
                requested += "${request.method.value} ${request.url.encodedPath}"
                respond(CONNECT_BODY, HttpStatusCode.OK)
            }
        return GatewayRepositoryImpl(HttpClient(engine), json)
    }

    private class FakeGatewaySources : GatewaySourceRepository {
        override fun getGateways(): Flow<List<GatewaySource>> = flowOf(emptyList())

        override suspend fun addGateway(gateway: GatewaySource) = Unit

        override suspend fun updateGateway(gateway: GatewaySource) = Unit

        override suspend fun deleteGateway(id: String) = Unit

        override suspend fun getGateway(id: String): GatewaySource? = null

        override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
    }

    private companion object {
        val SOURCE =
            GatewaySource(
                id = "source-1",
                name = "gw",
                scheme = "http",
                host = "gw.local:5788",
                gatewayCredential = "cred",
            )

        val CONNECT_BODY =
            """
            {"runtimeId":"runtime-1","sessionId":"sess-1","attachToken":"attach-1",
             "websocketUrl":"ws://gw.local:5788/v1/runtimes/runtime-1/ws"}
            """.trimIndent()
    }
}
