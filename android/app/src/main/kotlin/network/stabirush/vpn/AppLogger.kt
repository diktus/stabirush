package network.stabirush.vpn

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {
    private const val MAX_LOGS = 600
    private val buffer = ConcurrentLinkedDeque<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        log("I", "System", "Stabirush Cyber VPN Logger initialized")
    }

    fun log(level: String, tag: String, msg: String) {
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$time] [$level/$tag] $msg"
        buffer.add(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.pollFirst()
        }
        when (level) {
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
            else -> Log.d(tag, msg)
        }
    }

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    fun getLogs(): List<String> = buffer.toList()
    fun getAllAsText(): String = buffer.joinToString("\n")
    fun clear() {
        buffer.clear()
        log("I", "System", "Logs cleared by user")
    }
}
