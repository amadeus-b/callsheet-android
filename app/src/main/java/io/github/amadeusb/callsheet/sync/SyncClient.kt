package io.github.amadeusb.callsheet.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What went wrong. AUTH and NOT_FOUND are misconfiguration and worth showing
 * to the user directly. RATE_LIMITED and TOO_LARGE are their own kinds
 * because the engine reacts to them differently — see [SyncEngine].
 * BAD_RESPONSE covers a 200 whose body a broken server or an intercepting
 * proxy made unreadable as the JSON the contract promises. UNKNOWN covers
 * anything the engine did not expect at all — a local database error, for
 * instance — so that it becomes a visible failure instead of taking the app
 * down.
 */
enum class FailureKind { NETWORK, AUTH, NOT_FOUND, RATE_LIMITED, TOO_LARGE, BAD_RESPONSE, SERVER, UNKNOWN }

class HttpFailure(val kind: FailureKind, message: String) : IOException(message)

interface Transport {
    fun post(payload: JSONObject): JSONObject
}

/**
 * Whether a server address is even worth trying. Android blocks cleartext
 * HTTP from API 28 onward (this app has no network security config opting
 * back in), so an `http://` address would otherwise fail with a network
 * error that reads like a connectivity problem rather than the configuration
 * mistake it is. Blank is accepted — that is how synchronisation gets turned
 * off.
 */
fun isAcceptableServerAddress(url: String): Boolean =
    url.isBlank() || url.startsWith("https://")

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
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // A 200 with a body that is not the promised JSON — a broken
            // server or a proxy's error page — must become a visible
            // failure, not an uncaught org.json.JSONException.
            return try {
                JSONObject(body)
            } catch (malformed: JSONException) {
                throw HttpFailure(FailureKind.BAD_RESPONSE, "Die Antwort des Servers ließ sich nicht lesen.")
            }
        } finally {
            connection.disconnect()
        }
    }
}
