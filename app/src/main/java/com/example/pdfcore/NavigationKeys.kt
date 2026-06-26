package com.example.pdfcore

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey
@Serializable data object CompressPdf : NavKey
@Serializable data object ImageToPdf : NavKey
@Serializable data object ViewPdf : NavKey
