package com.example.pdfcore.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.OutputStream

/**
 * Page sizes for PDF creation.
 * Extracted from Pdf_Tools (Karna14314).
 */
enum class PageSize(val rectangle: PDRectangle) {
    A4(PDRectangle.A4),
    LETTER(PDRectangle.LETTER),
    LEGAL(PDRectangle.LEGAL),
    FIT_IMAGE(PDRectangle.A4)
}

/**
 * Image-to-PDF conversion engine.
 * Extracted from Pdf_Tools (Karna14314), adapted for PdfCore.
 */
class ImageConverter {

    companion object {
        private var initialized = false
        fun ensureInitialized(context: Context) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }

    /**
     * Convert multiple images to a single PDF.
     */
    suspend fun imagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputStream: OutputStream,
        pageSize: PageSize = PageSize.A4,
        quality: Int = 85,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        ensureInitialized(context)

        if (imageUris.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("No images provided")
            )
        }

        val document = PDDocument()

        try {
            imageUris.forEachIndexed { index, uri ->
                val bitmap = loadBitmap(context, uri)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Cannot load image: $uri")
                    )

                try {
                    addImageAsPage(document, bitmap, pageSize)
                } finally {
                    bitmap.recycle()
                }

                onProgress((index + 1).toFloat() / imageUris.size)
                yield()
            }

            document.save(outputStream)

            Result.success(imageUris.size)
        } catch (e: OutOfMemoryError) {
            Result.failure(
                IllegalStateException("Not enough memory. Try with fewer images.")
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document.close()
        }
    }

    /**
     * Load a bitmap from a URI with memory-efficient options.
     */
    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                val maxDimension = 2048
                var sampleSize = 1
                while (options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension) {
                    sampleSize *= 2
                }

                context.contentResolver.openInputStream(uri)?.use { stream2 ->
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream2, null, decodeOptions)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Add an image as a new page in the document.
     */
    private fun addImageAsPage(
        document: PDDocument,
        bitmap: Bitmap,
        pageSize: PageSize
    ) {
        val pageRect = if (pageSize == PageSize.FIT_IMAGE) {
            PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat())
        } else {
            pageSize.rectangle
        }

        val page = PDPage(pageRect)
        document.addPage(page)

        val pixelCount = bitmap.width * bitmap.height
        val pdImage = if (pixelCount > 1024 * 1024) {
            JPEGFactory.createFromImage(document, bitmap, 0.9f)
        } else {
            LosslessFactory.createFromImage(document, bitmap)
        }

        val pageWidth = pageRect.width
        val pageHeight = pageRect.height
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        val scale = minOf(pageWidth / imageWidth, pageHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        val x = (pageWidth - scaledWidth) / 2
        val y = (pageHeight - scaledHeight) / 2

        PDPageContentStream(document, page).use { contentStream ->
            contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight)
        }
    }
}
