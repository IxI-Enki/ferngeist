package com.tamimarafat.ferngeist.e2e

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The throwaway gateway's admin API, reached through `adb reverse tcp:5799 tcp:5799` (see run.ps1). */
object Gateway {
    private const val ADMIN = "http://localhost:5799/admin/v1"

    /** Pairing payloads are single use, so every pairing attempt needs its own. */
    fun newPairingPayload(): String = JSONObject(post("$ADMIN/pairings/start")).getString("payload")

    private fun post(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
