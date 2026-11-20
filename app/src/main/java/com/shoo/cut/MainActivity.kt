package com.shoo.cut

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.shoo.cut.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * Single screen, thin UI layer. All video work lives in the dedicated classes:
 * [VideoPicker], [VideoMetadataReader], [SegmentSelector], [VideoProcessor],
 * [StorageManager] and [ProcessingManager].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var metadataReader: VideoMetadataReader
    private lateinit var processingManager: ProcessingManager

    private val metadataExecutor = Executors.newSingleThreadExecutor()

    private var inputUri: Uri? = null
    /** Destination chosen for the *next* run; cleared after a successful save. */
    private var outputUri: Uri? = null
    /** Most recently written document, used by the Open button. */
    private var lastResultUri: Uri? = null
    private var metadata: VideoMetadata? = null
    private var plan: SegmentPlan? = null
    private var pendingStart = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metadataReader = VideoMetadataReader(this)
        val storage = StorageManager(this)
        processingManager = ProcessingManager(storage, VideoProcessor(this))

        binding.buttonSelectVideo.setOnClickListener {
            if (!processingManager.isProcessing()) {
                pickVideoLauncher.launch(arrayOf(VideoPicker.VIDEO_MIME_TYPE))
            }
        }
        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonCancel.setOnClickListener { processingManager.cancel() }
        binding.buttonOpenOutput.setOnClickListener { openOutput() }

        resetUi()
    }

    private fun resetUi() {
        binding.textFileName.text = getString(R.string.no_video_selected)
        binding.textFileDetails.text = getString(R.string.no_video_details)
        binding.textRetainedEstimate.text = getString(R.string.retained_placeholder)
        binding.textSegmentInfo.text = ""
        binding.buttonStart.isEnabled = false
        binding.buttonCancel.visibility = View.GONE
        binding.buttonOpenOutput.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.textProgressPercent.text = getString(R.string.progress_percent, 0)
        binding.textStatus.text = getString(R.string.status_idle)
        binding.textCompleted.visibility = View.GONE
        binding.textOutputLocation.text = getString(R.string.output_not_set)
    }

    private fun handlePickedVideo(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Not every provider supports persistable grants; the live grant is enough.
        }

        inputUri = uri
        outputUri = null
        lastResultUri = null
        metadata = null
        plan = null

        binding.textFileName.text = uri.lastPathSegment ?: getString(R.string.no_video_selected)
        binding.textFileDetails.text = getString(R.string.reading_video)
        binding.textRetainedEstimate.text = getString(R.string.retained_placeholder)
        binding.textSegmentInfo.text = ""
        binding.textOutputLocation.text = getString(R.string.output_not_set)
        binding.buttonStart.isEnabled = false
        binding.buttonOpenOutput.visibility = View.GONE
        binding.textCompleted.visibility = View.GONE

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
            binding.textRetainedEstimate.text = getString(
                R.string.retained_estimate,
                formatDuration(selected.actualRetainedSeconds),
                formatPercent(selected.effectiveRetentionPercent)
            )
            binding.textSegmentInfo.text = resources.getQuantityString(
                R.plurals.segment_count,
                selected.segments.size,
                selected.segments.size
            )
        } catch (e: Exception) {
            plan = null
            binding.buttonStart.isEnabled = false
            binding.textRetainedEstimate.text = e.message ?: getString(R.string.error_metadata)
        }
    }

    private fun onStartClicked() {
        val info = metadata
        if (info == null) {
            Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (processingManager.isProcessing()) return

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

        binding.progressBar.progress = 0
        binding.textProgressPercent.text = getString(R.string.progress_percent, 0)
        binding.textStatus.text = getString(R.string.status_starting)
        binding.textCompleted.visibility = View.GONE
        binding.buttonStart.isEnabled = false
        binding.buttonSelectVideo.isEnabled = false
        binding.buttonCancel.isEnabled = true
        binding.buttonCancel.visibility = View.VISIBLE
        binding.buttonOpenOutput.visibility = View.GONE

        processingManager.start(
            source,
            destination,
            info,
            selectedPlan,
            object : ProcessingManager.Listener {
                override fun onProgress(percent: Int, status: String) {
                    binding.progressBar.progress = percent
                    binding.textProgressPercent.text = getString(R.string.progress_percent, percent)
                    binding.textStatus.text = status
                }

                override fun onSuccess(outputUri: Uri) {
                    finishProcessingUi()
                    binding.progressBar.progress = 100
                    binding.textProgressPercent.text = getString(R.string.progress_percent, 100)
                    binding.textStatus.text = getString(R.string.status_completed)
                    binding.textCompleted.visibility = View.VISIBLE
                    binding.textCompleted.text = getString(R.string.completed_message)
                    binding.textOutputLocation.text =
                        getString(R.string.output_saved, displayNameOf(outputUri))
                    this@MainActivity.lastResultUri = outputUri
                    // Ask for a new destination before the next run so the
                    // finished file can never be overwritten silently.
                    this@MainActivity.outputUri = null
                    binding.buttonOpenOutput.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, R.string.toast_completed, Toast.LENGTH_LONG).show()
                }

                override fun onError(message: String) {
                    finishProcessingUi()
                    binding.textStatus.text = getString(R.string.status_error, message)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }

                override fun onCancelled() {
                    finishProcessingUi()
                    binding.progressBar.progress = 0
                    binding.textProgressPercent.text = getString(R.string.progress_percent, 0)
                    binding.textStatus.text = getString(R.string.processing_cancelled)
                }
            }
        )
    }

    private fun finishProcessingUi() {
        binding.buttonStart.isEnabled = metadata != null
        binding.buttonSelectVideo.isEnabled = true
        binding.buttonCancel.visibility = View.GONE
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
        binding.textOutputLocation.text = getString(R.string.output_selected, displayNameOf(uri))
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
        try {
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

    override fun onDestroy() {
        super.onDestroy()
        metadataExecutor.shutdownNow()
        if (::processingManager.isInitialized) {
            processingManager.shutdown()
        }
    }
}
