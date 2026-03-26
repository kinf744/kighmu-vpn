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

fun String.sanitizeGlobal(): String {
    return this
        .replace(Regex("(\\d{1,3}\\.){3}\\d{1,3}"), "[IP]")
        .replace(Regex(":\\d{2,5}\b"), ":[PORT]")
        .replace(Regex("/data/app/~~[a-zA-Z0-9_-]+/"), "/data/app/[MASKED]/")
}


object KighmuLogger {

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()
    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun sanitize(message: String): String {
        var msg = message
        // Masquer passwords et auth
        msg = msg.replace(Regex("""(auth[_-]?str|password|auth|obfs)["\s:=]+[^\s,"{}]+"""), "$1: ***")
        msg = msg.replace(Regex("(ghp|token|key|secret)_[A-Za-z0-9]+"), "***")
        // Masquer IPs partiellement (garder dernier octet)
        // Ne pas masquer les IPs locales 127.x et 10.x
        return msg
    }

    fun log(message: String, level: LogEntry.LogLevel = LogEntry.LogLevel.INFO, tag: String = "KIGHMU") {
        val safeMessage = sanitize(message)
        val entry = LogEntry(System.currentTimeMillis(), level, safeMessage, tag)
        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_ENTRIES) logBuffer.pollFirst()
        _logFlow.tryEmit(entry)

        // Also log to Android logcat
        when (level) {
            LogEntry.LogLevel.DEBUG   -> Log.d(tag, safeMessage)
            LogEntry.LogLevel.INFO    -> Log.i(tag, safeMessage)
            LogEntry.LogLevel.WARNING -> Log.w(tag, safeMessage)
            LogEntry.LogLevel.ERROR   -> Log.e(tag, safeMessage)
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
