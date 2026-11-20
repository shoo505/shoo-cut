package com.shoo.cut

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.shoo.cut.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * UI controller that communicates with ProcessingService via broadcasts.
 * Does not own processing logic - delegates to foreground service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var metadataReader: VideoMetadataReader
    private val metadataExecutor = Executors.newSingleThreadExecutor()

    private var inputUri: Uri? = null
    private var outputUri: Uri? = null
    private var lastResultUri: Uri? = null
    private var metadata: VideoMetadata? = null
    private var plan: SegmentPlan? = null
    private var pendingStart = false
    private var notificationPermissionExplained = false

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePickedVideo(uri)
        }

    private val createOutputLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(VideoPicker.MP4_MIME_TYPE)
        ) { uri ->
            if (uri != null) {
                handleOutputChosen(uri)
                if (pendingStart) {
                    pendingStart = false
                    startProcessing()
                }
            } else {
                pendingStart = false
            }
        }

    // Broadcast receiver for service updates
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ProcessingService.BROADCAST_PROGRESS -> onProgressUpdate(intent)
                ProcessingService.BROADCAST_COMPLETED -> onProcessingCompleted(intent)
                ProcessingService.BROADCAST_ERROR -> onProcessingError(intent)
                ProcessingService.BROADCAST_CANCELLED -> onProcessingCancelled()
                ProcessingService.BROADCAST_STATE -> onStateRestored(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metadataReader = VideoMetadataReader(this)

        setupClickListeners()
        requestNotificationPermissionIfNeeded()
        registerReceivers()
        requestCurrentState()
        resetUi()
    }

    private fun setupClickListeners() {
        binding.buttonSelectVideo.setOnClickListener {
            if (!isProcessing()) {
                pickVideoLauncher.launch(arrayOf(VideoPicker.VIDEO_MIME_TYPE))
            }
        }
        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonCancel.setOnClickListener { cancelProcessing() }
        binding.buttonOpenOutput.setOnClickListener { openOutput() }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ProcessingService.BROADCAST_PROGRESS)
            addAction(ProcessingService.BROADCAST_COMPLETED)
            addAction(ProcessingService.BROADCAST_ERROR)
            addAction(ProcessingService.BROADCAST_CANCELLED)
            addAction(ProcessingService.BROADCAST_STATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReceiver, filter)
    }

    private fun unregisterReceivers() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isEmpty() || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (!notificationPermissionExplained) {
                    notificationPermissionExplained = true
                    Toast.makeText(this,
                        "Notifications are required to show processing progress in the background. " +
                            "Please enable in Settings if needed.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestCurrentState() {
        val intent = Intent(this, ProcessingService::class.java).apply {
            action = ProcessingService.ACTION_GET_STATE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isProcessing(): Boolean {
        return binding.buttonCancel.visibility == View.VISIBLE
    }

    private fun resetUi() {
        runOnUiThread {
            binding.textFileName.text = getString(R.string.no_video_selected)
            binding.textFileDetails.text = getString(R.string.no_video_details)
            binding.textSegmentInfo.text = ""
            binding.textOutputSpecs.text = ""
            binding.buttonStart.isEnabled = false
            binding.buttonCancel.visibility = View.GONE
            binding.buttonOpenOutput.visibility = View.GONE
            binding.cardProcessing.visibility = View.GONE
            binding.progressBar.progress = 0
            binding.textProgressPercent.text = getString(R.string.progress_zero)
            binding.textStatus.text = getString(R.string.status_idle)
            binding.textEta.visibility = View.GONE
            binding.textEta.text = ""
            binding.textCompleted.visibility = View.GONE
            binding.textOutputLocation.text = getString(R.string.output_not_set)
        }
    }

    private fun handlePickedVideo(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Not every provider supports persistable grants
        }

        inputUri = uri
        outputUri = null
        lastResultUri = null
        metadata = null
        plan = null

        runOnUiThread {
            binding.textFileName.text = uri.lastPathSegment ?: getString(R.string.no_video_selected)
            binding.textFileDetails.text = getString(R.string.reading_video)
            binding.textSegmentInfo.text = ""
            binding.textOutputSpecs.text = ""
            binding.textOutputLocation.text = getString(R.string.output_not_set)
            binding.buttonStart.isEnabled = false
            binding.buttonOpenOutput.visibility = View.GONE
            binding.textCompleted.visibility = View.GONE
        }

        metadataExecutor.execute {
            try {
                val info = metadataReader.read(uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    metadata = info
                    showMetadata(info)
                    binding.buttonStart.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    metadata = null
                    plan = null
                    binding.buttonStart.isEnabled = false
                    val message = e.message ?: getString(R.string.error_metadata)
                    binding.textFileDetails.text = message
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showMetadata(info: VideoMetadata) {
        runOnUiThread {
            binding.textFileName.text = info.displayName

            val size = if (info.sizeBytes > 0L) {
                formatFileSize(info.sizeBytes)
            } else {
                getString(R.string.unknown_size)
            }
            binding.textFileDetails.text = getString(
                R.string.video_details,
                "${info.displayWidth}×${info.displayHeight}",
                formatDuration(info.durationSeconds),
                size
            )

            try {
                val selected = SegmentSelector.select(info.durationSeconds)
                plan = selected
                // textRetainedEstimate removed from layout - info shown in file details instead
                binding.textSegmentInfo.text = resources.getQuantityString(
                    R.plurals.segment_count,
                    selected.segments.size,
                    selected.segments.size
                )
            } catch (e: Exception) {
                plan = null
                binding.buttonStart.isEnabled = false
                binding.textSegmentInfo.text = e.message ?: getString(R.string.error_metadata)
            }
        }
    }

    private fun onStartClicked() {
        val info = metadata
        if (info == null) {
            Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show()
            return
        }

        if (outputUri == null) {
            pendingStart = true
            createOutputLauncher.launch(VideoPicker.buildOutputFileName(info.displayName))
            return
        }
        startProcessing()
    }

    private fun startProcessing() {
        val info = metadata ?: return
        val source = inputUri ?: return
        val destination = outputUri ?: return
        val selectedPlan = plan ?: try {
            SegmentSelector.select(info.durationSeconds).also { plan = it }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.error_metadata), Toast.LENGTH_LONG).show()
            return
        }

        runOnUiThread {
            binding.progressBar.progress = 0
            binding.textProgressPercent.text = getString(R.string.progress_percent, 0)
            binding.textStatus.text = getString(R.string.status_starting)
            binding.textEta.visibility = View.GONE
            binding.textEta.text = ""
            binding.textCompleted.visibility = View.GONE
            binding.buttonStart.isEnabled = false
            binding.buttonSelectVideo.isEnabled = false
            binding.buttonCancel.isEnabled = true
            binding.buttonCancel.visibility = View.VISIBLE
            binding.buttonOpenOutput.visibility = View.GONE
            binding.cardProcessing.visibility = View.VISIBLE
        }

        val jobId = java.util.UUID.randomUUID().toString()
        val intent = Intent(this, ProcessingService::class.java).apply {
            action = ProcessingService.ACTION_START
            putExtra(ProcessingService.EXTRA_INPUT_URI, source)
            putExtra(ProcessingService.EXTRA_OUTPUT_URI, destination)
            putExtra(ProcessingService.EXTRA_JOB_ID, jobId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun cancelProcessing() {
        val intent = Intent(this, ProcessingService::class.java).apply {
            action = ProcessingService.ACTION_CANCEL
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onProgressUpdate(intent: Intent) {
        val percent = intent.getIntExtra(ProcessingService.EXTRA_PROGRESS, 0)
        val status = intent.getStringExtra(ProcessingService.EXTRA_STATUS) ?: ""
        val etaMillis = intent.getLongExtra(ProcessingService.EXTRA_ETA_MILLIS, -1)
        val modeName = intent.getStringExtra(ProcessingService.EXTRA_MODE) ?: ""

        runOnUiThread {
            binding.progressBar.progress = percent
            binding.textProgressPercent.text = getString(R.string.progress_percent, percent)
            binding.textStatus.text = status
            if (etaMillis >= 0) {
                binding.textEta.visibility = View.VISIBLE
                binding.textEta.text = formatEta(etaMillis)
            }
            // Update output specs with mode
            val modeLabel = when (modeName) {
                "STREAM_COPY" -> getString(R.string.processing_mode_stream_copy)
                else -> getString(R.string.processing_mode_bicubic)
            }
            binding.textOutputSpecs.text = getString(R.string.output_specs, modeLabel)
        }
    }

    private fun onProcessingCompleted(intent: Intent) {
        val outputUri = intent.getParcelableExtra<Uri>(ProcessingService.EXTRA_OUTPUT_URI)
        val modeName = intent.getStringExtra(ProcessingService.EXTRA_MODE) ?: ""

        runOnUiThread {
            finishProcessingUi()
            binding.progressBar.progress = 100
            binding.textProgressPercent.text = getString(R.string.progress_percent, 100)
            binding.textStatus.text = getString(R.string.status_completed)
            binding.textEta.visibility = View.GONE
            binding.textCompleted.visibility = View.VISIBLE
            binding.textCompleted.text = getString(R.string.completed_message)
            if (outputUri != null) {
                binding.textOutputLocation.text =
                    getString(R.string.output_saved, displayNameOf(outputUri))
                this@MainActivity.lastResultUri = outputUri
            }
            this@MainActivity.outputUri = null
            binding.buttonOpenOutput.visibility = View.VISIBLE
            Toast.makeText(this@MainActivity, R.string.toast_completed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onProcessingError(intent: Intent) {
        val message = intent.getStringExtra(ProcessingService.EXTRA_ERROR) ?: "Unknown error"

        runOnUiThread {
            finishProcessingUi()
            binding.textStatus.text = getString(R.string.status_error, message)
            binding.textEta.visibility = View.GONE
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun onProcessingCancelled() {
        runOnUiThread {
            finishProcessingUi()
            binding.progressBar.progress = 0
            binding.textProgressPercent.text = getString(R.string.progress_percent, 0)
            binding.textStatus.text = getString(R.string.processing_cancelled)
            binding.textEta.visibility = View.GONE
        }
    }

    private fun onStateRestored(intent: Intent) {
        val state = intent.getParcelableExtra<JobState>(ProcessingService.EXTRA_JOB_STATE)
        state?.let { restoreUiFromState(it) }
    }

    private fun restoreUiFromState(state: JobState) {
        runOnUiThread {
            // Restore input/output URIs if available
            state.inputUri?.let { inputUri = it }
            state.outputUri?.let { outputUri = it }
            lastResultUri = state.outputUri

            // Restore metadata display
            if (state.inputDisplayName != null) {
                binding.textFileName.text = state.inputDisplayName
            }
            binding.textFileDetails.text = getString(
                R.string.video_details,
                "${state.width}×${state.height}",
                formatDuration(state.durationSeconds),
                getString(R.string.unknown_size)
            )
            binding.textSegmentInfo.text = resources.getQuantityString(
                R.plurals.segment_count,
                4,
                4
            )

            val modeLabel = when (state.mode) {
                FfmpegCommandBuilder.Mode.STREAM_COPY -> getString(R.string.processing_mode_stream_copy)
                else -> getString(R.string.processing_mode_bicubic)
            }
            binding.textOutputSpecs.text = getString(R.string.output_specs, modeLabel)

            // Restore progress UI
            if (state.isProcessing) {
                binding.progressBar.progress = state.progressPercent
                binding.textProgressPercent.text = getString(R.string.progress_percent, state.progressPercent)
                binding.textStatus.text = state.status
                binding.buttonStart.isEnabled = false
                binding.buttonSelectVideo.isEnabled = false
                binding.buttonCancel.visibility = View.VISIBLE
                binding.buttonOpenOutput.visibility = View.GONE
                binding.cardProcessing.visibility = View.VISIBLE
                binding.textCompleted.visibility = View.GONE

                state.etaMillis?.let { eta ->
                    binding.textEta.visibility = View.VISIBLE
                    binding.textEta.text = formatEta(eta)
                }
            } else if (state.isCompleted) {
                finishProcessingUi()
                binding.progressBar.progress = 100
                binding.textProgressPercent.text = getString(R.string.progress_percent, 100)
                binding.textStatus.text = getString(R.string.status_completed)
                binding.textCompleted.visibility = View.VISIBLE
                binding.textCompleted.text = getString(R.string.completed_message)
                state.outputDisplayName?.let { name ->
                    binding.textOutputLocation.text = getString(R.string.output_saved, name)
                }
                binding.buttonOpenOutput.visibility = View.VISIBLE
            } else if (state.isCancelled) {
                finishProcessingUi()
                binding.progressBar.progress = 0
                binding.textProgressPercent.text = getString(R.string.progress_percent, 0)
                binding.textStatus.text = getString(R.string.processing_cancelled)
            } else if (state.errorMessage != null) {
                finishProcessingUi()
                binding.textStatus.text = getString(R.string.status_error, state.errorMessage)
            }
        }
    }

    private fun finishProcessingUi() {
        binding.buttonStart.isEnabled = metadata != null
        binding.buttonSelectVideo.isEnabled = true
        binding.buttonCancel.visibility = View.GONE
        binding.cardProcessing.visibility = View.GONE
        binding.textEta.visibility = View.GONE
    }

    private fun handleOutputChosen(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // The CREATE_DOCUMENT grant is valid for this session regardless.
        }
        outputUri = uri
        runOnUiThread {
            binding.textOutputLocation.text = getString(R.string.output_selected, displayNameOf(uri))
        }
    }

    private fun openOutput() {
        val uri = lastResultUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, VideoPicker.MP4_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_video_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun displayNameOf(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && !cursor.isNull(index)) {
                            return cursor.getString(index)
                        }
                    }
                }
        } catch (_: Exception) {
            // Fall back to the path segment.
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun formatEta(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> getString(R.string.eta_seconds, seconds)
            seconds < 3600 -> getString(R.string.eta_minutes, seconds / 60)
            else -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                if (mins > 0) getString(R.string.eta_hours_minutes, hours, mins) else getString(R.string.eta_hours, hours)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataExecutor.shutdownNow()
        unregisterReceivers()
        // Note: We do NOT call processingManager.shutdown() here - the service continues running
    }
}