package io.github.amadeusb.callsheet.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What sending a mail through the server came to. */
sealed class MailResult {
    object Ok : MailResult()
    data class Failed(val message: String) : MailResult()
}

/**
 * Sends one mail through the server's `/send-mail` endpoint — the same
 * server, address and token synchronisation already uses.
 */
class MailClient(private val url: String, private val token: String) {

    fun send(to: List<String>, subject: String, text: String): MailResult {
        val connection = (URL("$url/send-mail").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val payload = JSONObject().apply {
                put("to", JSONArray(to))
                put("subject", subject)
                put("text", text)
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return if (code == 200) {
                MailResult.Ok
            } else {
                val message = try {
                    JSONObject(body).optString("error", null)
                } catch (malformed: JSONException) {
                    null
                }
                MailResult.Failed(message ?: "Der Server meldet Fehler $code.")
            }
        } catch (network: IOException) {
            return MailResult.Failed("Kein Netz.")
        } finally {
            connection.disconnect()
        }
    }
}
