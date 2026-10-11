package com.tamimarafat.ferngeist.acp.bridge.launcher

import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.buildEnvPayload
import com.tamimarafat.ferngeist.acp.bridge.connection.buildGatewayLaunchContext
import com.tamimarafat.ferngeist.acp.bridge.connection.buildInitializeFailureMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.displayLabels
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.loadPersistedEnvValues
import com.tamimarafat.ferngeist.acp.bridge.connection.logConnectionFailure
import com.tamimarafat.ferngeist.acp.bridge.connection.persistEnvValues
import com.tamimarafat.ferngeist.acp.bridge.connection.shortInitializeFailureMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.toConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.facade.mapConnectionState
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValuesUnreadableException
import com.tamimarafat.ferngeist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The auth-method type whose values only reach the agent through a runtime restart. */
private const val ENV_AUTH_TYPE = "env"

/**
 * The server list's whole gateway into ACP: it owns the verification transport,
 * the gateway handoff that provisions a runtime for a gateway agent, the
 * initialize handshake, and authentication — including the restart-and-reattach
 * dance an environment-variable method needs on a gateway agent.
 *
 * A screen binds one [session]: the hub creates the transport for that screen's
 * scope, so it dies with the screen and still counts as a live connection for
 * the process-wide aggregates. It also fronts the one listing call the server
 * list needs before it navigates — [prewarmSessions] — so the feature still
 * never touches a transport.
 */
