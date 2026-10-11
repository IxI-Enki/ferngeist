package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import java.net.URI

/**
 * The WebSocket URL to attach to: the gateway's advertised URL, or — when it advertises
 * a host the device cannot route to (0.0.0.0, loopback) — the same URL with the gateway
 * source's host substituted in.
 */
fun resolveGatewayWebSocketUrl(
    gatewaySource: GatewaySource,
    handoff: GatewayConnectResponse,
): String {
    val advertisedUrl = handoff.webSocketUrl.trim()
    val advertisedHost = runCatching { URI(advertisedUrl).host?.lowercase() }.getOrNull()
    if (advertisedHost != null && !isUnroutableGatewayHost(advertisedHost)) {
        return advertisedUrl
    }

    val socketScheme =
        when (gatewaySource.scheme.lowercase()) {
            "https", "wss" -> "wss"
            else -> "ws"
        }
    val advertised = runCatching { URI(advertisedUrl) }.getOrNull()
    val path = advertised?.rawPath.orEmpty()
    val query = advertised?.rawQuery?.let { "?$it" }.orEmpty()
    return "$socketScheme://${gatewaySource.host}$path$query"
}

fun isUnroutableGatewayHost(host: String): Boolean =
    host == "0.0.0.0" || host == "127.0.0.1" || host == "localhost" || host == "::1"
