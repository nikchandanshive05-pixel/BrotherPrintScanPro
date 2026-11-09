package com.brother.printscan.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brother.printscan.protocol.ScanColorMode
import com.brother.printscan.protocol.ScanResolution

@Composable
fun ScannerScreen(
    isBusy: Boolean,
    progress: Int,
    statusMessage: String?,
    onStartScan: (ScanResolution, ScanColorMode) -> Unit,
    onBack: () -> Unit
) {
    var selectedDpi by remember { mutableStateOf(ScanResolution.DPI_300) }
    var selectedMode by remember { mutableStateOf(ScanColorMode.TRUE_COLOR_JPEG) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Flatbed Scan Configuration",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Resolution Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Resolution (DPI)", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ScanResolution.DPI_100 to "100 (Draft)",
                        ScanResolution.DPI_300 to "300 (Standard)",
                        ScanResolution.DPI_600 to "600 (High)"
                    ).forEach { (res, label) ->
                        FilterChip(
                            selected = selectedDpi == res,
                            onClick = { if (!isBusy) selectedDpi = res },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // Color Mode Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Scan Mode", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ScanColorMode.TRUE_COLOR_JPEG to "24-bit Color",
                        ScanColorMode.GRAYSCALE to "Grayscale",
                        ScanColorMode.MONOCHROME_PACKBITS to "Mono B&W"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { if (!isBusy) selectedMode = mode },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // Scan Action & Progress
        if (isBusy) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("$progress% - ${statusMessage ?: "Scanning..."}")
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onStartScan(selectedDpi, selectedMode) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isBusy,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Start Flatbed Scan", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isBusy,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Back")
        }
    }
}