class AgentLauncher(
    private val hub: ChatConnectionHub,
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val authEnvValueStore: AuthEnvValueStore,
    private val sessionCatalog: SessionCatalog,
) {
    /**
     * Server ids whose agent holds a live hub-tracked transport. The card dot
     * tracks this, not the verification socket, which hangs up after every tap.
     */
    val liveServerIds: StateFlow<Set<String>> get() = hub.warmServers

    /**
     * True when a hub-tracked chat already owns this server's transport. A fresh
     * start+connect would steal the gateway's single-attach slot and evict it.
     */
    fun hasWarmChat(serverId: String): Boolean = hub.warmManagerFor(serverId) != null

    /**
     * Warms the session cache for [serverId] through the catalog's listing seam
     * before the server list navigates, so the session list paints rows instantly
     * instead of showing a second spinner. Best-effort: any failure (including
     * auth challenges) still navigates — the session screen surfaces listing
     * failures exactly as it does today.
     */
    suspend fun prewarmSessions(serverId: String) {
        runCatching {
            sessionCatalog.listSessions(serverId, cwd = null)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    /** Binds a verification transport to [scope]; the hub releases it with the scope. */
    fun session(scope: CoroutineScope): AgentLaunchSession =
        AgentLaunchSession(
            connection = hub.createBrowserManager(scope),
            hangUp = hub::hangUpBrowserTransport,
            scope = scope,
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            launchableTargetRepository = launchableTargetRepository,
            authEnvValueStore = authEnvValueStore,
        )
}

/**
 * One screen's verification transport and every launch operation over it.
 */
@Suppress("TooManyFunctions")
class AgentLaunchSession internal constructor(
    private val connection: AcpConnection,
    private val hangUp: (AcpConnection, Boolean) -> Unit,
    scope: CoroutineScope,
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val authEnvValueStore: AuthEnvValueStore,
) {
    /** The transport's lifecycle, in the feature layer's own vocabulary. */
    val connectionState: StateFlow<ChatConnectionState> =
        connection.connectionState
            .map(::mapConnectionState)
            .stateIn(scope, SharingStarted.Eagerly, ChatConnectionState.Disconnected)

    /** Transport failures and agent initializations that arrive outside a call; see [AgentLaunchEvent]. */
    val events: Flow<AgentLaunchEvent> = connection.events.mapNotNull(::toLaunchEvent)

    val isConnected: Boolean get() = connection.isConnected

    /** Gateway runtime and source the last launch provisioned, for the auth restart. */
    private var pendingGateway: PendingGateway? = null

    /** Methods the last initialize reported; authentication reads the method's kind here. */
    private var authMethods: List<AcpAuthMethodInfo> = emptyList()

    /**
     * Connects and initializes [server]. A gateway agent first gets a runtime and
     * a handoff from the gateway; a manual server connects to its own endpoint.
     */
    suspend fun launch(server: LaunchableTarget): AgentLaunchOutcome {
        pendingGateway = null
        val config =
            when (server) {
                is LaunchableTarget.Manual -> server.toConnectionConfig()

                is LaunchableTarget.GatewayAgent -> {
                    val context =
                        buildGatewayLaunchContext(gatewayRepository, gatewaySourceRepository, server)
                            .getOrElse { error ->
                                return AgentLaunchOutcome.Failed(error.message ?: "Failed to launch ${server.name}")
                            }
                    pendingGateway = PendingGateway(source = context.gatewaySource, runtimeId = context.runtimeId)
                    context.config
                }
            }

        val initialized = withContext(Dispatchers.IO) { connection.connectAndInitialize(config) }
        if (initialized == null) return connectFailure(server)

        authMethods = initialized.authMethods
        return when (initialized) {
            is AcpInitializeResult.Ready -> {
                hangUp(connection, false)
                pendingGateway = null
                AgentLaunchOutcome.Ready(
                    agentName = initialized.agentInfo.name,
                    capabilities = initialized.agentCapabilities.displayLabels(),
                    authenticatedMethodId = initialized.authenticatedMethodId,
                )
            }

            is AcpInitializeResult.AuthenticationRequired ->
                AgentLaunchOutcome.AuthenticationRequired(
                    agentName = initialized.agentInfo.name,
                    capabilities = initialized.agentCapabilities.displayLabels(),
                    methods = initialized.authMethods.map { it.toAgentAuthMethod() },
                    persistedEnvValues =
                        loadPersistedEnvValues(authEnvValueStore, server.id, initialized.authMethods),
                    errorMessage = initialized.authErrorMessage,
                    gatewayBacked = pendingGateway != null,
                )
        }
    }

    /**
     * Runs [methodId] against the live transport. A gateway agent's
     * environment-variable method cannot be authenticated in place — the runtime
     * must be restarted with the values, then reattached and initialized.
     */
    suspend fun authenticate(
        serverId: String,
        methodId: String,
        envValues: Map<String, String> = emptyMap(),
    ): AgentAuthOutcome {
        val gateway = pendingGateway
        val method = authMethods.firstOrNull { it.id == methodId }
        if (gateway == null || method == null || method.type != ENV_AUTH_TYPE) {
            return withContext(Dispatchers.IO) { connection.authenticate(methodId) }.toAuthOutcome()
        }
        return authenticateGatewayEnv(serverId, method, envValues, gateway)
    }

    /** Hangs the verification transport up and forgets the runtime it was using. */
    fun disconnect() {
        pendingGateway = null
        hangUp(connection, false)
    }

    /** Surfaces whatever stopped a connect or an initialize, then logs the long form. */
    private suspend fun connectFailure(server: LaunchableTarget): AgentLaunchOutcome {
        if (!connection.isConnected) {
            val error = recentConnectError("Failed to connect to ${server.name}")
            // The gateway launched a session before the attach failed.
            hangUp(connection, false)
            return AgentLaunchOutcome.Failed(error)
        }
        val detail =
            buildInitializeFailureMessage(
                connectionManager = connection,
                gatewayRepository = gatewayRepository,
                gatewaySourceRepository = gatewaySourceRepository,
                server = server,
                gatewaySource = pendingGateway?.source,
                runtimeId = pendingGateway?.runtimeId,
            )
        logConnectionFailure(server, "initialize", detail)
        hangUp(connection, false)
        return AgentLaunchOutcome.Failed(shortInitializeFailureMessage(server))
    }

    /** The last recorded connect/connection diagnostic, or [fallback] when there is none. */
    private fun recentConnectError(fallback: String): String =
        connection.diagnostics.value.recentErrors
            .lastOrNull { entry -> entry.source == "connect" || entry.source == "connection" }
            ?.message
            ?: fallback

    // Each failure path exits early — a missing runtime, no auth context, an unreadable
    // stored copy, a failed restart, a failed reconnect — so the exit count is inherent to
    // the sequence, not a sign of nested conditions that want flattening.
    @Suppress("ReturnCount")
    private suspend fun authenticateGatewayEnv(
        serverId: String,
        method: AcpAuthMethodInfo,
        envValues: Map<String, String>,
        gateway: PendingGateway,
    ): AgentAuthOutcome {
        val server =
            withContext(Dispatchers.IO) { launchableTargetRepository.getTarget(serverId) }
                ?: return AgentAuthOutcome.Failure("Server was removed before authentication could complete.")
        val gatewayTarget =
            server as? LaunchableTarget.GatewayAgent
                ?: return AgentAuthOutcome.Failure("Gateway was not found for ${server.name}.")
        val source =
            when (val refreshed = refreshedGatewaySource(gatewayTarget)) {
                is AuthSource.Ready -> refreshed.source
                is AuthSource.Failed -> return AgentAuthOutcome.Failure(refreshed.message)
            }

        val envPayload = buildEnvPayload(method, envValues)
        try {
            persistEnvValues(authEnvValueStore, server.id, method, envValues)
        } catch (error: AuthEnvValuesUnreadableException) {
            // The stored copy decrypts but cannot be parsed, so this write's base was an
            // empty map. Continuing would destroy secrets the user still has; the stored
            // blob is left untouched for them to replace.
            return AgentAuthOutcome.Failure(error.message ?: "Saved environment values could not be read.")
        }

        val restart =
            runCatching {
                withContext(Dispatchers.IO) {
                    gatewayRepository.restartRuntime(
                        scheme = source.scheme,
                        host = source.host,
                        gatewayCredential = source.gatewayCredential,
                        runtimeId = gateway.runtimeId,
                        envVars = envPayload,
                    )
                }
            }.getOrElse { error ->
                return AgentAuthOutcome.Failure(error.message ?: "Failed to restart ${server.name}.")
            }
        // Restart launches a new runtime; later retries and diagnostics must target it.
        pendingGateway = PendingGateway(source = source, runtimeId = restart.runtimeId)

        return reconnectWithHandoff(server, source, method, restart)
    }

    /** Reconnects onto the restarted runtime and completes initialize + authenticate. */
    private suspend fun reconnectWithHandoff(
        server: LaunchableTarget,
        source: GatewaySource,
        method: AcpAuthMethodInfo,
        restart: GatewayConnectResponse,
    ): AgentAuthOutcome {
        connection.disconnect()
        val reconnected =
            withContext(Dispatchers.IO) {
                connection.connect(
                    AcpConnectionConfig.fromGatewayHandoff(
                        gatewaySource = source,
                        handoff = restart,
                        authMethodId = method.id,
                        // This reconnect never published a display name.
                        displayName = null,
                    ),
                )
            }
        if (!reconnected) {
            val message = recentConnectError("Failed to reconnect to ${server.name}")
            return AgentAuthOutcome.Failure(message, connectionError = message)
        }

        val initialized = withContext(Dispatchers.IO) { connection.initialize() }
        if (initialized == null) {
            val detail =
                buildInitializeFailureMessage(
                    connectionManager = connection,
                    gatewayRepository = gatewayRepository,
                    gatewaySourceRepository = gatewaySourceRepository,
                    server = server,
                    gatewaySource = source,
                    runtimeId = restart.runtimeId,
                )
            return AgentAuthOutcome.Failure(detail, connectionError = shortInitializeFailureMessage(server))
        }
        authMethods = initialized.authMethods
        return withContext(Dispatchers.IO) { connection.authenticate(method.id) }.toAuthOutcome()
    }

    /**
     * Refreshes the gateway credential, deleting it when it has expired so the
     * next launch surfaces the pairing flow instead of opaque 401s.
     */
    private suspend fun refreshedGatewaySource(gatewayTarget: LaunchableTarget.GatewayAgent): AuthSource =
        try {
            val source =
                withContext(Dispatchers.IO) {
                    refreshGatewaySourceIfNeeded(
                        gatewayTarget.gatewaySource,
                        gatewayRepository,
                        gatewaySourceRepository,
                    )
                }
            when {
                source.gatewayCredential.isBlank() -> AuthSource.Failed("Gateway is not paired.")
                else -> AuthSource.Ready(source)
            }
        } catch (_: GatewayCredentialExpiredException) {
            withContext(Dispatchers.IO) {
                gatewaySourceRepository.deleteGateway(gatewayTarget.gatewaySource.id)
            }
            AuthSource.Failed("Gateway credential expired. Please pair this gateway again.")
        }

    /** The gateway runtime the last launch left up, and the source it came from. */
    private data class PendingGateway(
        val source: GatewaySource,
        val runtimeId: String,
    )

    /** A refreshed gateway credential, or the message to show when it could not be renewed. */
    private sealed interface AuthSource {
        data class Ready(
            val source: GatewaySource,
        ) : AuthSource

        data class Failed(
            val message: String,
        ) : AuthSource
    }
}

private fun AcpAuthenticateResult.toAuthOutcome(): AgentAuthOutcome =
    when (this) {
        AcpAuthenticateResult.Success -> AgentAuthOutcome.Success
        is AcpAuthenticateResult.Failure -> AgentAuthOutcome.Failure(message)
    }

private fun AcpAuthMethodInfo.toAgentAuthMethod(): AgentAuthMethod =
    AgentAuthMethod(
        id = id,
        name = name,
        description = description,
        type = type,
        envVars =
            envVars.map {
                AgentAuthEnvVar(name = it.name, label = it.label, secret = it.secret, optional = it.optional)
            },
        link = link,
        args = args,
    )

private fun toLaunchEvent(event: AcpManagerEvent): AgentLaunchEvent? =
    when (event) {
        is AcpManagerEvent.Initialized -> AgentLaunchEvent.Initialized(event.result.agentInfo.name)
        is AcpManagerEvent.Error -> AgentLaunchEvent.Failed(formatAcpErrorMessage(event.throwable, "Unknown error"))
        // The verification transport never resumes a session; nothing to do here.
        is AcpManagerEvent.SessionResumeRejected,
        is AcpManagerEvent.Connected,
        is AcpManagerEvent.Disconnected,
        is AcpManagerEvent.Authenticated,
        -> null
    }
