package com.shoo.cut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that handles 4-part stream-copy processing independently of
 * the Activity lifecycle. Continues while the app is minimized or the screen is off,
 * subject to Android background restrictions.
 */
class ProcessingService : Service() {

    private val TAG = "ProcessingService"

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

        /**
         * Last known job state for this process. Lets a recreated Activity restore
         * the UI without starting the (possibly absent) foreground service.
         */
        @Volatile
        var lastState: JobState? = null
            private set
    }

    private lateinit var processingManager: ProcessingManager
    private lateinit var storageManager: StorageManager
    private lateinit var metadataReader: VideoMetadataReader
    private lateinit var notificationManager: NotificationManager

    private var currentJobState: JobState? = null
    private var jobStartTime = 0L
    private var lastProgressUpdate = 0L

    /**
     * True from a start request until the job reaches a terminal state. Used to
     * reject duplicate starts and to avoid stopping the service while metadata is
     * still being read.
     */
    private val startInProgress = AtomicBoolean(false)

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

        Log.d(TAG, "onStartCommand action=${intent.action}")

        when (intent.action) {
            ACTION_START -> {
                if (processingManager.isProcessing() || !startInProgress.compareAndSet(false, true)) {
                    Log.w(TAG, "Ignoring duplicate start request")
                    return START_NOT_STICKY
                }
                handleStartProcessing(intent)
            }

            ACTION_CANCEL -> {
                if (hasActiveJob()) {
                    handleCancel()
                } else {
                    stopSelf(startId)
                }
            }

            ACTION_GET_STATE -> {
                sendStateBroadcast()
                if (!hasActiveJob()) {
                    stopSelf(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun hasActiveJob(): Boolean =
        startInProgress.get() || processingManager.isProcessing() || currentJobState?.isProcessing == true

    private fun handleStartProcessing(intent: Intent) {
        val inputUri = intent.getParcelableExtra<Uri>(EXTRA_INPUT_URI)
        val outputFolderUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_FOLDER_URI)
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()

        Log.d(TAG, "handleStartProcessing input=$inputUri folder=$outputFolderUri job=$jobId")

        if (inputUri == null || outputFolderUri == null) {
            startInProgress.set(false)
            sendErrorBroadcast("Missing input or output folder URI")
            stopForegroundCompat()
            stopSelf()
            return
        }

        currentJobState = null
        lastState = null

        // Must enter the foreground within a few seconds of startForegroundService().
        startForegroundCompat(
            buildNotification(
                status = "Preparing video…",
                progress = 0,
                indeterminate = true,
                ongoing = true,
                showProgress = true,
                showCancel = true,
                bigText = "Preparing video…"
            )
        )

        // Reading metadata must not run on the main thread.
        Thread {
            try {
                val metadata = metadataReader.read(inputUri)
                val plan = SegmentSelector.select(metadata.durationSeconds)

                jobStartTime = System.currentTimeMillis()
                lastProgressUpdate = jobStartTime

                publishState(JobState.createInitial(jobId, inputUri, outputFolderUri, metadata))
                showNotification(currentJobState!!)

                processingManager.start(
                    inputUri,
                    outputFolderUri,
                    metadata,
                    plan,
                    object : ProcessingManager.Listener {
                        override fun onProgress(percent: Int, status: String) {
                            val now = System.currentTimeMillis()
                            val etaMillis = calculateEta(percent, now)
                            val state = currentJobState ?: return
                            val updated = state.copy(
                                progressPercent = percent,
                                status = status,
                                etaMillis = etaMillis
                            )
                            publishState(updated)

                            // Throttle notification updates to ~1 second, but always
                            // broadcast progress so the Activity stays current.
                            if (now - lastProgressUpdate >= 1000 || percent >= 100) {
                                lastProgressUpdate = now
                                showNotification(updated)
                            }
                            sendProgressBroadcast(percent, status, etaMillis)
                        }

                        override fun onSegmentComplete(segmentIndex: Int, outputUri: Uri) {
                            val state = currentJobState ?: return
                            val updated = state.copy(
                                outputFileNames = state.outputFileNames.mapIndexed { index, name ->
                                    if (index == segmentIndex) "$name ✓" else name
                                }
                            )
                            publishState(updated)
                            showNotification(updated)
                            sendSegmentCompleteBroadcast(segmentIndex, outputUri)
                        }

                        override fun onAllComplete(outputUris: List<Uri>) {
                            val state = currentJobState
                            if (state != null) {
                                val updated = state.copy(
                                    progressPercent = 100,
                                    status = "All 4 parts saved",
                                    etaMillis = 0L,
                                    isProcessing = false,
                                    isCompleted = true
                                )
                                publishState(updated)
                                showNotification(updated)
                            }
                            sendCompletedBroadcast(outputUris)
                            finishService()
                        }

                        override fun onError(message: String) {
                            val state = currentJobState
                            if (state != null) {
                                val updated = state.copy(
                                    status = "Error: $message",
                                    etaMillis = null,
                                    isProcessing = false,
                                    errorMessage = message
                                )
                                publishState(updated)
                                showNotification(updated)
                            }
                            sendErrorBroadcast(message)
                            finishService()
                        }

                        override fun onCancelled() {
                            val state = currentJobState
                            if (state != null) {
                                val updated = state.copy(
                                    status = "Cancelled",
                                    etaMillis = null,
                                    isProcessing = false,
                                    isCancelled = true
                                )
                                publishState(updated)
                                showNotification(updated)
                            }
                            sendCancelledBroadcast()
                            finishService()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start processing", e)
                startInProgress.set(false)
                sendErrorBroadcast(e.message ?: "Failed to start processing")
                notificationManager.cancel(NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun handleCancel() {
        if (processingManager.isProcessing()) {
            processingManager.cancel()
        }
    }

    private fun finishService() {
        startInProgress.set(false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun calculateEta(percent: Int, now: Long): Long? {
        if (percent <= 0 || jobStartTime == 0L) return null
        val elapsed = now - jobStartTime
        if (elapsed < 2000L) return null
        val estimatedTotal = (elapsed * 100.0 / percent).toLong()
        val remaining = estimatedTotal - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    private fun publishState(state: JobState) {
        currentJobState = state
        lastState = state
    }

    private fun sendProgressBroadcast(percent: Int, status: String, etaMillis: Long?) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, percent)
            putExtra(EXTRA_STATUS, status)
            etaMillis?.let { putExtra(EXTRA_ETA_MILLIS, it) }
        }
        broadcast(intent)
    }

    private fun sendSegmentCompleteBroadcast(segmentIndex: Int, outputUri: Uri) {
        val intent = Intent(BROADCAST_SEGMENT_COMPLETE).apply {
            putExtra(EXTRA_SEGMENT_INDEX, segmentIndex)
            putExtra(EXTRA_OUTPUT_URIS, outputUri)
        }
        broadcast(intent)
    }

    private fun sendCompletedBroadcast(outputUris: List<Uri>) {
        val intent = Intent(BROADCAST_COMPLETED).apply {
            putParcelableArrayListExtra(EXTRA_OUTPUT_URIS, ArrayList(outputUris))
        }
        broadcast(intent)
    }

    private fun sendErrorBroadcast(message: String) {
        val intent = Intent(BROADCAST_ERROR).apply {
            putExtra(EXTRA_ERROR, message)
        }
        broadcast(intent)
    }

    private fun sendCancelledBroadcast() {
        broadcast(Intent(BROADCAST_CANCELLED))
    }

    private fun sendStateBroadcast() {
        val state = currentJobState ?: lastState ?: return
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_JOB_STATE, state)
        }
        broadcast(intent)
    }

    private fun broadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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
        val isProcessing = state.isProcessing

        val bigText = when {
            state.isCompleted -> "All 4 parts saved in the selected folder."
            state.isCancelled -> "Processing cancelled."
            state.errorMessage != null -> "Error: ${state.errorMessage}"
            state.etaMillis != null -> "${state.status}\n${formatEta(state.etaMillis)} remaining"
            else -> "${state.status}\nEstimating…"
        }

        val notification = buildNotification(
            status = state.status,
            progress = progress,
            indeterminate = isProcessing && progress == 0,
            ongoing = isProcessing,
            showProgress = isProcessing,
            showCancel = isProcessing,
            bigText = bigText
        )

        if (isProcessing) {
            startForegroundCompat(notification)
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        status: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
        showProgress: Boolean,
        showCancel: Boolean,
        bigText: String?
    ): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Shoo Cut")
            .setContentText(if (showProgress) "$progress% • $status" else status)
            .setOngoing(ongoing)
            .setPriority(
                if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (showProgress) {
            builder.setProgress(100, progress, indeterminate)
        }

        if (showCancel) {
            val cancelIntent = Intent(this, ProcessingService::class.java).apply {
                action = ACTION_CANCEL
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                0,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelPendingIntent
                ).build()
            )
        }

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Detaches the notification so a completed/error state stays visible. */
    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_DETACH)
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
