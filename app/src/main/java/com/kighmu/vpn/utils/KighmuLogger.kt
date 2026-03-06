package com.kighmu.vpn.utils

import android.util.Log
import com.kighmu.vpn.models.LogEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Central logger for KIGHMU VPN.
 * Keeps last 500 log entries in memory and exposes a Flow for UI.
 */
object KighmuLogger {

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()
    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String, level: LogEntry.LogLevel = LogEntry.LogLevel.INFO, tag: String = "KIGHMU") {
        val entry = LogEntry(System.currentTimeMillis(), level, message, tag)
        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_ENTRIES) logBuffer.pollFirst()
        _logFlow.tryEmit(entry)

        // Also log to Android logcat
        when (level) {
            LogEntry.LogLevel.DEBUG   -> Log.d(tag, message)
            LogEntry.LogLevel.INFO    -> Log.i(tag, message)
            LogEntry.LogLevel.WARNING -> Log.w(tag, message)
            LogEntry.LogLevel.ERROR   -> Log.e(tag, message)
        }
    }

    fun info(tag: String, message: String)    = log(message, LogEntry.LogLevel.INFO, tag)
    fun error(tag: String, message: String)   = log(message, LogEntry.LogLevel.ERROR, tag)
    fun warning(tag: String, message: String) = log(message, LogEntry.LogLevel.WARNING, tag)
    fun debug(tag: String, message: String)   = log(message, LogEntry.LogLevel.DEBUG, tag)

    fun getRecentLogs(count: Int = 100): List<LogEntry> =
        logBuffer.toList().takeLast(count)

    fun clearLogs() = logBuffer.clear()

    fun formatEntry(entry: LogEntry): String {
        val time = dateFormat.format(Date(entry.timestamp))
        val levelStr = entry.level.name.padEnd(7)
        return "[$time] $levelStr [${entry.tag}] ${entry.message}"
    }

    fun getAllLogsText(): String =
        logBuffer.joinToString("\n") { formatEntry(it) }
}
