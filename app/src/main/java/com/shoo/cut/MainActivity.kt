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
import kotlin.collections.emptyMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoProcessor: VideoProcessor

    // State
    private var selectedVideoUri: Uri? = null
    private var selectedVideoPath: String? = null
    private var videoMetadata: VideoMetadata? = null
    private var currentCutPercentage: Double = DEFAULT_CUT_PERCENTAGE
    private var currentCutDirection: CutDirection = CutDirection.REMOVE_BEGINNING
    private var outputFilePath: String? = null
    private var isProcessing = false

    private val TAG = "MainActivity"

    // Activity result launchers
    private val pickVideoLauncher = registerForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            selectVideo(uri)
        }
    }

    private val pickOutputDirLauncher = registerForActivityResult(CreateDocument()) { uri ->
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

        // Try to get the file path
        val path = StorageHelper.getPathFromUri(this, uri)
        selectedVideoPath = path

        if (path == null) {
            // SAF URI - can't get direct path, use content resolver approach
            // For now, show the URI and try to probe via FFmpeg using the URI
            binding.textSelectedFilename.text = uri.lastPathSegment ?: "Unknown video"
            binding.textVideoInfo.text = "Video selected via Storage Access Framework"
            binding.textVideoCodec.text = "Video Codec: --"
            binding.textAudioCodec.text = "Audio Codec: --"
            Toast.makeText(this, "Video selected (path access limited)", Toast.LENGTH_SHORT).show()
        } else {
            val file = File(path)
            binding.textSelectedFilename.text = file.name
            val sizeStr = StorageHelper.formatFileSize(file.length())
            binding.textVideoInfo.text = "Size: $sizeStr"

            // Probe video metadata
            probeVideoMetadata(path)
        }

        updatePreview()
    }

    private fun probeVideoMetadata(path: String) {
        // Use FFmpeg to probe the video
        val probeCommand = arrayOf(
            "-v", "error",
            "-show_entries", "format=duration,size,format_name:stream=codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate,sample_rate,channels",
            "-of", "default=noprint_wrappers=1",
            path
        )

        videoProcessor.ffmpeg.execute(emptyMap(), probeCommand, object : com.github.pao11.libffmpeg.FFmpegExecuteResponseHandler() {
            override fun onStart() {}

            override fun onSuccess(message: String?) {
                runOnUiThread {
                    parseProbeOutput(message ?: "", path)
                }
            }

            override fun onFailure(message: String?) {
                runOnUiThread {
                    Log.w(TAG, "Probe failed: $message")
                    binding.textVideoCodec.text = "Video Codec: --"
                    binding.textAudioCodec.text = "Audio Codec: --"
                }
            }

            override fun onProgress(message: String?) {}

            override fun onFinish() {}
        })
    }

    private fun parseProbeOutput(output: String, path: String) {
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
            filename = File(path).name,
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
            return
        }

        try {
            val result = calculateAutoCut(
                sourceDuration = videoMetadata!!.durationSeconds,
                cutPercentage = currentCutPercentage,
                cutDirection = currentCutDirection
            )

            binding.previewOriginalDuration.text = "Original: ${formatDuration(videoMetadata!!.durationSeconds)}"
            binding.previewCutPercentage.text = "Cut: ${result.cutPercentage.toInt()}% removed"
            binding.previewRemovedDuration.text = "Removed: ${result.removedDurationLabel}"
            binding.previewRetainedDuration.text = "Retained: ${result.retainedDurationLabel}"
            binding.previewDirection.text = "Direction: ${currentCutDirection.toReadableString()}"
            binding.previewOutputResolution.text = "Output Resolution: 1280 x 720"
            binding.previewScalingAlgorithm.text = "Scaling Algorithm: Bicubic"
            binding.previewOutputFormat.text = "Output Format: MP4"
        } catch (e: Exception) {
            Log.e(TAG, "Preview update error", e)
        }
    }

    private fun startProcessing() {
        if (selectedVideoPath == null) {
            Toast.makeText(this, R.string.error_invalid_video, Toast.LENGTH_SHORT).show()
            return
        }

        if (videoMetadata == null || videoMetadata!!.durationSeconds <= 0) {
            Toast.makeText(this, R.string.error_metadata, Toast.LENGTH_SHORT).show()
            return
        }

        // Determine output location
        if (outputFilePath == null) {
            // Prompt user to select output location
            pickOutputDirLauncher.launch(arrayOf("*/*"))
            return
        }

        val outputFile = File(outputFilePath)
        if (outputFile.exists()) {
            // Ask for overwrite confirmation
            AlertDialog.Builder(this)
                .setTitle("File Exists")
                .setMessage("The output file already exists. Overwrite?")
                .setPositiveButton("Overwrite") { _, _ -> doProcess() }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        } else {
            doProcess()
        }
    }

    private fun doProcess() {
        isProcessing = true
        binding.buttonStartProcessing.isEnabled = false
        binding.buttonStartProcessing.text = getString(R.string.processing)
        binding.buttonCancel.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.textStatus.text = "Starting..."
        binding.textProgressPercent.text = "0%"

        try {
            val result = calculateAutoCut(
                sourceDuration = videoMetadata!!.durationSeconds,
                cutPercentage = currentCutPercentage,
                cutDirection = currentCutDirection
            )

            videoProcessor.processVideo(
                inputPath = selectedVideoPath!!,
                outputPath = outputFilePath!!,
                autoCutResult = result,
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
                            binding.textOutputLocation.text = "Output Location: $outputPath"
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
        val path = StorageHelper.getPathFromUri(this, uri)
        if (path != null) {
            outputFilePath = path
            // Ensure it ends with .mp4
            if (!outputFilePath!!.endsWith(".mp4", ignoreCase = true)) {
                outputFilePath = outputFilePath + ".mp4"
            }
            binding.textOutputLocation.text = "Output Location: $outputFilePath"
            Toast.makeText(this, "Output location set", Toast.LENGTH_SHORT).show()
        } else {
            // Can't get direct path from SAF URI
            outputFilePath = uri.path
            binding.textOutputLocation.text = "Output Location: $outputFilePath (via SAF)"
        }
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
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