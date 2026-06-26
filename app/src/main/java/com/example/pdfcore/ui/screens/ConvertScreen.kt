package com.example.pdfcore.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.pdfcore.engine.ImageConverter
import com.example.pdfcore.engine.PageSize
import com.example.pdfcore.ui.components.*
import com.example.pdfcore.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Image info holder.
 */
private data class ImageInfo(
    val uri: Uri,
    val name: String,
    val originalIndex: Int = 0
)

/**
 * Image-to-PDF conversion screen.
 * Adapted from Pdf_Tools ConvertScreen.kt — rewired to use PdfCore engine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageConverter = remember { ImageConverter() }

    // State
    var selectedImages by remember { mutableStateOf<List<ImageInfo>>(emptyList()) }
    var pageSize by remember { mutableStateOf(PageSize.A4) }
    var quality by remember { mutableStateOf(85f) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var selectedItemIndex by remember { mutableStateOf<Int?>(null) }

    // Image picker
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val currentSize = selectedImages.size
            val newImages = uris.mapIndexedNotNull { index, uri ->
                val info = FileUtil.getFileInfo(context, uri)
                if (info != null) {
                    ImageInfo(
                        uri = uri,
                        name = info.name,
                        originalIndex = currentSize + index
                    )
                } else null
            }
            selectedImages = selectedImages + newImages
        }
    }

    // Save file launcher
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            if (selectedImages.isNotEmpty()) {
                scope.launch {
                    isProcessing = true
                    progress = 0f

                    val outputStream = context.contentResolver.openOutputStream(outputUri, "wt")
                    if (outputStream != null) {
                        val bufferedOut = java.io.BufferedOutputStream(outputStream)
                        val result = imageConverter.imagesToPdf(
                            context = context,
                            imageUris = selectedImages.map { it.uri },
                            outputStream = bufferedOut,
                            pageSize = pageSize,
                            quality = quality.toInt(),
                            onProgress = { progress = it }
                        )

                        withContext(Dispatchers.IO) { bufferedOut.flush(); bufferedOut.close() }

                        result.fold(
                            onSuccess = { count ->
                                resultSuccess = true
                                resultMessage = "Successfully converted $count images to PDF"
                                selectedImages = emptyList()
                            },
                            onFailure = { error ->
                                resultSuccess = false
                                resultMessage = error.message ?: "Conversion failed"
                            }
                        )
                    } else {
                        resultSuccess = false
                        resultMessage = "Cannot create output file"
                    }

                    isProcessing = false
                    showResult = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = "Images to PDF",
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
                if (selectedImages.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Image,
                        title = "No Images Selected",
                        subtitle = "Select one or more images to convert to PDF",
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        // Header
                        item(span = { GridItemSpan(3) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Selected Images (${selectedImages.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                TextButton(
                                    onClick = {
                                        selectedImages = emptyList()
                                        selectedItemIndex = null
                                    }
                                ) {
                                    Text("Clear All")
                                }
                            }
                        }

                        // Image grid
                        itemsIndexed(
                            items = selectedImages,
                            key = { index, image -> "${image.uri}-${image.originalIndex}" }
                        ) { index, image ->
                            ImagePreviewCard(
                                image = image,
                                position = index + 1,
                                isSelected = selectedItemIndex == index,
                                onSelect = {
                                    selectedItemIndex = if (selectedItemIndex == index) null else index
                                },
                                onRemove = {
                                    selectedImages = selectedImages.toMutableList().apply {
                                        removeAt(index)
                                    }
                                    if (selectedItemIndex == index) selectedItemIndex = null
                                },
                                onMoveUp = {
                                    if (index > 0) {
                                        selectedImages = selectedImages.toMutableList().apply {
                                            val item = removeAt(index)
                                            add(index - 1, item)
                                        }
                                        selectedItemIndex = index - 1
                                    }
                                },
                                onMoveDown = {
                                    if (index < selectedImages.lastIndex) {
                                        selectedImages = selectedImages.toMutableList().apply {
                                            val item = removeAt(index)
                                            add(index + 1, item)
                                        }
                                        selectedItemIndex = index + 1
                                    }
                                },
                                canMoveUp = index > 0,
                                canMoveDown = index < selectedImages.lastIndex
                            )
                        }

                        // Add more button
                        item(span = { GridItemSpan(3) }) {
                            OutlinedButton(
                                onClick = { pickImagesLauncher.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add More Images")
                            }
                        }

                        // Settings
                        item(span = { GridItemSpan(3) }) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Page size
                        item(span = { GridItemSpan(3) }) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "Page Size",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PageSize.entries.forEach { size ->
                                            FilterChip(
                                                selected = pageSize == size,
                                                onClick = { pageSize = size },
                                                label = { Text(size.name.replace("_", " ")) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Quality slider
                        item(span = { GridItemSpan(3) }) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Image Quality",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${quality.toInt()}%",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Slider(
                                        value = quality,
                                        onValueChange = { quality = it },
                                        valueRange = 20f..100f,
                                        steps = 7
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Smaller file",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Better quality",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Progress overlay
                if (isProcessing) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OperationProgress(
                                progress = progress,
                                message = "Converting images..."
                            )
                        }
                    }
                }
            }

            // Bottom action
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (selectedImages.isEmpty()) {
                        ActionButton(
                            text = "Select Images",
                            onClick = { pickImagesLauncher.launch(arrayOf("image/*")) },
                            icon = Icons.Default.Image
                        )
                    } else {
                        ActionButton(
                            text = "Convert to PDF",
                            onClick = {
                                val fileName = FileUtil.generateOutputFileName("images")
                                savePdfLauncher.launch(fileName)
                            },
                            isLoading = isProcessing,
                            icon = Icons.Default.Transform
                        )
                    }
                }
            }
        }
    }

    // Result dialog
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Conversion Complete" else "Conversion Failed",
            message = resultMessage,
            onDismiss = { showResult = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreviewCard(
    image: ImageInfo,
    position: Int,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier
            .aspectRatio(1f)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = image.uri,
                contentDescription = image.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )

            // Position badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            ) {
                Text(
                    text = "$position",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Controls when selected
            if (isSelected) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
