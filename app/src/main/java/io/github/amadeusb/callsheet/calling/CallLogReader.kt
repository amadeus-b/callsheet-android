package io.github.amadeusb.callsheet.calling

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import io.github.amadeusb.callsheet.data.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reads the call duration out of the Android call log.
 *
 * Without the READ_CALL_LOG permission everything here returns null — the app
 * then has to carry on without automatic durations, neither crashing nor
 * blocking.
 */
object CallLogReader {

    /** How often to look for the entry before giving up. */
    private const val ATTEMPTS = 3

    /** Wait between two attempts. The log entry shows up with a delay. */
    private const val RETRY_DELAY_MS = 1_000L

    /** Whether the app is allowed to read the call log. */
    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Tolerant number comparison: Android logs the number as actually dialled,
     * and the notation regularly differs. "+493012345678" and "03012345678" are
     * the same number.
     */
    fun matches(logNumber: String?, wantedNumber: String): Boolean {
        val a = PhoneNumbers.comparableForm(logNumber) ?: return false
        val b = PhoneNumbers.comparableForm(wantedNumber) ?: return false
        return a == b
    }

    /**
     * Finds the most recent outgoing call to [number] that did not start before
     * [fromMillis] and returns its duration in seconds.
     *
     * Both conditions are mandatory: searching by number alone turns up older
     * calls to the same business.
     *
     * A null result means "could not be determined" — the UI then offers the
     * status buttons without a duration. 0, by contrast, is a valid result
     * (never connected).
     */
    suspend fun findDuration(context: Context, number: String, fromMillis: Long): Int? =
        withContext(Dispatchers.IO) {
            if (!canRead(context)) return@withContext null
            repeat(ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RETRY_DELAY_MS)
                val duration = query(context, number, fromMillis)
                if (duration != null) return@withContext duration
            }
            null
        }

    /** A single query against the log. null = nothing matching found. */
    private fun query(context: Context, number: String, fromMillis: Long): Int? {
        val columns = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE,
        )
        val condition = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
        val values = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), fromMillis.toString())
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                columns,
                condition,
                values,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val iNumber = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val iDuration = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (c.moveToNext()) {
                    if (matches(c.getString(iNumber), number)) return c.getInt(iDuration)
                }
                null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
