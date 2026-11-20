package com.shoo.cut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Foreground service that handles 4-segment stream-copy processing independently of the Activity lifecycle.
 * Continues processing when app is minimized, screen is off, or Activity is destroyed.
 */
class ProcessingService : Service() {

    // Broadcast actions - using LocalBroadcastManager for app-internal communication
    companion object {
        const val ACTION_START = "com.shoo.cut.action.START_PROCESSING"
        const val ACTION_CANCEL = "com.shoo.cut.action.CANCEL_PROCESSING"
        const val ACTION_GET_STATE = "com.shoo.cut.action.GET_STATE"

        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_OUTPUT_FOLDER_URI = "output_folder_uri"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_JOB_STATE = "job_state"

        const val BROADCAST_PROGRESS = "com.shoo.cut.broadcast.PROGRESS"
        const val BROADCAST_SEGMENT_COMPLETE = "com.shoo.cut.broadcast.SEGMENT_COMPLETE"
        const val BROADCAST_COMPLETED = "com.shoo.cut.broadcast.COMPLETED"
        const val BROADCAST_ERROR = "com.shoo.cut.broadcast.ERROR"
        const val BROADCAST_CANCELLED = "com.shoo.cut.broadcast.CANCELLED"
        const val BROADCAST_STATE = "com.shoo.cut.broadcast.STATE"

        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ETA_MILLIS = "eta_millis"
        const val EXTRA_SEGMENT_INDEX = "segment_index"
        const val EXTRA_OUTPUT_URIS = "output_uris"
        const val EXTRA_ERROR = "error"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "video_processing"
        private const val CHANNEL_NAME = "Video Processing"
    }

    private lateinit var processingManager: ProcessingManager
    private lateinit var storageManager: StorageManager
    private lateinit var metadataReader: VideoMetadataReader
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentJobState: JobState? = null
    private var jobStartTime: Long = 0
    private var lastProgressUpdate: Long = 0
    private var currentSegmentIndex = 0

    override fun onCreate() {
        super.onCreate()
        storageManager = StorageManager(this)
        metadataReader = VideoMetadataReader(this)
        processingManager = ProcessingManager(storageManager, VideoProcessor())
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> handleStartProcessing(intent)
            ACTION_CANCEL -> handleCancel()
            ACTION_GET_STATE -> {
                // For state query, reply directly without starting foreground processing
                sendStateBroadcast()
            }
        }

