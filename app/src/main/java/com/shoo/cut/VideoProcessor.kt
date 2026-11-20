package com.shoo.cut

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the FFmpeg executable that ships inside the verified
 * `io.github.pao11:ffmpeg-android:1.1.0` AAR (`libffmpeg.so`).
 *
 * The AAR installs the binary into the app's native library directory. Running
 * it with [ProcessBuilder] (rather than the library's deprecated AsyncTask
 * wrapper) gives real cancellation: [cancel] destroys the process and the
 * blocking read loop then returns normally.
 *
 * Only arm64-v8a and armeabi-v7a natives are packaged, so unsupported ABIs get
 * a clear message instead of a crash.
 */
class VideoProcessor(private val context: Context) {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    private fun ffmpegBinary(): File {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.isFile) {
            throw IllegalStateException(
                "FFmpeg is not available for this device ABI " +
                    "(${Build.SUPPORTED_ABIS.joinToString()}). " +
                    "This build supports arm64-v8a and armeabi-v7a."
            )
        }
        return binary
    }

    /**
     * Runs [command] to completion, forwarding every merged stdout/stderr line to
     * [onLine]. Returns the process exit code, or `-1` if interrupted.
     */
    fun run(command: Array<String>, onLine: (String) -> Unit): Int {
        cancelled.set(false)
        val binary = ffmpegBinary()
        val builder = ProcessBuilder(listOf(binary.absolutePath) + command.toList())
        builder.redirectErrorStream(true)

        val active = builder.start()
        process = active
        try {
            try {
                active.outputStream.close()
            } catch (_: Exception) {
                // FFmpeg runs with -nostdin; closing stdin is best-effort.
            }
            active.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } finally {
            process = null
        }

        return try {
            active.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            active.destroy()
            -1
        }
    }

    /** Requests cancellation of the currently running FFmpeg process. */
    fun cancel() {
        cancelled.set(true)
        val active = process ?: return
        try {
            active.destroy()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    fun wasCancelled(): Boolean = cancelled.get()

    companion object {
        private const val BINARY_NAME = "libffmpeg.so"
    }
}
