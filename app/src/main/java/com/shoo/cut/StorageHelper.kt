package com.shoo.cut

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.content.ContentUris
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

object StorageHelper {

    /** Request code for picking a video via SAF */
    const val REQUEST_PICK_VIDEO = 1001

    /** Request code for picking output directory via SAF */
    const val REQUEST_PICK_OUTPUT_DIR = 1002

    /**
     * Creates an intent to pick a video file using Storage Access Framework.
     */
    fun createPickVideoIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
    }

    /**
     * Creates an intent to pick/create an output directory using SAF.
     */
    fun createPickOutputDirIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, "Shoo Auto Cut")
        }
    }

    /**
     * Gets the file path from a content URI. Returns null if not directly accessible.
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        val contentResolver: ContentResolver = context.contentResolver
        var projection: Array<String>? = null

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)

            if ("com.android.externalstorage.documents".equals(uri.authority, ignoreCase = true)) {
                val splits = docId.split(":")
                if (splits.size >= 2) {
                    val type = splits[0]
                    val path = splits[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        return Environment.getExternalStorageDirectory().absolutePath + "/" + path
                    }
                }
            } else if ("com.android.providers.downloads.documents".equals(uri.authority, ignoreCase = true)) {
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"),
                    java.lang.Long.valueOf(docId)
                )
                return getDataColumn(context, contentUri, null, null)
            } else if ("com.android.providers.media.documents".equals(uri.authority, ignoreCase = true)) {
                val splits = docId.split(":")
                if (splits.size >= 2) {
                    val type = splits[0]
                    val id = splits[1]
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(id)
                    projection = arrayOf("_data")
                    val uri2 = when {
                        "image".equals(type, ignoreCase = true) ->
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video".equals(type, ignoreCase = true) ->
                            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio".equals(type, ignoreCase = true) ->
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> null
                    }
                    if (uri2 != null) {
                        val cursor = contentResolver.query(uri2, projection, selection, selectionArgs, null)
                        val columnIndex = cursor?.getColumnIndexOrThrow("_data") ?: -1
                        if (cursor != null && cursor.moveToFirst() && columnIndex >= 0) {
                            val path = cursor.getString(columnIndex)
                            cursor.close()
                            return path
                        }
                        cursor?.close()
                    }
                }
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            projection = arrayOf("_data")
            val cursor = contentResolver.query(uri, projection, null, null, null)
            val columnIndex = cursor?.getColumnIndexOrThrow("_data") ?: -1
            if (cursor != null && cursor.moveToFirst() && columnIndex >= 0) {
                val path = cursor.getString(columnIndex)
                cursor.close()
                return path
            }
            cursor?.close()
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        return null
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("_data"),
            selection,
            selectionArgs,
            null
        )
        val columnIndex = cursor?.getColumnIndexOrThrow("_data") ?: -1
        if (cursor != null && cursor.moveToFirst() && columnIndex >= 0) {
            val path = cursor.getString(columnIndex)
            cursor.close()
            return path
        }
        cursor?.close()
        return null
    }

    /**
     * Gets a human-readable file size string.
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024.0 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }
        return String.format("%.1f %s", size, units[unitIndex])
    }

    /**
     * Checks if a file extension is a video format.
     */
    fun isVideoFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.').lowercase()
        return MimeTypeMap.getFileExtensionFromUrl(filename).let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?.startsWith("video/") == true
    }

    /**
     * Generates a sensible output filename based on source name, cut percentage, and direction.
     */
    fun generateOutputFilename(
        sourceName: String,
        cutPercentage: Double,
        cutDirection: CutDirection
    ): String {
        val stem = sourceName.substringBeforeLast('.').replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        val percent = cutPercentage.toInt()
        val direction = when (cutDirection) {
            CutDirection.REMOVE_BEGINNING -> "removebeg"
            CutDirection.REMOVE_ENDING -> "removeend"
        }
        return "${stem}_cut_${percent}pct_$direction.mp4"
    }
}