package com.sievex.app.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.sievex.app.MediaItem
import com.sievex.app.MediaStore
import com.sievex.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface FeedEntry {
    val timestamp: Long

    data class NoteEntry(val note: NoteItem) : FeedEntry {
        override val timestamp: Long get() = note.createdAt
    }

    data class MediaEntry(val media: MediaItem) : FeedEntry {
        override val timestamp: Long get() = media.addedAt
    }
}

@Composable
internal fun MediaCard(
    media: MediaItem,
    onClick: () -> Unit
) {
    var thumb by remember(media.id, media.thumb) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(media.id, media.thumb) {
        val f = MediaStore.resolveFile(media.thumb.ifBlank { media.file })
        if (f != null) {
            thumb = MediaBitmapCache.loadFile(f, 320)
        }
    }

    val whenLabel = remember(media.addedAt) {
        if (media.addedAt <= 0L) ""
        else SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(media.addedAt))
    }

    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .border(1.dp, Line, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = media.caption.ifBlank { stringResource(R.string.ui_media_viewer_title) },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = media.caption.ifBlank { stringResource(R.string.ui_media_no_caption) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (media.caption.isBlank()) Ink2 else Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (whenLabel.isNotBlank()) {
                        Text(
                            text = whenLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink2
                        )
                    }
                    if (media.noteIds.isNotEmpty()) {
                        val linkText = if (media.noteIds.size == 1) {
                            stringResource(R.string.ui_media_links_one)
                        } else {
                            stringResource(R.string.ui_media_links_many, media.noteIds.size)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Signal.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = linkText,
                                style = MaterialTheme.typography.labelSmall,
                                color = Signal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoteMediaStrip(
    media: List<MediaItem>,
    onOpenMedia: (MediaItem) -> Unit
) {
    if (media.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.ui_note_images),
            style = MaterialTheme.typography.labelSmall,
            color = Ink2
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(media, key = { it.id }) { item ->
                NoteMediaThumb(item = item, onClick = { onOpenMedia(item) })
            }
        }
    }
}

@Composable
private fun NoteMediaThumb(
    item: MediaItem,
    onClick: () -> Unit
) {
    var thumb by remember(item.id, item.thumb) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id, item.thumb) {
        val f = MediaStore.resolveFile(item.thumb.ifBlank { item.file })
        if (f != null) {
            thumb = MediaBitmapCache.loadFile(f, 320)
        }
    }

    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.caption.ifBlank { stringResource(R.string.ui_media_viewer_title) },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun MediaViewerDialog(
    media: MediaItem,
    spaceNotes: List<NoteItem>,
    onDismiss: () -> Unit,
    onMediaChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullBitmap by remember(media.id, media.file) { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    var editingCaption by remember(media.id) { mutableStateOf(false) }
    var captionDraft by remember(media.id, media.caption) { mutableStateOf(media.caption) }
    var linkingNotes by remember(media.id) { mutableStateOf(false) }
    var confirmingDelete by remember(media.id) { mutableStateOf(false) }

    LaunchedEffect(media.id, media.file) {
        val f = MediaStore.resolveFile(media.file)
        if (f != null) {
            fullBitmap = MediaBitmapCache.loadFile(f, 2048)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable { onDismiss() }
                .padding(horizontal = 16.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Surface1,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Line, RoundedCornerShape(22.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Top action bar: close + share / delete
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.ui_action_back),
                            tint = Ink,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { onDismiss() }
                                .padding(7.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        PillButton(
                            label = stringResource(R.string.ui_media_share),
                            background = Surface2,
                            foreground = Ink,
                            onClick = {
                                val file = MediaStore.resolveFile(media.file) ?: return@PillButton
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, null))
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        PillButton(
                            label = stringResource(R.string.ui_media_delete_confirm),
                            background = Surface2,
                            foreground = Signal,
                            onClick = { confirmingDelete = true }
                        )
                    }

                    // Image preview area (pinch-zoom + double-tap reset, clipped inside card)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp)
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .pointerInput(media.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                        }
                                    }
                                )
                            }
                            .pointerInput(media.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = nextScale
                                    offset = if (nextScale <= 1.01f) {
                                        Offset.Zero
                                    } else {
                                        offset + pan
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = fullBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = media.caption.ifBlank { stringResource(R.string.ui_media_viewer_title) },
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y
                                    )
                            )
                        }
                    }

                    // Caption + Edit / Link notes footer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = media.caption.ifBlank { stringResource(R.string.ui_media_no_caption) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (media.caption.isBlank()) Ink2 else Ink,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    captionDraft = media.caption
                                    editingCaption = true
                                }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PillButton(
                                label = stringResource(R.string.ui_action_edit),
                                background = Surface2,
                                foreground = Ink,
                                onClick = {
                                    captionDraft = media.caption
                                    editingCaption = true
                                }
                            )
                            PillButton(
                                label = if (media.noteIds.isEmpty()) {
                                    stringResource(R.string.ui_media_link_notes)
                                } else {
                                    "${stringResource(R.string.ui_media_link_notes)} (${media.noteIds.size})"
                                },
                                background = Surface2,
                                foreground = Signal,
                                onClick = { linkingNotes = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (editingCaption) {
        AlertDialog(
            onDismissRequest = { editingCaption = false },
            title = { Text(stringResource(R.string.ui_media_caption_label)) },
            text = {
                OutlinedTextField(
                    value = captionDraft,
                    onValueChange = { captionDraft = it },
                    placeholder = { Text(stringResource(R.string.ui_media_caption_hint)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Color.White),
                    onClick = {
                        editingCaption = false
                        val next = captionDraft
                        scope.launch {
                            withContext(Dispatchers.IO) { MediaStore.updateCaption(media.id, next) }
                            onMediaChanged()
                        }
                    }
                ) {
                    Text(stringResource(R.string.ui_share_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCaption = false }) {
                    Text(stringResource(R.string.ui_action_back))
                }
            }
        )
    }

    if (linkingNotes) {
        LinkNotesDialog(
            media = media,
            spaceNotes = spaceNotes,
            onDismiss = { linkingNotes = false },
            onSave = { selected ->
                linkingNotes = false
                scope.launch {
                    withContext(Dispatchers.IO) { MediaStore.setNoteLinks(media.id, selected) }
                    onMediaChanged()
                }
            }
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.ui_media_delete_title)) },
            text = { Text(stringResource(R.string.ui_media_delete_body)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Color.White),
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) { MediaStore.delete(media.id) }
                            onMediaChanged()
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(R.string.ui_media_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.ui_action_back))
                }
            }
        )
    }
}

@Composable
internal fun LinkNotesDialog(
    media: MediaItem,
    spaceNotes: List<NoteItem>,
    onDismiss: () -> Unit,
    onSave: (List<Long>) -> Unit
) {
    var selected by remember(media.id, media.noteIds) { mutableStateOf(media.noteIds.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_media_link_notes_title)) },
        text = {
            if (spaceNotes.isEmpty()) {
                Text(
                    stringResource(R.string.ui_media_link_notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(spaceNotes, key = { it.id }) { note ->
                        val checked = note.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selected = if (checked) selected - note.id else selected + note.id
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) selected + note.id else selected - note.id
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = note.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Color.White),
                onClick = { onSave(selected.toList()) }
            ) {
                Text(stringResource(R.string.ui_share_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_action_back))
            }
        }
    )
}
