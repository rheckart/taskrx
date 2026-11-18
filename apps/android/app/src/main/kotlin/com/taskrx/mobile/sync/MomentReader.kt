package com.taskrx.mobile.sync

import android.os.SystemClock
import android.util.Log
import com.taskrx.core.sync.ConvexFunctions
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reads one instant from the server for the fire-time check (ADR 0016), over a request of its own
 * rather than the long-lived Convex connection.
 *
 * Why not the connection: Android cuts a background app's network five seconds after it leaves
 * the foreground (`dumpsys netpolicy`: `blocked=APP_BACKGROUND`). The connection drops, and its
 * reconnects fail until something brings the app forward. When a dose's alarm does, the first
 * attempt can land before the firewall lifts, and the connection then sits out its retry backoff:
 * about six seconds on the phone at 08:30, past the bound. The library also keeps no token after a
 * failed refresh while still reporting itself authenticated, so its readiness says nothing about
 * whether a query will go through.
 *
 * So this takes Clerk's `convex` token (from Clerk's cache while it lasts, an hour) and posts one
 * query to the deployment's HTTP API. A failed attempt is tried again after [pauseMs], for up to
 * [retryForMs], since the first may land before the firewall lifts; a token the server refuses is
 * fetched fresh for the next. Every attempt is logged, so a phone's dose shows what the wake cost.
 */
class MomentReader(
    private val query: suspend (function: String, args: Map<String, Double>, token: String) -> JsonElement,
    private val token: suspend (fresh: Boolean) -> String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pauseMs: Long = PAUSE_MS,
    private val retryForMs: Long = RETRY_FOR_MS,
) {
    internal suspend fun read(scheduledTime: Long): List<ServerInstance> {
        val started = clock()
        val at = scheduledTime.toDouble()
        var fresh = false
        var attempt = 0
        while (true) {
            attempt++
            try {
                // Doubles: the times are `v.number()` on the server.
                val reply = query(ConvexFunctions.INSTANCES_LIST, mapOf("since" to at, "until" to at), token(fresh))
                val rows = ServerDocs.instances(reply)
                Log.i(TAG, "read of $scheduledTime: ${rows.size} rows on attempt $attempt after ${clock() - started} ms")
                return rows
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                // A reply this build cannot read reads the same way next time.
                throw e
            } catch (e: Exception) {
                val took = clock() - started
                Log.w(TAG, "read of $scheduledTime: attempt $attempt failed after $took ms: $e")
                if (e.isAuthRefusal() || (e as? ConvexHttpError)?.refusedToken == true) fresh = true
                if (took + pauseMs >= retryForMs) throw e
            }
            delay(pauseMs)
        }
    }

    companion object {
        /** Between attempts: short, since the usual cause is a firewall about to lift. */
        const val PAUSE_MS = 250L

        /** Past the bound, so a read that lands late can still answer a later dose of the instant. */
        const val RETRY_FOR_MS = 8_000L
    }
}

/** A query the deployment answered with an error. [refusedToken]: it would not take the token. */
internal class ConvexHttpError(message: String, val refusedToken: Boolean) : Exception(message)

/** Convex's HTTP API, for the one query the fire-time check makes. */
internal class ConvexHttp(
    deploymentUrl: String,
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000,
) {
    private val endpoint = URL(deploymentUrl.trimEnd('/') + "/api/query")

    suspend fun query(function: String, args: Map<String, Double>, token: String): JsonElement =
        withContext(Dispatchers.IO) {
            val body =
                buildJsonObject {
                    put("path", function)
                    put("args", buildJsonObject { args.forEach { (key, value) -> put(key, value) } })
                    put("format", "json")
                }.toString()
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: throw IOException("HTTP $status with no body")
                reply(status, text)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        /**
         * A reply's value, or the error it carries: `{"status":"error","errorMessage":…}` from a
         * function that threw, or `{"code":…,"message":…}` with a 401 for a token it would not take.
         */
        fun reply(status: Int, text: String): JsonElement {
            // A proxy's error page is no reply from Convex: worth another attempt.
            val reply =
                try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: IllegalArgumentException) {
                    throw IOException("HTTP $status, not a Convex reply: ${text.take(80)}")
                }
            if (status in 200..299 && reply["status"]?.jsonPrimitive?.contentOrNull == "success") return reply["value"] ?: JsonNull
            val message =
                reply["errorMessage"]?.jsonPrimitive?.contentOrNull
                    ?: reply["message"]?.jsonPrimitive?.contentOrNull?.let { "${reply["code"]?.jsonPrimitive?.contentOrNull}: $it" }
                    ?: "HTTP $status"
            throw ConvexHttpError(message, refusedToken = status == 401)
        }
    }
}

private const val TAG = "alarm"
