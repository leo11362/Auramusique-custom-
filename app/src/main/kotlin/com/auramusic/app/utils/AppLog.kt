package com.auramusic.app.utils

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a readable journal of what the app does (playback state, errors, DJ transitions) in
 * filesDir/app_log.txt, so a bug can be read and shared from Settings > Player > Journal.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    private const val KEEP_BYTES = 200_000L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun file(): File? = file

    fun install(filesDir: File) {
        file = File(filesDir, "app_log.txt")
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                val keep = priority >= Log.INFO || tag == "DJ" || tag == "Player"
                if (!keep) return
                val level = when (priority) { Log.ERROR -> "E"; Log.WARN -> "W"; Log.INFO -> "I"; else -> "D" }
                val trace = t?.let { "\n" + Log.getStackTraceString(it).lines().take(12).joinToString("\n") } ?: ""
                write("${fmt.format(Date())} $level/${tag ?: "-"}: $message$trace\n")
            }
        })
    }

    @Synchronized
    private fun write(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val tail = f.readBytes().takeLast(KEEP_BYTES.toInt()).toByteArray()
                f.writeBytes(tail)
            }
            f.appendText(line)
        }
    }

    /** Last [maxChars] characters of the journal. */
    @Synchronized
    fun read(maxChars: Int = 60_000): String {
        val f = file ?: return ""
        return runCatching { f.readText().takeLast(maxChars) }.getOrDefault("")
    }

    @Synchronized
    fun clear() { runCatching { file?.writeText("") } }
}
