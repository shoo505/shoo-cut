package com.shoo.cut

import android.util.Log
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import java.util.Collections
import java.util.Locale

/**
 * Runs FFmpeg in-process through the verified MobileFFmpeg build shipped by
 * `com.bihe0832.android:lib-ffmpeg-mobile-aaf:8.5.0`.
 *
 * That AAR contains genuine `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`
 * libraries (FFmpeg 4.4-dev, NDK r25b) with `libopenh264`, the native AAC
 * encoder, `concat`, `trim`, `pad`, `scale` (bicubic) and `setsar`, and it
 * bundles the `libc++_shared.so` those libraries link against.
 *
 * [run] blocks the calling worker thread and returns FFmpeg's exit code.
 * [cancel] asks FFmpeg to stop; the blocking call then returns
 * [Config.RETURN_CODE_CANCEL].
 */
class VideoProcessor {

    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val logLines = Collections.synchronizedList(ArrayList<String>())

    @Volatile
    private var running = false

    /**
     * Executes [command], forwarding the encoded output time in milliseconds to
     * [onOutputTimeMillis]. Returns FFmpeg's exit code.
     */
    fun run(command: String, onOutputTimeMillis: (Int) -> Unit): Int {
        cancelRequested.set(false)
        logLines.clear()

        Config.enableStatisticsCallback { statistics ->
            if (!cancelRequested.get()) {
                try {
                    onOutputTimeMillis(statistics.time)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Progress callback failed", e)
                }
            }
        }
        Config.enableLogCallback { message ->
            appendLog(message.text)
        }

        return try {
            running = true
            FFmpeg.execute(command)
        } finally {
            running = false
            Config.enableStatisticsCallback(null)
            Config.enableLogCallback(null)
        }
    }

    /**
     * Requests a graceful stop of the running FFmpeg session. Safe to call from
     * the main thread while [run] blocks on the worker thread.
     */
    fun cancel() {
        if (!running) return
        cancelRequested.set(true)
        try {
            FFmpeg.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cancelling FFmpeg failed", e)
        }
    }

    fun wasCancelled(): Boolean = cancelRequested.get()

    /**
     * The tail of FFmpeg's own log, so a failed encode can show a useful
     * diagnostic instead of a bare exit code.
     */
    fun diagnostics(maxLines: Int = 12, maxChars: Int = 1200): String {
        val tail = synchronized(logLines) {
            logLines.takeLast(maxLines.coerceAtLeast(1))
        }
        if (tail.isEmpty()) return ""
        val joined = tail.joinToString("\n")
        return if (joined.length <= maxChars) joined else joined.takeLast(maxChars)
    }

    /** Version string of the bundled FFmpeg, for the UI / diagnostics. */
    fun ffmpegVersion(): String = try {
        Config.getFFmpegVersion()
    } catch (e: Throwable) {
        "unknown"
    }

    private fun appendLog(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        synchronized(logLines) {
            logLines.add(cleaned)
            if (logLines.size > MAX_LOG_LINES) {
                logLines.removeAt(0)
            }
        }
    }

    companion object {
        private const val TAG = "VideoProcessor"
        private const val MAX_LOG_LINES = 80

        /** Human-readable exit-code description used in error messages. */
        fun describeReturnCode(code: Int): String = when (code) {
            Config.RETURN_CODE_SUCCESS -> "success"
            Config.RETURN_CODE_CANCEL -> "cancelled"
            else -> String.format(Locale.US, "exit code %d", code)
        }
    }
}
