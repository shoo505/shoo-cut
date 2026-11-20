package com.shoo.cut

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AppCompatActivity
import com.shoo.cut.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoProcessor: VideoProcessor

    // State
    private var selectedVideoUri: Uri? = null
    private var videoMetadata: VideoMetadata? = null
    private var currentCutPercentage: Double = DEFAULT_CUT_PERCENTAGE
    private var currentCutDirection: CutDirection = CutDirection.REMOVE_BEGINNING
    private var outputUri: Uri? = null
    private var isProcessing = false

    private val TAG = "MainActivity"

    // Activity result launchers
    private val pickVideoLauncher = registerForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            selectVideo(uri)
        }
    }

    private val pickOutputFileLauncher = registerForActivityResult(CreateDocument()) { uri ->
        if (uri != null) {
            selectOutputLocation(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoProcessor = VideoProcessor(this)

        setupVideoSelection()
        setupPercentageButtons()
        setupDirectionButtons()
        setupProcessingControls()
        updatePreview()
    }

    private fun setupVideoSelection() {
        binding.buttonSelectVideo.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun selectVideo(uri: Uri) {
        selectedVideoUri = uri
        videoMetadata = null // Reset metadata

        binding.textSelectedFilename.text = uri.lastPathSegment ?: "Unknown video"
        binding.textVideoInfo.text = "Loading metadata..."
        binding.textVideoCodec.text = "Video Codec: --"
        binding.textAudioCodec.text = "Audio Codec: --"

        // Probe video metadata
        probeVideoMetadata(uri)
        updatePreview()
    }

    private fun probeVideoMetadata(uri: Uri) {
        // We need a temporary file path to probe with FFmpeg
        // Copy URI to temp file first
        val tempFile = File(cacheDir, "probe_${System.currentTimeMillis()}.tmp")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI for probing", e)
            runOnUiThread {
                binding.textVideoInfo.text = "Failed to load metadata"
                binding.textVideoCodec.text = "Video Codec: --"
                binding.textAudioCodec.text = "Audio Codec: --"
            }
            return
        }

        // Use FFmpeg to probe the video
        val probeCommand = arrayOf(
            "-v", "error",
            "-show_entries", "format=duration,size,format_name:stream=codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate,sample_rate,channels",
            "-of", "default=noprint_wrappers=1",
            tempFile.absolutePath
        )

        videoProcessor.ffmpeg.execute(emptyMap(), probeCommand, object : com.github.pao11.libffmpeg.FFmpegExecuteResponseHandler() {
            override fun onStart() {}

            override fun onSuccess(message: String?) {
                runOnUiThread {
                    parseProbeOutput(message ?: "", tempFile.absolutePath, uri)
                    // Clean up temp file
                    tempFile.delete()
                }
            }

            override fun onFailure(message: String?) {
                runOnUiThread {
                    Log.w(TAG, "Probe failed: $message")
                    binding.textVideoCodec.text = "Video Codec: --"
                    binding.textAudioCodec.text = "Audio Codec: --"
                    binding.textVideoInfo.text = "Failed to load metadata"
                    tempFile.delete()
                }
            }

            override fun onProgress(message: String?) {}

            override fun onFinish() {}
        })
    }

    private fun parseProbeOutput(output: String, path: String, originalUri: Uri) {
        var duration: Double = 0.0
        var width: Int = 0
        var height: Int = 0
        var fps: Double? = null
        var videoCodec = "unknown"
        var audioCodec: String? = null
        var audioChannels: Int? = null
        var audioSampleRate: Int? = null
        var container = "unknown"
        var sizeBytes: Long = 0
        var bitrate: Int? = null
        var hasAudio = false

        val lines = output.lines()
        for (line in lines) {
            when {
                line.startsWith("duration=") -> {
                    duration = line.substringAfter("duration=").toDoubleOrNull() ?: 0.0
                }
                line.startsWith("size=") -> {
                    sizeBytes = line.substringAfter("size=").toLongOrNull() ?: 0
                }
                line.startsWith("format_name=") -> {
                    container = line.substringAfter("format_name=")
                }
                line.startsWith("codec_type=video") -> {
                    // Next lines will have video codec info
                }
                line.startsWith("codec_name=") && videoCodec == "unknown" -> {
                    videoCodec = line.substringAfter("codec_name=")
                }
                line.startsWith("codec_name=audio") || line.contains("codec_name=") -> {
                    if (audioCodec == null) {
                        audioCodec = line.substringAfter("codec_name=")
                        hasAudio = true
                    }
                }
                line.startsWith("width=") -> {
                    width = line.substringAfter("width=").toIntOrNull() ?: 0
                }
                line.startsWith("height=") -> {
                    height = line.substringAfter("height=").toIntOrNull() ?: 0
                }
                line.startsWith("avg_frame_rate=") -> {
                    val rate = line.substringAfter("avg_frame_rate=")
                    if (rate.contains("/")) {
                        val parts = rate.split("/")
                        if (parts.size == 2) {
                            val num = parts[0].toDoubleOrNull() ?: 0.0
                            val den = parts[1].toDoubleOrNull() ?: 1.0
                            if (den != 0.0) fps = num / den
                        }
                    } else {
                        fps = rate.toDoubleOrNull()
                    }
                }
                line.startsWith("channels=") -> {
                    audioChannels = line.substringAfter("channels=").toIntOrNull()
                }
                line.startsWith("sample_rate=") -> {
                    audioSampleRate = line.substringAfter("sample_rate=").toIntOrNull()
                }
            }
        }

        videoMetadata = VideoMetadata(
            path = path,
            filename = originalUri.lastPathSegment ?: "video",
            durationSeconds = duration,
            width = width,
            height = height,
            fps = fps,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioChannels = audioChannels,
            audioSampleRate = audioSampleRate,
            container = container,
            formatName = container,
            sizeBytes = sizeBytes,
            bitrate = bitrate,
            hasAudio = hasAudio
        )

        runOnUiThread {
            binding.textVideoCodec.text = "Video Codec: $videoCodec"
            binding.textAudioCodec.text = if (hasAudio) "Audio Codec: $audioCodec" else "Audio: None"
            val info = String.format("%s x %s", width, height)
            binding.textVideoInfo.text = "$info  |  ${formatDuration(duration)}  |  ${StorageHelper.formatFileSize(sizeBytes)}"
        }

        updatePreview()
    }

    private fun setupPercentageButtons() {
        val buttons = listOf(
            binding.buttonPct25 to 25.0,
            binding.buttonPct45 to 45.0,
            binding.buttonPct50 to 50.0,
            binding.buttonPct65 to 65.0,
            binding.buttonPct75 to 75.0
        )

        for ((button, pct) in buttons) {
            button.setOnClickListener {
                currentCutPercentage = pct
                // Update button appearances
                for ((b, _) in buttons) {
                    b.isEnabled = true
                    b.alpha = 1.0f
                }
                button.isEnabled = false
                button.alpha = 0.6f
                binding.textSelectedPercentage.text = "Selected: ${pct.toInt()}%"
                updatePreview()
            }
        }

        // Set default 50% selected
        binding.buttonPct50.isEnabled = false
        binding.buttonPct50.alpha = 0.6f
    }

    private fun setupDirectionButtons() {
        binding.radioRemoveBeginning.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentCutDirection = CutDirection.REMOVE_BEGINNING
                updatePreview()
            }
        }
        binding.radioRemoveEnding.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentCutDirection = CutDirection.REMOVE_ENDING
                updatePreview()
            }
        }
    }

    private fun setupProcessingControls() {
        binding.buttonStartProcessing.setOnClickListener {
            startProcessing()
        }

        binding.buttonCancel.setOnClickListener {
            cancelProcessing()
        }
    }

    private fun updatePreview() {
        if (videoMetadata == null) {
            binding.previewOriginalDuration.text = "Original: --"
            binding.previewCutPercentage.text = "Cut: --"
            binding.previewRemovedDuration.text = "Removed: --"
            binding.previewRetainedDuration.text = "Retained: --"
            binding.previewDirection.text = "Direction: ${currentCutDirection.toReadableString()}"
            binding.previewSegments.text = "Segments: --"
            return
        }

        try {
            val cutPlan = calculateCutPlan(
                sourceDuration = videoMetadata!!.durationSeconds,
                cutPercentage = currentCutPercentage,
                cutDirection = currentCutDirection
            )

            binding.previewOriginalDuration.text = "Original: ${formatDuration(videoMetadata!!.durationSeconds)}"
            binding.previewCutPercentage.text = "Cut: ${cutPlan.cutPercentage.toInt()}% removed"
            binding.previewRemovedDuration.text = "Removed: ${formatDuration(cutPlan.sourceDuration - cutPlan.actualRetainedDuration)}"
            binding.previewRetainedDuration.text = "Retained: ${formatDuration(cutPlan.actualRetainedDuration)} (${String.format("%.1f", cutPlan.effectiveRetentionPercent)}%)"
            binding.previewDirection.text = "Direction: ${currentCutDirection.toReadableString()}"
            binding.previewSegments.text = "Segments: ${cutPlan.segments.size} (distributed)"
            binding.previewOutputResolution.text = "Output Resolution: 1280 x 720"
            binding.previewScalingAlgorithm.text = "Scaling Algorithm: Bicubic"
            binding.previewOutputFormat.text = "Output Format: MP4"
        } catch (e: Exception) {
            Log.e(TAG, "Preview update error", e)
        }
    }

    private fun startProcessing() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, R.string.error_invalid_video, Toast.LENGTH_SHORT).show()
            return
        }

        if (videoMetadata == null || videoMetadata!!.durationSeconds <= 0) {
            Toast.makeText(this, R.string.error_metadata, Toast.LENGTH_SHORT).show()
            return
        }

        // Determine output location
        if (outputUri == null) {
            // Prompt user to select output location
            pickOutputFileLauncher.launch(arrayOf("video/mp4"))
            return
        }

        val cutPlan = calculateCutPlan(
            sourceDuration = videoMetadata!!.durationSeconds,
            cutPercentage = currentCutPercentage,
            cutDirection = currentCutDirection
        )

        // Check if we have enough segments
        if (cutPlan.segments.isEmpty()) {
            Toast.makeText(this, "No valid segments to process", Toast.LENGTH_SHORT).show()
            return
        }

        doProcess(cutPlan)
    }

    private fun doProcess(cutPlan: CutPlan) {
        isProcessing = true
        binding.buttonStartProcessing.isEnabled = false
        binding.buttonStartProcessing.text = getString(R.string.processing)
        binding.buttonCancel.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.textStatus.text = "Starting..."
        binding.textProgressPercent.text = "0%"

        try {
            videoProcessor.processVideo(
                inputUri = selectedVideoUri!!,
                outputUri = outputUri!!,
                cutPlan = cutPlan,
                listener = object : VideoProcessor.ProcessingListener {
                    override fun onProgress(percent: Float, message: String) {
                        runOnUiThread {
                            binding.progressBar.progress = percent.toInt()
                            binding.textStatus.text = message
                            binding.textProgressPercent.text = "${percent.toInt()}%"
                        }
                    }

                    override fun onCompleted(outputPath: String) {
                        runOnUiThread {
                            isProcessing = false
                            binding.buttonStartProcessing.isEnabled = true
                            binding.buttonStartProcessing.text = getString(R.string.start_processing)
                            binding.buttonCancel.visibility = android.view.View.GONE
                            binding.progressBar.progress = 100
                            binding.textStatus.text = getString(R.string.completed)
                            binding.textProgressPercent.text = "100%"
                            binding.textOutputLocation.text = "Output: $outputPath"
                            Toast.makeText(this@MainActivity, "Processing completed!", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            isProcessing = false
                            binding.buttonStartProcessing.isEnabled = true
                            binding.buttonStartProcessing.text = getString(R.string.start_processing)
                            binding.buttonCancel.visibility = android.view.View.GONE
                            binding.textStatus.text = "Error: $message"
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onCancelled() {
                        runOnUiThread {
                            isProcessing = false
                            binding.buttonStartProcessing.isEnabled = true
                            binding.buttonStartProcessing.text = getString(R.string.start_processing)
                            binding.buttonCancel.visibility = android.view.View.GONE
                            binding.textStatus.text = getString(R.string.processing_cancelled)
                            Toast.makeText(this@MainActivity, "Processing cancelled", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed", e)
            isProcessing = false
            binding.buttonStartProcessing.isEnabled = true
            binding.buttonStartProcessing.text = getString(R.string.start_processing)
            binding.buttonCancel.visibility = android.view.View.GONE
            Toast.makeText(this, e.message ?: getString(R.string.error_unknown), Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelProcessing() {
        videoProcessor.cancel()
        binding.buttonCancel.isEnabled = false
    }

    private fun selectOutputLocation(uri: Uri) {
        outputUri = uri
        binding.textOutputLocation.text = "Output: ${uri.lastPathSegment}"
        Toast.makeText(this, "Output location set", Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return when {
            hours > 0 -> "$hours h ${String.format("%02d", minutes)} m ${String.format("%02d", secs)} s"
            minutes > 0 -> "$minutes m ${String.format("%02d", secs)} s"
            else -> "$secs s"
        }
    }

    private fun CutDirection.toReadableString(): String {
        return when (this) {
            CutDirection.REMOVE_BEGINNING -> getString(R.string.mode_remove_beginning)
            CutDirection.REMOVE_ENDING -> getString(R.string.mode_remove_ending)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isProcessing) {
            videoProcessor.cancel()
        }
    }
}