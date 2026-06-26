package com.example.pdfcore.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSObjectKey
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Compression level presets.
 * Extracted and adapted from Pdf_Tools (Karna14314).
 */
enum class CompressionLevel(val dpi: Float, val jpegQuality: Float, val description: String) {
    LOW(150f, 0.85f, "Low - Minor size reduction"),
    MEDIUM(135f, 0.70f, "Medium - Balanced"),
    HIGH(110f, 0.55f, "High - Significant reduction"),
    MAXIMUM(85f, 0.40f, "Maximum - Smallest size")
}

enum class CompressionStrategy {
    IMAGE_OPTIMIZATION,
    FULL_RERENDER
}

data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Float,
    val timeTakenMs: Long,
    val pagesProcessed: Int,
    val strategyUsed: CompressionStrategy = CompressionStrategy.IMAGE_OPTIMIZATION
) {
    val savedBytes: Long get() = originalSize - compressedSize
    val savedPercentage: Float get() = if (originalSize > 0) (savedBytes.toFloat() / originalSize) * 100 else 0f
    val wasReduced: Boolean get() = compressedSize < originalSize
}

data class CompressionProfile(
    val dpi: Float,
    val jpegQuality: Float,
    val scaleFactor: Float
)

/**
 * PDF Compression engine.
 * Extracted from Pdf_Tools (Karna14314), adapted for PdfCore.
 * Uses PdfBox-Android for dual-strategy compression:
 * 1. IMAGE_OPTIMIZATION: Optimize embedded images without re-rendering pages.
 * 2. FULL_RERENDER: Re-render each page as a compressed JPEG.
 */
class PdfCompressor {

    companion object {
        private var initialized = false
        fun ensureInitialized(context: Context) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }

    suspend fun compressPdf(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        level: CompressionLevel = CompressionLevel.MEDIUM,
        qualityPercent: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null
        var resultFile: File? = null
        val profile = profileFromSlider(qualityPercent) ?: profileFromLevel(level)

        try {
            ensureActive()
            onProgress(0.05f)

            val cacheDir = File(context.cacheDir, "compress_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            tempFile = File(cacheDir, "temp_compress_${System.currentTimeMillis()}.pdf")

            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("Cannot open input file")
            )

            val originalSize = tempFile.length()

            if (originalSize < 10 * 1024) {
                onProgress(1.0f)
                tempFile.inputStream().use { it.copyTo(outputStream) }
                outputStream.flush()
                return@withContext Result.success(CompressionResult(
                    originalSize = originalSize, compressedSize = originalSize,
                    compressionRatio = 1f, timeTakenMs = System.currentTimeMillis() - startTime,
                    pagesProcessed = countPages(tempFile),
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                ))
            }

            onProgress(0.10f)

            var strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION

            val opt = tryImageOptimization(context, tempFile, profile, onProgress)

            val isOptInefficient = opt != null && opt.length() > originalSize * 0.85f

            if (opt != null && opt.length() < originalSize) {
                resultFile = opt
                strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
            } else {
                opt?.delete()
            }

            val shouldTryRerender = level == CompressionLevel.HIGH || level == CompressionLevel.MAXIMUM || (qualityPercent ?: 50) > 60
            if (shouldTryRerender && (resultFile == null || isOptInefficient)) {
                onProgress(0.55f)
                val rerender = tryFullRerender(context, tempFile, profile) { p ->
                    onProgress(0.55f + p * 0.40f)
                }

                if (rerender != null && rerender.length() < originalSize) {
                    if (resultFile != null) {
                        if (rerender.length() < resultFile!!.length()) {
                            resultFile!!.delete()
                            resultFile = rerender
                            strategyUsed = CompressionStrategy.FULL_RERENDER
                        } else {
                            rerender.delete()
                        }
                    } else {
                        resultFile = rerender
                        strategyUsed = CompressionStrategy.FULL_RERENDER
                    }
                } else {
                    rerender?.delete()
                }
            }

            onProgress(0.95f)

            val finalFile = resultFile ?: tempFile
            finalFile.inputStream().use { it.copyTo(outputStream) }
            outputStream.flush()

            onProgress(1.0f)

            val compressedSize = finalFile.length()
            if (resultFile != null && resultFile != tempFile) resultFile!!.delete()

            return@withContext Result.success(CompressionResult(
                originalSize = originalSize,
                compressedSize = compressedSize,
                compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                timeTakenMs = System.currentTimeMillis() - startTime,
                pagesProcessed = countPages(finalFile),
                strategyUsed = strategyUsed
            ))

        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Result.failure(
                IllegalStateException("Compression failed due to low memory. Try closing other apps.", e)
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(e.message ?: "Compression failed.", e)
            )
        } finally {
            tempFile?.delete()
            if (resultFile != tempFile) resultFile?.delete()
        }
    }