        return START_NOT_STICKY
    }

    private fun handleStartProcessing(intent: Intent) {
        val inputUri = intent.getParcelableExtra<Uri>(EXTRA_INPUT_URI)
        val outputFolderUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_FOLDER_URI)
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()

        if (inputUri == null || outputFolderUri == null) {
            sendErrorBroadcast("Missing input or output folder URI")
            stopSelf()
            return
        }

        // Read metadata on background thread
        Thread {
            try {
                val metadata = metadataReader.read(inputUri)
                val plan = SegmentSelector.select(metadata.durationSeconds)

                jobStartTime = System.currentTimeMillis()
                lastProgressUpdate = jobStartTime
                currentSegmentIndex = 0

                currentJobState = JobState.createInitial(jobId, inputUri, outputFolderUri, metadata)

                // Show initial notification
                showNotification(currentJobState!!)

                // Start processing on worker thread
                processingManager.start(
                    inputUri,
                    outputFolderUri,
                    metadata,
                    plan,
                    object : ProcessingManager.Listener {
                        override fun onProgress(percent: Int, status: String) {
                            val now = System.currentTimeMillis()
                            val etaMillis = calculateEta(percent, now)
                            currentJobState = currentJobState?.copy(
                                progressPercent = percent,
                                status = status,
                                etaMillis = etaMillis
                            )
                            // Throttle notification updates to ~1 second
                            if (now - lastProgressUpdate >= 1000 || percent >= 100) {
                                lastProgressUpdate = now
                                showNotification(currentJobState!!)
                            }
                            sendProgressBroadcast(percent, status, etaMillis)
                        }

                        override fun onSegmentComplete(segmentIndex: Int, outputUri: Uri) {
                            currentSegmentIndex = segmentIndex
                            currentJobState = currentJobState?.copy(
                                outputFileNames = currentJobState!!.outputFileNames.mapIndexed { i, name ->
                                    if (i == segmentIndex) "$name ✓" else name
                                }
                            )
                            showNotification(currentJobState!!)
                            sendSegmentCompleteBroadcast(segmentIndex, outputUri)
                        }

                        override fun onAllComplete(outputUris: List<Uri>) {
                            currentJobState = currentJobState?.copy(
                                progressPercent = 100,
                                status = "All 4 segments complete",
                                etaMillis = 0,
                                isProcessing = false,
                                isCompleted = true
                            )
                            showNotification(currentJobState!!)
                            sendCompletedBroadcast(outputUris)
                            stopForeground(true)
                            stopSelf()
                        }

                        override fun onError(message: String) {
                            currentJobState = currentJobState?.copy(
                                status = "Error: $message",
                                etaMillis = null,
                                isProcessing = false,
                                errorMessage = message
                            )
                            showNotification(currentJobState!!)
                            sendErrorBroadcast(message)
                            stopForeground(true)
                            stopSelf()
                        }

                        override fun onCancelled() {
                            currentJobState = currentJobState?.copy(
                                status = "Cancelled",
                                etaMillis = null,
                                isProcessing = false,
                                isCancelled = true
                            )
                            showNotification(currentJobState!!)
                            sendCancelledBroadcast()
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                )
            } catch (e: Exception) {
                sendErrorBroadcast(e.message ?: "Failed to start processing")
                stopSelf()
            }
        }.start()
    }

    private fun handleCancel() {
        if (currentJobState?.isProcessing == true) {
            processingManager.cancel()
        }
    }

    private fun calculateEta(percent: Int, now: Long): Long? {
        if (percent <= 0 || jobStartTime == 0L) return null
        val elapsed = now - jobStartTime
        if (elapsed < 2000L) return null // Need some progress data first
        val estimatedTotal = (elapsed * 100.0 / percent).toLong()
        val remaining = estimatedTotal - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    private fun sendProgressBroadcast(percent: Int, status: String, etaMillis: Long?) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, percent)
            putExtra(EXTRA_STATUS, status)
            etaMillis?.let { putExtra(EXTRA_ETA_MILLIS, it) }
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendSegmentCompleteBroadcast(segmentIndex: Int, outputUri: Uri) {
        val intent = Intent(BROADCAST_SEGMENT_COMPLETE).apply {
            putExtra(EXTRA_SEGMENT_INDEX, segmentIndex)
            putExtra(EXTRA_OUTPUT_URIS, outputUri)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendCompletedBroadcast(outputUris: List<Uri>) {
        val intent = Intent(BROADCAST_COMPLETED).apply {
            putParcelableArrayListExtra(EXTRA_OUTPUT_URIS, ArrayList(outputUris))
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(message: String) {
        val intent = Intent(BROADCAST_ERROR).apply {
            putExtra(EXTRA_ERROR, message)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendCancelledBroadcast() {
        val intent = Intent(BROADCAST_CANCELLED)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendStateBroadcast() {
        currentJobState?.let { state ->
            val intent = Intent(BROADCAST_STATE).apply {
                putExtra(EXTRA_JOB_STATE, state)
            }
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows video processing progress"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(state: JobState) {
        val progress = state.progressPercent.coerceIn(0, 100)
        val isIndeterminate = state.isProcessing && progress == 0

        val cancelIntent = Intent(this, ProcessingService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Shoo Cut")
            .setContentText("$progress% • ${state.status}")
            .setOngoing(state.isProcessing)
            .setPriority(if (state.isProcessing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setProgress(100, progress, isIndeterminate)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(!state.isProcessing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.isProcessing) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelPendingIntent
                ).build()
            )
        }

        // Add ETA to notification
        val style = NotificationCompat.BigTextStyle()
        if (state.etaMillis != null) {
            val etaText = formatEta(state.etaMillis!!)
            style.bigText("${state.status}\n$etaText remaining")
        } else if (state.isProcessing) {
            style.bigText("${state.status}\nEstimating…")
        } else if (state.isCompleted) {
            style.bigText("All 4 segments complete")
            builder.setContentText("Completed")
        } else if (state.isCancelled) {
            style.bigText("Processing cancelled")
            builder.setContentText("Cancelled")
        } else if (state.errorMessage != null) {
            style.bigText("Error: ${state.errorMessage}")
            builder.setContentText("Error: ${state.errorMessage}")
        }
        builder.setStyle(style)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    private fun formatEta(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                if (mins > 0) "${hours}h ${mins}min" else "${hours}h"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        processingManager.shutdown()
    }
}