package com.shoo.cut

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import java.io.Serializable

/**
 * Persistent state of a processing job, used to restore UI when Activity is recreated
 * or when user reopens the app during background processing.
 */
data class JobState(
    val jobId: String,
    val inputUriString: String?,
    val outputUriString: String?,
    val inputDisplayName: String?,
    val outputDisplayName: String?,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val mode: FfmpegCommandBuilder.Mode,
    val progressPercent: Int,
    val status: String,
    val etaMillis: Long?,
    val isProcessing: Boolean,
    val isCompleted: Boolean,
    val isCancelled: Boolean,
    val errorMessage: String?,
    val startTimeMillis: Long
) : Parcelable {

    constructor(parcel: Parcel) : this(
        jobId = parcel.readString() ?: "",
        inputUriString = parcel.readString(),
        outputUriString = parcel.readString(),
        inputDisplayName = parcel.readString(),
        outputDisplayName = parcel.readString(),
        durationSeconds = parcel.readDouble(),
        width = parcel.readInt(),
        height = parcel.readInt(),
        hasAudio = parcel.readInt() == 1,
        mode = FfmpegCommandBuilder.Mode.valueOf(parcel.readString() ?: "BICUBIC_REENCODE"),
        progressPercent = parcel.readInt(),
        status = parcel.readString() ?: "",
        etaMillis = if (parcel.readInt() == 1) parcel.readLong() else null,
        isProcessing = parcel.readInt() == 1,
        isCompleted = parcel.readInt() == 1,
        isCancelled = parcel.readInt() == 1,
        errorMessage = parcel.readString(),
        startTimeMillis = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(jobId)
        parcel.writeString(inputUriString)
        parcel.writeString(outputUriString)
        parcel.writeString(inputDisplayName)
        parcel.writeString(outputDisplayName)
        parcel.writeDouble(durationSeconds)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeInt(if (hasAudio) 1 else 0)
        parcel.writeString(mode.name)
        parcel.writeInt(progressPercent)
        parcel.writeString(status)
        if (etaMillis != null) {
            parcel.writeInt(1)
            parcel.writeLong(etaMillis)
        } else {
            parcel.writeInt(0)
        }
        parcel.writeInt(if (isProcessing) 1 else 0)
        parcel.writeInt(if (isCompleted) 1 else 0)
        parcel.writeInt(if (isCancelled) 1 else 0)
        parcel.writeString(errorMessage)
        parcel.writeLong(startTimeMillis)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<JobState> = object : Parcelable.Creator<JobState> {
            override fun createFromParcel(parcel: Parcel): JobState = JobState(parcel)
            override fun newArray(size: Int): Array<JobState?> = arrayOfNulls(size)
        }

        fun createInitial(
            jobId: String,
            inputUri: Uri,
            outputUri: Uri,
            metadata: VideoMetadata,
            mode: FfmpegCommandBuilder.Mode
        ): JobState {
            return JobState(
                jobId = jobId,
                inputUriString = inputUri.toString(),
                outputUriString = outputUri.toString(),
                inputDisplayName = metadata.displayName,
                outputDisplayName = null,
                durationSeconds = metadata.durationSeconds,
                width = metadata.width,
                height = metadata.height,
                hasAudio = metadata.hasAudio,
                mode = mode,
                progressPercent = 0,
                status = "Starting…",
                etaMillis = null,
                isProcessing = true,
                isCompleted = false,
                isCancelled = false,
                errorMessage = null,
                startTimeMillis = System.currentTimeMillis()
            )
        }
    }

    val inputUri: Uri? = inputUriString?.let { Uri.parse(it) }
    val outputUri: Uri? = outputUriString?.let { Uri.parse(it) }
}