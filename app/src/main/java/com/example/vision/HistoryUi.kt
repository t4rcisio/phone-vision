package com.example.vision

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HistorySheet(vm: VisionModel, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<HistoryEntry?>(null) }
    val context = LocalContext.current
    GlassSheet(if (chosen == null) "History" else "Results", onDismiss) {
        val entry = chosen
        if (entry == null) {
            if (vm.history.isEmpty())
                Text("No results yet", Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.history, key = { it.id }) { e ->
                    val thumb by
                        produceState<Bitmap?>(null, e.id) {
                            value =
                                withContext(Dispatchers.IO) {
                                    BitmapFactory.decodeFile(
                                        e.annotated,
                                        BitmapFactory.Options().apply { inSampleSize = 4 },
                                    )
                                }
                        }
                    GlassSurface(
                        Modifier.fillMaxWidth().clickable { chosen = e },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            thumb?.let {
                                Image(
                                    it.asImageBitmap(),
                                    null,
                                    Modifier.size(68.dp)
                                        .clip(
                                            androidx.compose.foundation.shape.RoundedCornerShape(
                                                14.dp,
                                            ),
                                        ),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    e.prompt.ifBlank { e.skill },
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    java.text.DateFormat.getDateTimeInstance(
                                            java.text.DateFormat.SHORT,
                                            java.text.DateFormat.SHORT,
                                        )
                                        .format(java.util.Date(e.timestamp)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Ice.copy(alpha = .75f),
                                )
                            }
                            Text("›", color = Ice, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        } else {
            val image by
                produceState<Bitmap?>(null, entry.id) {
                    value =
                        withContext(Dispatchers.IO) { BitmapFactory.decodeFile(entry.annotated) }
                }
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = { chosen = null }) { Text("‹ History") }
                image?.let { ResultPhoto(it) }
                SelectionContainer {
                    Text(entry.answer, style = MaterialTheme.typography.bodyLarge)
                }
                entry.galleryUri?.let { uri ->
                    CameraAction(
                        "Open in gallery",
                        {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(Uri.parse(uri), "image/jpeg")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
