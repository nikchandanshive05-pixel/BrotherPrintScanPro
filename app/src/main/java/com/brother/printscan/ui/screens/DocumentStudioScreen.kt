package com.brother.printscan.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brother.printscan.ui.theme.BorderLight
import com.brother.printscan.viewmodel.ScannedPage

@Composable
fun DocumentStudioScreen(
    pages: List<ScannedPage>,
    isBusy: Boolean,
    onToggleBwFilter: (String) -> Unit,
    onRotatePage: (String) -> Unit,
    onRemovePage: (String) -> Unit,
    onClearAll: () -> Unit,
    onExportPdf: (String) -> Unit,
    onBack: () -> Unit
) {
    var selectedPageIndex by remember { mutableStateOf(0) }
    var documentTitle by remember { mutableStateOf("Document") }

    val safeIndex = selectedPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val currentPage = if (pages.isNotEmpty()) pages[safeIndex] else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Studio (${pages.size} pages)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pages.isNotEmpty()) {
                    IconButton(onClick = onClearAll) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear All")
                    }
                }
            }
        }

        if (currentPage != null) {
            // Document Large Preview Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, BorderLight, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = currentPage.displayBitmap.asImageBitmap(),
                    contentDescription = "Current Scanned Page",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // Quick Tool Bar for Selected Page
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterChip(
                    selected = currentPage.isBwFilterApplied,
                    onClick = { onToggleBwFilter(currentPage.id) },
                    label = { Text("High Contrast B&W") }
                )

                Button(
                    onClick = { onRotatePage(currentPage.id) },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Rotate 90°")
                }

                OutlinedButton(
                    onClick = { onRemovePage(currentPage.id) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Remove")
                }
            }

            // Thumbnail Strip
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(80.dp)
            ) {
                itemsIndexed(pages) { index, page ->
                    val isSelected = index == safeIndex
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else BorderLight
                            )
                    ) {
                        IconButton(
                            onClick = { selectedPageIndex = index },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Image(
                                bitmap = page.displayBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // PDF Export Row
            OutlinedTextField(
                value = documentTitle,
                onValueChange = { documentTitle = it },
                label = { Text("Document Filename") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = { onExportPdf(documentTitle) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isBusy && pages.isNotEmpty(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Compile & Share Multi-Page PDF", fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("No pages scanned yet. Connect OTG cable and start scanning.")
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Back to Home")
        }
    }
}
