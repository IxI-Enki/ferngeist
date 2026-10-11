package com.tamimarafat.ferngeist.acp.bridge.launcher

/**
 * One authentication method an agent offers, flattened so a feature dialog
 * never has to see an ACP protocol type.
 */
data class AgentAuthMethod(
    val id: String,
    val name: String,
    val description: String?,
    val type: String,
    val envVars: List<AgentAuthEnvVar> = emptyList(),
    val link: String? = null,
    val args: List<String> = emptyList(),
)

/** An environment variable an [AgentAuthMethod] declares. */
data class AgentAuthEnvVar(
    val name: String,
    val label: String?,
    val secret: Boolean,
    val optional: Boolean,
)

/**
 * What a launch produced: connect, gateway handoff and the ACP initialize
 * handshake folded into the three facts a server list acts on.
 */
sealed interface AgentLaunchOutcome {
    /**
     * The agent is connected and initialized. The verification transport has
     * already hung up: the gateway allows one live attach per session, and the
     * chat that opens next owns that slot.
     */
    data class Ready(
        val agentName: String,
        val capabilities: List<String>,
        /** Method the agent authenticated with unprompted, to save as preferred. */
        val authenticatedMethodId: String? = null,
    ) : AgentLaunchOutcome

    /** The agent wants authentication before it will work. */
    data class AuthenticationRequired(
        val agentName: String,
        val capabilities: List<String>,
        val methods: List<AgentAuthMethod>,
        /** Stored env values for [methods], to seed the prompt. */
        val persistedEnvValues: Map<String, String>,
        val errorMessage: String? = null,
        /** True when the agent runs behind a gateway runtime, which takes env vars by restarting. */
        val gatewayBacked: Boolean = false,
    ) : AgentLaunchOutcome

    /**
     * No agent came up: the gateway runtime could not be started, the transport
     * could not connect, or initialize failed after it had. [message] is the
     * user-facing text — a gateway failure's own message, the transport
     * diagnostic, or the short initialize failure (the long one is logged).
     */
    data class Failed(
        val message: String,
    ) : AgentLaunchOutcome
}

/** What running an authentication method produced. */
sealed interface AgentAuthOutcome {
    data object Success : AgentAuthOutcome

    /**
     * Authentication did not complete. [message] belongs on the auth prompt;
     * [connectionError] is the connection banner's message when the failure
     * happened while re-establishing the transport.
     */
    data class Failure(
        val message: String,
        val connectionError: String? = null,
    ) : AgentAuthOutcome
}

/** Transport events worth reacting to while a launch or an auth is in flight. */
sealed interface AgentLaunchEvent {
    /** The agent answered initialize — often a reconnect's, not the caller's own request. */
    data class Initialized(
        val agentName: String,
    ) : AgentLaunchEvent

    /** A failure outside any call the caller made: a dropped socket, a failed reconnect. */
    data class Failed(
        val message: String,
    ) : AgentLaunchEvent
}
