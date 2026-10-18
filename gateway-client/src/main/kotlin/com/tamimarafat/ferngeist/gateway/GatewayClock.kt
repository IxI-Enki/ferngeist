package com.tamimarafat.ferngeist.gateway

import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val MILLISECONDS_PER_SECOND = 1000L

/**
 * Remembers, per gateway host, how far the device clock deviates from the gateway's.
 *
 * In memory only on purpose. A persisted offset would be wrong the moment the user
 * fixes their clock, and relearning it costs at most one rejected request per launch.
 * The offset is recorded whenever the client sees a gateway clock: the `serverTime`
 * of `/v1/status`, that of an auth rejection body, or — for gateways that predate the
 * field — the HTTP `Date` header of a rejection.
 */
@Singleton
class GatewayClock
    @Inject
    constructor() {
        private val offsets = ConcurrentHashMap<String, Long>()

        /** Seconds to add to the device clock to match [host]'s gateway; 0 until one was seen. */
        fun offsetFor(host: String): Long = offsets[key(host)] ?: 0L

        /**
         * The signed `now` for [host]: the device clock moved by the learned offset, so
         * a proof timestamp built from it lands inside the gateway's ±5-minute window
         * even when the device clock is off by much more. 0 offset before anything was
         * learned, which is correct while the clocks agree.
         */
        fun nowSeconds(host: String): Long = System.currentTimeMillis() / MILLISECONDS_PER_SECOND + offsetFor(host)

        /**
         * Learns [host]'s offset from a gateway clock. [serverTimeEpochSeconds] is what
         * the gateway believed when it answered; [localNowEpochSeconds] is what the
         * device believed while reading the answer. Round-trip time is ignored: the
         * sample is taken when the answer arrives, and half an RTT is negligible
         * against the ±5-minute window.
         */
        fun recordServerTime(
            host: String,
            serverTimeEpochSeconds: Long,
            localNowEpochSeconds: Long = System.currentTimeMillis() / MILLISECONDS_PER_SECOND,
        ) {
            if (serverTimeEpochSeconds > 0) {
                offsets[key(host)] = serverTimeEpochSeconds - localNowEpochSeconds
            }
        }

        private fun key(host: String): String = normalizeGatewayHost(host)
    }

/**
 * The key gateway-wide in-memory caches (clock offsets) are stored under: one identity
 * per endpoint, with scheme prefixes and trailing slashes dropped.
 */
internal fun normalizeGatewayHost(host: String): String =
    host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("ws://")
        .removePrefix("wss://")
        .trimEnd('/')

/** Parses an RFC 3339 timestamp (`serverTime`) into epoch seconds, or null when it is not one. */
internal fun parseGatewayServerTimeEpochSeconds(value: String?): Long? =
    value
        ?.trim()
        ?.ifBlank { null }
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant().epochSecond }.getOrNull() }

/** Parses an HTTP `Date` header (`Sun, 19 Oct 2026 10:00:00 GMT`) into epoch seconds, or null. */
internal fun parseGatewayHttpDateEpochSeconds(value: String?): Long? =
    value
        ?.trim()
        ?.ifBlank { null }
        ?.let {
            runCatching {
                ZonedDateTime
                    .parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .epochSecond
            }.getOrNull()
        }
