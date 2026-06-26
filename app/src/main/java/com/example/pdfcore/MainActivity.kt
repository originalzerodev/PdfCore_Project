package com.example.pdfcore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.pdfcore.theme.PdfCoreTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Clean up ALL orphaned temp files from previous sessions
    cleanupTempFiles()

    enableEdgeToEdge()
    setContent {
      PdfCoreTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  private fun cleanupTempFiles() {
    try {
      cacheDir.listFiles()?.forEach { file ->
        if (file.name != "code_cache") {
          deleteRecursive(file)
        }
      }
      externalCacheDir?.listFiles()?.forEach { file ->
        if (file.name != "code_cache") {
          deleteRecursive(file)
        }
      }
    } catch (_: Exception) {}
  }

  private fun deleteRecursive(file: java.io.File) {
    if (file.isDirectory) {
      file.listFiles()?.forEach { deleteRecursive(it) }
    }
    file.delete()
  }
}
