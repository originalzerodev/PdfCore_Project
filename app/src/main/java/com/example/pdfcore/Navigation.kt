package com.example.pdfcore

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.pdfcore.ui.screens.CompressScreen
import com.example.pdfcore.ui.screens.ConvertScreen
import com.example.pdfcore.ui.screens.HomeScreen
import com.example.pdfcore.ui.screens.ViewerScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    onNavigateToCompress = { backStack.add(CompressPdf) },
                    onNavigateToImageToPdf = { backStack.add(ImageToPdf) },
                    onNavigateToViewer = { backStack.add(ViewPdf) },
                    modifier = Modifier.safeDrawingPadding()
                )
            }
            entry<CompressPdf> {
                CompressScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding()
                )
            }
            entry<ImageToPdf> {
                ConvertScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding()
                )
            }
            entry<ViewPdf> {
                ViewerScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding()
                )
            }
        },
    )
}
