package com.example.pdfcore.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * File info holder.
 */
data class PdfFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val formattedSize: String
)

/**
 * File utility functions.
 */
object FileUtil {

    fun getFileInfo(context: Context, uri: Uri): PdfFileInfo? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                    var size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L

                    // Fallback: some providers return 0 for SIZE — compute from stream
                    if (size <= 0L) {
                        size = try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                stream.available().toLong().takeIf { it > 0 }
                                    ?: run {
                                        var count = 0L
                                        val buf = ByteArray(8192)
                                        var n: Int
                                        while (stream.read(buf).also { n = it } != -1) count += n
                                        count
                                    }
                            } ?: 0L
                        } catch (_: Exception) { 0L }
                    }

                    PdfFileInfo(
                        uri = uri,
                        name = name ?: "Unknown",
                        size = size,
                        formattedSize = formatFileSize(size)
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
            else -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
        }
    }

    fun generateOutputFileName(prefix: String): String {
        val timestamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        return "${prefix}_$timestamp.pdf"
    }
}