    private fun pruneMetadata(document: PDDocument) {
        try {
            val catalog = document.documentCatalog
            catalog.metadata = null
            val info = document.documentInformation
            info.creator = null
            info.producer = "PdfCore"
            info.author = null
            info.title = null
            info.subject = null
            info.keywords = null
            catalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            for (i in 0 until document.numberOfPages) {
                val page = document.getPage(i)
                page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
                page.cosObject.removeItem(COSName.METADATA)
            }
        } catch (_: Exception) {}
    }

    private data class ImageContentKey(val length: Long, val md5: String)

    private fun computeImageHash(image: PDImageXObject): ImageContentKey? {
        return try {
            val length = image.cosObject.getLength().toLong()
            if (length <= 0) return null
            val digest = java.security.MessageDigest.getInstance("MD5")
            image.createInputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            val md5String = digest.digest().joinToString("") { "%02x".format(it) }
            ImageContentKey(length, md5String)
        } catch (_: Exception) { null }
    }

    private suspend fun tryImageOptimization(
        context: Context, inputFile: File, profile: CompressionProfile, onProgress: (Float) -> Unit
    ): File? {
        var document: PDDocument? = null
        val outputFile = File(context.cacheDir, "opt_${System.currentTimeMillis()}.pdf")
        return try {
            document = PDDocument.load(inputFile, MemoryUsageSetting.setupTempFileOnly())
            val totalPages = document.numberOfPages
            if (totalPages == 0) return null
            pruneMetadata(document)
            val optimizedImages = mutableMapOf<COSObjectKey, PDImageXObject>()
            val hashCache = mutableMapOf<ImageContentKey, PDImageXObject>()
            for (pageIndex in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                val resources = document.getPage(pageIndex).resources ?: continue
                optimizePageImages(document, resources, profile, optimizedImages, hashCache)
                onProgress(0.10f + (0.45f * (pageIndex + 1).toFloat() / totalPages))
            }
            document.save(outputFile)
            outputFile
        } catch (e: CancellationException) { outputFile.delete(); throw e
        } catch (_: Exception) { outputFile.delete(); null
        } finally { document?.close() }
    }

