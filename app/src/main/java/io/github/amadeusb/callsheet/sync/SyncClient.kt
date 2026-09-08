package io.github.amadeusb.callsheet.sync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What went wrong. AUTH and NOT_FOUND are misconfiguration and worth showing
 * to the user directly. RATE_LIMITED and TOO_LARGE are their own kinds
 * because the engine reacts to them differently — see [SyncEngine].
 */
enum class FailureKind { NETWORK, AUTH, NOT_FOUND, RATE_LIMITED, TOO_LARGE, SERVER }

class HttpFailure(val kind: FailureKind, message: String) : IOException(message)

interface Transport {
    fun post(payload: JSONObject): JSONObject
}

/** One request, one response. No retries here — the engine decides that. */
class SyncClient(private val url: String, private val token: String) : Transport {

    override fun post(payload: JSONObject): JSONObject {
        val connection = (URL("$url/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            when (val code = connection.responseCode) {
                200 -> Unit
                401, 403 -> throw HttpFailure(FailureKind.AUTH, "Der Server hat den Zugang abgelehnt.")
                404 -> throw HttpFailure(FailureKind.NOT_FOUND, "Unter dieser Adresse antwortet kein Callsheet-Server.")
                413 -> throw HttpFailure(FailureKind.TOO_LARGE, "Der Server hat die Anfrage als zu groß abgelehnt.")
                429 -> throw HttpFailure(FailureKind.RATE_LIMITED, "Der Server bittet um eine Pause.")
                else -> throw HttpFailure(FailureKind.SERVER, "Der Server meldet Fehler $code.")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
