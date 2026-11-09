package com.brother.printscan

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.brother.printscan.ui.screens.DocumentStudioScreen
import com.brother.printscan.ui.screens.HomeScreen
import com.brother.printscan.ui.screens.PrinterScreen
import com.brother.printscan.ui.screens.ScannerScreen
import com.brother.printscan.ui.theme.BrotherPrintScanTheme
import com.brother.printscan.viewmodel.AppViewModel

enum class NavigationTarget {
    HOME,
    SCANNER,
    STUDIO,
    PRINTER
}

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BrotherPrintScanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(NavigationTarget.HOME) }

                    val connectionState by viewModel.connectionState.collectAsState()
                    val pages by viewModel.scannedPages.collectAsState()
                    val isBusy by viewModel.isBusy.collectAsState()
                    val progress by viewModel.operationProgress.collectAsState()
                    val statusMessage by viewModel.statusMessage.collectAsState()

                    LaunchedEffect(statusMessage) {
                        statusMessage?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        }
                    }

                    when (currentScreen) {
                        NavigationTarget.HOME -> {
                            HomeScreen(
                                connectionState = connectionState,
                                scannedCount = pages.size,
                                onNavigateToScan = { currentScreen = NavigationTarget.SCANNER },
                                onNavigateToStudio = { currentScreen = NavigationTarget.STUDIO },
                                onNavigateToPrint = { currentScreen = NavigationTarget.PRINTER },
                                onRefreshUsb = { viewModel.usbManager.checkConnectedDevices() }
                            )
                        }
                        NavigationTarget.SCANNER -> {
                            ScannerScreen(
                                isBusy = isBusy,
                                progress = progress,
                                statusMessage = statusMessage,
                                onStartScan = { res, mode -> viewModel.startScan(res, mode) },
                                onBack = { currentScreen = NavigationTarget.HOME }
                            )
                        }
                        NavigationTarget.STUDIO -> {
                            DocumentStudioScreen(
                                pages = pages,
                                isBusy = isBusy,
                                onToggleBwFilter = { viewModel.toggleBwFilter(it) },
                                onRotatePage = { viewModel.rotatePage(it) },
                                onRemovePage = { viewModel.removePage(it) },
                                onClearAll = { viewModel.clearAllPages() },
                                onExportPdf = { title -> viewModel.exportAndSharePdf(this@MainActivity, title) },
                                onBack = { currentScreen = NavigationTarget.HOME }
                            )
                        }
                        NavigationTarget.PRINTER -> {
                            PrinterScreen(
                                isBusy = isBusy,
                                progress = progress,
                                statusMessage = statusMessage,
                                onPrintBitmap = { bmp, copies -> viewModel.printCurrentPage(bmp, copies) },
                                onBack = { currentScreen = NavigationTarget.HOME }
                            )
                        }
                    }
                }
            }
        }
    }
}
