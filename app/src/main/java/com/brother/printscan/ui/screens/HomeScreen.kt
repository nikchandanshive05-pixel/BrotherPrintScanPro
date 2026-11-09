package com.brother.printscan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brother.printscan.ui.theme.BorderLight
import com.brother.printscan.ui.theme.OliveAccent
import com.brother.printscan.ui.theme.TerracottaCallout
import com.brother.printscan.usb.UsbConnectionState

@Composable
fun HomeScreen(
    connectionState: UsbConnectionState,
    scannedCount: Int,
    onNavigateToScan: () -> Unit,
    onNavigateToStudio: () -> Unit,
    onNavigateToPrint: () -> Unit,
    onRefreshUsb: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hardware Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = "USB Connection",
                            tint = if (connectionState.isConnected) OliveAccent else TerracottaCallout
                        )
                        Text(
                            text = "Hardware Connection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onRefreshUsb) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh USB")
                    }
                }

                HorizontalDivider(color = BorderLight)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectionState.deviceName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (connectionState.isConnected) "Direct USB OTG Attached" else "Plug Type-C to USB-B OTG cable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }

                    Icon(
                        imageVector = if (connectionState.isConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status",
                        tint = if (connectionState.isConnected) OliveAccent else TerracottaCallout
                    )
                }

                if (connectionState.endpointDetails.isNotEmpty()) {
                    Text(
                        text = "Endpoints: ${connectionState.endpointDetails}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }

                if (connectionState.errorMessage != null) {
                    Text(
                        text = connectionState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Action Hub Cards
        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Scanner Launch Button
            Button(
                onClick = onNavigateToScan,
                modifier = Modifier
                    .weight(1f)
                    .height(110.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = connectionState.isConnected && connectionState.canScan
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Scan Flatbed", fontWeight = FontWeight.Bold)
                }
            }

            // Print Launch Button
            Button(
                onClick = onNavigateToPrint,
                modifier = Modifier
                    .weight(1f)
                    .height(110.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                enabled = connectionState.isConnected && connectionState.canPrint
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Print File", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Document Studio Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Pro Document Studio",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "You have $scannedCount scanned page(s) ready in memory for filtering, auto B&W contrast, merging, and PDF export.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onNavigateToStudio,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = scannedCount > 0
                ) {
                    Text("Open Studio & Export PDF ($scannedCount)")
                }
            }
        }
    }
}
