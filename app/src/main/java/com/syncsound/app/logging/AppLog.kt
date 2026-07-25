package com.syncsound.app.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogArea { DISCOVERY, SESSION, NETWORK, AUDIO, SYNC, PERMISSION, CRASH }
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEvent(
    val timestamp: String,
    val level: LogLevel,
    val area: LogArea,
    val event: String,
    val details: Map<String, String>,
)

/**
 * Structured Logcat output plus a bounded in-memory diagnostic trail. Logging
 * itself is exception-safe so diagnostics can never crash a session.
 */
object AppLog {
    private const val TAG = "SyncSound"
    private const val MAX_EVENTS = 500
    private val history = ConcurrentLinkedDeque<LogEvent>()
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events

    fun d(area: LogArea, event: String, vararg details: Pair<String, Any?>) =
        write(LogLevel.DEBUG, area, event, null, details)

    fun i(area: LogArea, event: String, vararg details: Pair<String, Any?>) =
        write(LogLevel.INFO, area, event, null, details)

    fun w(area: LogArea, event: String, error: Throwable? = null, vararg details: Pair<String, Any?>) =
        write(LogLevel.WARN, area, event, error, details)

    fun e(area: LogArea, event: String, error: Throwable? = null, vararg details: Pair<String, Any?>) =
        write(LogLevel.ERROR, area, event, error, details)

    private fun write(
        level: LogLevel,
        area: LogArea,
        event: String,
        error: Throwable?,
        details: Array<out Pair<String, Any?>>,
    ) {
        runCatching {
            val fields = buildMap {
                details.forEach { (key, value) -> put(key, value?.toString() ?: "null") }
                error?.let {
                    put("error", it::class.java.simpleName)
                    put("message", it.message.orEmpty())
                }
            }
            val entry = LogEvent(Instant.now().toString(), level, area, event, fields)
            history.addLast(entry)
            while (history.size > MAX_EVENTS) history.pollFirst()
            _events.value = history.toList()
            val message = buildString {
                append("area=").append(area)
                append(" event=").append(event)
                fields.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
            }
            when (level) {
                LogLevel.DEBUG -> Log.d(TAG, message)
                LogLevel.INFO -> Log.i(TAG, message)
                LogLevel.WARN -> Log.w(TAG, message, error)
                LogLevel.ERROR -> Log.e(TAG, message, error)
            }
        }
    }
}