    private suspend fun optimizePageImages(
        document: PDDocument, resources: PDResources, profile: CompressionProfile,
        optimizedCache: MutableMap<COSObjectKey, PDImageXObject>,
        hashCache: MutableMap<ImageContentKey, PDImageXObject>
    ): Int {
        var count = 0
        try {
            val names = resources.xObjectNames?.toList() ?: return 0
            for (name in names) {
                currentCoroutineContext().ensureActive()
                try {
                    val item = resources.cosObject.getItem(name)
                    var cacheKey: COSObjectKey? = null
                    if (item is COSObject) {
                        cacheKey = COSObjectKey(item.objectNumber, item.generationNumber)
                        optimizedCache[cacheKey]?.let { resources.put(name, it); count++; continue }
                    }
                    val xObject = resources.getXObject(name)
                    if (xObject is PDImageXObject) {
                        val hashKey = computeImageHash(xObject)
                        if (hashKey != null) {
                            hashCache[hashKey]?.let {
                                resources.put(name, it); count++
                                if (cacheKey != null) optimizedCache[cacheKey] = it
                                continue
                            }
                        }
                        val optimized = optimizeImage(document, xObject, profile)
                        if (optimized != null) {
                            resources.put(name, optimized); count++
                            if (cacheKey != null) optimizedCache[cacheKey] = optimized
                            if (hashKey != null) hashCache[hashKey] = optimized
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { continue }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {}
        return count
    }

    private fun optimizeImage(document: PDDocument, image: PDImageXObject, profile: CompressionProfile): PDImageXObject? {
        return try {
            if (image.bitsPerComponent == 1) return null
            val originalLength = image.cosObject.getLength()
            val originalImage = image.image ?: return null
            if (originalImage.width < 128 || originalImage.height < 128) return null
            val scaleFactor = profile.scaleFactor
            val targetWidth = (originalImage.width * scaleFactor).toInt().coerceAtLeast(32)
            val targetHeight = (originalImage.height * scaleFactor).toInt().coerceAtLeast(32)
            val scaledBitmap = if (scaleFactor < 1.0f) {
                Bitmap.createScaledBitmap(originalImage, targetWidth, targetHeight, true)
            } else originalImage
            try {
                val hasSMask = image.cosObject.containsKey(COSName.SMASK)
                val hasMask = image.cosObject.containsKey(COSName.MASK)
                val optimizedImage = if (hasSMask || hasMask || scaledBitmap.hasAlpha()) {
                    val rgbBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(rgbBitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                    try {
                        val colorImage = JPEGFactory.createFromImage(document, rgbBitmap, profile.jpegQuality)
                        colorImage.cosObject.removeItem(COSName.SMASK)
                        colorImage.cosObject.removeItem(COSName.MASK)
                        colorImage
                    } catch (_: Exception) { null } finally { rgbBitmap.recycle() }
                } else {
                    JPEGFactory.createFromImage(document, scaledBitmap, profile.jpegQuality)
                }
                if (optimizedImage != null) {
                    val newLength = optimizedImage.cosObject.getLength()
                    if (newLength > 0 && newLength < originalLength) optimizedImage else null
                } else null
            } finally { if (scaledBitmap !== originalImage) scaledBitmap.recycle() }
        } catch (_: Exception) { null }
    }

    private suspend fun tryFullRerender(
        context: Context, inputFile: File, profile: CompressionProfile, onProgress: (Float) -> Unit
    ): File? {
        var outputDocument: PDDocument? = null
        var androidRenderer: android.graphics.pdf.PdfRenderer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        val outputFile = File(context.cacheDir, "rerender_${System.currentTimeMillis()}.pdf")
        return try {
            pfd = android.os.ParcelFileDescriptor.open(inputFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            androidRenderer = android.graphics.pdf.PdfRenderer(pfd)
            val totalPages = androidRenderer.pageCount
            if (totalPages == 0) return null
            outputDocument = PDDocument()
            for (pageIndex in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                var page: android.graphics.pdf.PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = androidRenderer.openPage(pageIndex)
                    val maxDim = 2048f
                    val scale = profile.dpi / 72f
                    var tw = (page.width * scale).toInt()
                    var th = (page.height * scale).toInt()
                    if (tw > maxDim || th > maxDim) {
                        val ds = maxDim / maxOf(tw, th)
                        tw = (tw * ds).toInt(); th = (th * ds).toInt()
                    }
                    tw = tw.coerceAtLeast(1); th = th.coerceAtLeast(1)
                    bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pageRect = PDRectangle(page.width.toFloat(), page.height.toFloat())
                    val newPage = PDPage(pageRect)
                    outputDocument.addPage(newPage)
                    val pdImage = JPEGFactory.createFromImage(outputDocument, bitmap, profile.jpegQuality)
                    PDPageContentStream(outputDocument, newPage, PDPageContentStream.AppendMode.OVERWRITE, true, true).use {
                        it.drawImage(pdImage, pageRect.lowerLeftX, pageRect.lowerLeftY, pageRect.width, pageRect.height)
                    }
                } catch (t: Throwable) { if (t is CancellationException) throw t
                } finally { page?.close(); bitmap?.recycle() }
                onProgress((pageIndex + 1).toFloat() / totalPages)
            }
            outputDocument.save(outputFile)
            outputFile
        } catch (e: CancellationException) { outputFile.delete(); throw e
        } catch (_: Throwable) { outputFile.delete(); null
        } finally { androidRenderer?.close(); pfd?.close(); outputDocument?.close() }
    }

    private fun countPages(file: File): Int {
        return try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { it.numberOfPages }
        } catch (_: Exception) { 0 }
    }

    fun estimateCompressedSize(originalSize: Long, qualityPercent: Int): Long {
        val clamped = qualityPercent.coerceIn(0, 100)
        val reductionFactor = 0.85f - (0.55f * (clamped / 100f))
        return (originalSize * reductionFactor).toLong()
    }

    private fun profileFromLevel(level: CompressionLevel): CompressionProfile {
        val scale = when (level) {
            CompressionLevel.LOW -> 1.0f
            CompressionLevel.MEDIUM -> 0.85f
            CompressionLevel.HIGH -> 0.70f
            CompressionLevel.MAXIMUM -> 0.55f
        }
        return CompressionProfile(dpi = level.dpi, jpegQuality = level.jpegQuality, scaleFactor = scale)
    }

    private fun profileFromSlider(qualityPercent: Int?): CompressionProfile? {
        if (qualityPercent == null) return null
        val clamped = qualityPercent.coerceIn(0, 100)
        val ratio = clamped / 100f
        return CompressionProfile(
            dpi = (150f - (65f * ratio)).coerceIn(85f, 150f),
            jpegQuality = (0.9f - (0.52f * ratio)).coerceIn(0.35f, 0.92f),
            scaleFactor = (1.0f - (0.45f * ratio)).coerceIn(0.55f, 1.0f)
        )
    }
}
