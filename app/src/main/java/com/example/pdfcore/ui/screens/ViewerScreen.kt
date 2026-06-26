package com.example.pdfcore.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pdfcore.ui.components.ActionButton
import com.example.pdfcore.ui.components.EmptyState
import com.example.pdfcore.ui.components.ToolTopBar
import com.pdfview.PDFView
import java.io.File

/**
 * PDF Viewer screen.
 * Wraps the core pdfview-android library (PDFView / SubsamplingScaleImageView)
 * in a Compose layout, using Android's built-in PdfRenderer for rendering.
 */
@Composable
fun ViewerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var fileName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // File picker
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            errorMessage = null
            selectedUri = uri
            // Copy the URI content to a local cache file so PDFView can access it
            pdfFile = copyUriToCache(context, uri)
            if (pdfFile == null) {
                errorMessage = "Could not open this PDF file"
            }

            // Get display name
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) fileName = c.getString(nameIdx) ?: "PDF"
                }
            }
        }
    }
    DisposableEffect(pdfFile) {
        onDispose {
            pdfFile?.delete()
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = if (fileName.isNotEmpty()) fileName else "View PDF",
                onNavigateBack = onNavigateBack
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (pdfFile != null && errorMessage == null) {
                    // Render the PDF using the core library's PDFView
                    val file = pdfFile!!
                    AndroidView(
                        factory = { ctx ->
                            PDFView(ctx).apply {
                                fromFile(file)
                                    .scale(8f)
                                    .show()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (errorMessage != null) {
                    EmptyState(
                        icon = Icons.Default.Error,
                        title = "Cannot Open PDF",
                        subtitle = errorMessage!!,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    EmptyState(
                        icon = Icons.Default.PictureAsPdf,
                        title = "No PDF Selected",
                        subtitle = "Select a PDF file to view it",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // Bottom action — only shown when no PDF is loaded
            if (pdfFile == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        ActionButton(
                            text = "Open PDF",
                            onClick = { pickPdfLauncher.launch("application/pdf") },
                            icon = Icons.Default.FolderOpen
                        )
                    }
                }
            } else {
                // Floating action button to pick another file
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        ActionButton(
                            text = "Open Another PDF",
                            onClick = { pickPdfLauncher.launch("application/pdf") },
                            icon = Icons.Default.FolderOpen
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copies a SAF Uri to a local cache file so PDFView (which needs a java.io.File) can read it.
 */
private fun copyUriToCache(context: Context, uri: Uri): File? {
    return try {
        val cacheFile = File(context.cacheDir, "viewer_temp_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
    } catch (_: Exception) {
        null
    }
}
