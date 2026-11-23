package com.sievex.app.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.sievex.app.AppLocale
import com.sievex.app.CaptureLog
import com.sievex.app.Formats
import com.sievex.app.MediaItem
import com.sievex.app.MediaStore
import com.sievex.app.NoteFormat
import com.sievex.app.NoteStore
import com.sievex.app.R
import com.sievex.app.Reminder
import com.sievex.app.ReminderStore
import com.sievex.app.ReminderTargetType
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val MEDIA_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

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
    val thumbFile = remember(media.id, media.thumb, media.file) {
        MediaStore.resolveFile(media.thumb.ifBlank { media.file })
    }
    var thumb by remember(thumbFile) {
        mutableStateOf(thumbFile?.let { MediaBitmapCache.peekFile(it, 320) })
    }
    var loadAttempted by remember(thumbFile) { mutableStateOf(thumb != null) }
    LaunchedEffect(thumbFile) {
        if (thumb == null && thumbFile != null) {
            thumb = MediaBitmapCache.loadFile(thumbFile, 320)
        }
        loadAttempted = true
    }

    val whenLabel = remember(media.addedAt) {
        if (media.addedAt <= 0L) ""
        else Instant.ofEpochMilli(media.addedAt)
            .atZone(ZoneId.systemDefault())
            .format(MEDIA_DATE_FORMATTER.withLocale(Locale.getDefault()))
    }

    Surface(
        onClick = onClick,
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .border(1.dp, Line, RoundedCornerShape(14.dp)),
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
                } else if (loadAttempted) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = stringResource(R.string.ui_media_missing),
                        tint = Ink2,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.caption.ifBlank { stringResource(R.string.ui_media_no_caption) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (media.caption.isBlank()) Ink2 else Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (whenLabel.isNotBlank()) {
                        Text(whenLabel, style = MaterialTheme.typography.labelSmall, color = Ink2)
                    }
                    if (media.noteIds.isNotEmpty()) {
                        val linksText = if (media.noteIds.size == 1) {
                            stringResource(R.string.ui_media_links_one)
                        } else {
                            stringResource(R.string.ui_media_links_many, media.noteIds.size)
                        }
                        Text("· $linksText", style = MaterialTheme.typography.labelSmall, color = SignalText)
                    }
                    if (!media.ocrScanned) {
                        Text("· ${stringResource(R.string.ui_media_ocr_scanning)}", style = MaterialTheme.typography.labelSmall, color = Ink2)
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.ui_note_images).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Ink2
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
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
    val thumbFile = remember(item.id, item.thumb, item.file) {
        MediaStore.resolveFile(item.thumb.ifBlank { item.file })
    }
    var thumb by remember(thumbFile) {
        mutableStateOf(thumbFile?.let { MediaBitmapCache.peekFile(it, 320) })
    }
    var loadAttempted by remember(thumbFile) { mutableStateOf(thumb != null) }
    LaunchedEffect(thumbFile) {
        if (thumb == null && thumbFile != null) {
            thumb = MediaBitmapCache.loadFile(thumbFile, 320)
        }
        loadAttempted = true
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
        } else if (loadAttempted) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.ui_media_missing),
                tint = Ink2,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun MediaViewerDialog(
    media: MediaItem,
    spaceNotes: List<NoteItem>,
    reminder: Reminder? = null,
    onOpenNote: (Long) -> Unit = {},
    onStartVoiceNote: (MediaItem) -> Unit = {},
    onDismiss: () -> Unit,
    onMediaChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fullBitmap by remember(media.id, media.file) { mutableStateOf<ImageBitmap?>(null) }
    var imageChecked by remember(media.id, media.file) { mutableStateOf(false) }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(media.id) { mutableStateOf(IntSize.Zero) }
    var editingCaption by remember(media.id) { mutableStateOf(false) }
    var captionDraft by remember(media.id, media.caption) { mutableStateOf(media.caption) }
    var linkingNotes by remember(media.id) { mutableStateOf(false) }
    var confirmingDelete by remember(media.id) { mutableStateOf(false) }
    var viewingOcr by remember(media.id) { mutableStateOf(false) }
    var pickingShapeFormat by remember(media.id) { mutableStateOf(false) }
    var shapingNote by remember(media.id) { mutableStateOf(false) }
    var pickingReminder by remember(media.id) { mutableStateOf(false) }
    var menuOpen by remember(media.id) { mutableStateOf(false) }
    var scanningOcr by remember(media.id) { mutableStateOf(!media.ocrScanned) }
    var actionBusy by remember(media.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val hasShapeableText = media.ocrText.isNotBlank() || media.caption.isNotBlank()

    fun shapeIntoNote(chosenFormat: NoteFormat) {
        if (shapingNote || actionBusy) return
        pickingShapeFormat = false
        shapingNote = true
        actionBusy = true
        scope.launch {
            val sourceText = buildString {
                if (media.caption.isNotBlank()) {
                    appendLine(media.caption.trim())
                }
                if (media.ocrText.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(media.ocrText.trim())
                }
            }.trim()

            val createdId = withContext(Dispatchers.IO) {
                val (spaceName, spaceFocus) = SpaceStore.focusFor(media.spaceId)
                val structured = if (chosenFormat.usesLlm && TransformModel.isInstalled(context.filesDir)) {
                    Transform.apply(
                        context.filesDir,
                        sourceText,
                        chosenFormat,
                        AppLocale.modelLanguage(context),
                        spaceName = spaceName,
                        spaceFocus = spaceFocus
                    )
                } else {
                    Transform.apply(
                        context.filesDir,
                        sourceText,
                        NoteFormat.PLAIN,
                        AppLocale.modelLanguage(context),
                        spaceName = spaceName,
                        spaceFocus = spaceFocus
                    )
                }
                val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                val whenText = "Today $time"
                val id = NoteStore.nextId()
                val note = if (structured != null) {
                    StoredNote(
                        id = id,
                        title = structured.title,
                        oneLine = structured.oneLine,
                        mode = structured.mode,
                        whenText = whenText,
                        verbatim = sourceText,
                        why = structured.why,
                        question = structured.question,
                        tags = structured.tags,
                        audioPath = null,
                        spaceId = media.spaceId,
                        format = if (chosenFormat.usesLlm && TransformModel.isInstalled(context.filesDir)) chosenFormat.id else NoteFormat.PLAIN.id,
                        items = structured.items,
                        createdAt = System.currentTimeMillis(),
                        questionDrafts = structured.questionDrafts
                    )
                } else {
                    val fallbackTitle = sourceText.lineSequence().firstOrNull { it.isNotBlank() }?.take(64)
                        ?: context.getString(R.string.common_untitled_note)
                    StoredNote(
                        id = id,
                        title = fallbackTitle,
                        oneLine = "",
                        mode = "Reference",
                        whenText = whenText,
                        verbatim = sourceText,
                        why = "",
                        question = "",
                        tags = emptyList(),
                        audioPath = null,
                        spaceId = media.spaceId,
                        format = NoteFormat.PLAIN.id,
                        createdAt = System.currentTimeMillis()
                    )
                }
                NoteStore.add(note)
                MediaStore.linkNoteToMedia(listOf(media.id), note.id)
                note.id
            }
            shapingNote = false
            actionBusy = false
            onMediaChanged()
            Toast.makeText(context, R.string.ui_media_shaped_toast, Toast.LENGTH_SHORT).show()
            onDismiss()
            onOpenNote(createdId)
        }
    }

    LaunchedEffect(media.id, media.file) {
        val f = MediaStore.resolveFile(media.file)
        if (f != null) {
            fullBitmap = MediaBitmapCache.loadViewerFile(f, 2048)
        }
        imageChecked = true
    }

    LaunchedEffect(media.id, media.ocrScanned) {
        if (!media.ocrScanned) {
            scanningOcr = true
            val updated = withContext(Dispatchers.IO) { MediaStore.ensureOcr(media.id) }
            scanningOcr = false
            if (updated != null && updated.ocrScanned) onMediaChanged()
        } else {
            scanningOcr = false
        }
    }

    Dialog(
        onDismissRequest = { if (!actionBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(enabled = !actionBusy) { onDismiss() }
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Surface1,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Line, RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Top action bar: accessible 48dp Close + optional OCR status + MoreVert overflow
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CloseIconButton(
                            onClick = onDismiss,
                            contentDescription = stringResource(R.string.ui_action_close),
                            enabled = !actionBusy
                        )
                        Spacer(Modifier.weight(1f))
                        if (scanningOcr && media.ocrText.isBlank()) {
                            Text(
                                stringResource(R.string.ui_media_ocr_scanning),
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink2,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Box {
                            Surface(
                                onClick = { if (!actionBusy) menuOpen = true },
                                enabled = !actionBusy,
                                color = Color.Transparent,
                                shape = CircleShape,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.ui_action_more),
                                        tint = Ink,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_media_edit_caption), color = Ink) },
                                    onClick = {
                                        menuOpen = false
                                        captionDraft = media.caption
                                        editingCaption = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (media.noteIds.isEmpty()) stringResource(R.string.ui_media_link_notes)
                                            else "${stringResource(R.string.ui_media_link_notes)} (${media.noteIds.size})",
                                            color = Ink
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        linkingNotes = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                media.ocrText.isNotBlank() -> stringResource(R.string.ui_media_ocr_label)
                                                scanningOcr -> stringResource(R.string.ui_media_ocr_scanning)
                                                else -> stringResource(R.string.ui_media_ocr_none)
                                            },
                                            color = if (media.ocrText.isNotBlank()) Ink else Ink2
                                        )
                                    },
                                    enabled = media.ocrText.isNotBlank(),
                                    onClick = {
                                        menuOpen = false
                                        viewingOcr = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (reminder != null) {
                                                stringResource(R.string.ui_reminder_chip_active, formatReminderWhen(reminder.triggerAtMs))
                                            } else {
                                                stringResource(R.string.ui_reminder_chip_set)
                                            },
                                            color = if (reminder != null) BlueText else Ink
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        pickingReminder = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_media_share), color = Ink) },
                                    onClick = {
                                        menuOpen = false
                                        val file = MediaStore.resolveFile(media.file)
                                        if (file == null || !file.isFile) {
                                            Toast.makeText(context, R.string.ui_media_share_failed, Toast.LENGTH_SHORT).show()
                                            return@DropdownMenuItem
                                        }
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/*"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                clipData = ClipData.newRawUri(null, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(share, null))
                                        }.onFailure { e ->
                                            CaptureLog.add("media: share failed - ${e.message}")
                                            Toast.makeText(context, R.string.ui_media_share_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_media_delete_confirm), color = Red) },
                                    onClick = {
                                        menuOpen = false
                                        confirmingDelete = true
                                    }
                                )
                            }
                        }
                    }

                    // Image preview area (pinch-zoom + double-tap + zoom reset pill)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 420.dp)
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .onSizeChanged { viewportSize = it }
                            .pointerInput(media.id) {
                                detectTapGestures(
                                    onDoubleTap = { tap ->
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            val newScale = 2.5f
                                            val cx = viewportSize.width / 2f
                                            val cy = viewportSize.height / 2f
                                            val dx = (cx - tap.x) * (newScale - 1f)
                                            val dy = (cy - tap.y) * (newScale - 1f)
                                            val maxX = viewportSize.width * (newScale - 1f) / 2f
                                            val maxY = viewportSize.height * (newScale - 1f) / 2f
                                            scale = newScale
                                            offset = Offset(dx.coerceIn(-maxX, maxX), dy.coerceIn(-maxY, maxY))
                                        }
                                    }
                                )
                            }
                            .pointerInput(media.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 4f)
                                    if (newScale <= 1.01f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        val maxX = viewportSize.width * (newScale - 1f) / 2f
                                        val maxY = viewportSize.height * (newScale - 1f) / 2f
                                        scale = newScale
                                        offset = Offset(
                                            x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                            y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                        )
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
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            )
                        } else if (imageChecked) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = stringResource(R.string.ui_media_missing),
                                    tint = Ink2,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    stringResource(R.string.ui_media_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink2
                                )
                            }
                        }

                        if (scale > 1.05f) {
                            Surface(
                                onClick = {
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                                color = Color.Black.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    stringResource(R.string.ui_media_zoom_reset, String.format(Locale.US, "%.1f×", scale)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Caption + compact metadata + primary actions (at most 2 clean buttons)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = media.caption.ifBlank { stringResource(R.string.ui_media_no_caption) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (media.caption.isBlank()) Ink2 else Ink,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !actionBusy) {
                                    captionDraft = media.caption
                                    editingCaption = true
                                }
                        )
                        if (media.noteIds.isNotEmpty() || reminder != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (media.noteIds.isNotEmpty()) {
                                    val linksText = if (media.noteIds.size == 1) {
                                        stringResource(R.string.ui_media_links_one)
                                    } else {
                                        stringResource(R.string.ui_media_links_many, media.noteIds.size)
                                    }
                                    Text(
                                        text = linksText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SignalText,
                                        modifier = Modifier.clickable(enabled = !actionBusy) { linkingNotes = true }
                                    )
                                }
                                if (reminder != null) {
                                    Text(
                                        text = if (media.noteIds.isNotEmpty()) {
                                            "· ${stringResource(R.string.ui_reminder_chip_active, formatReminderWhen(reminder.triggerAtMs))}"
                                        } else {
                                            stringResource(R.string.ui_reminder_chip_active, formatReminderWhen(reminder.triggerAtMs))
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BlueText,
                                        modifier = Modifier.clickable(enabled = !actionBusy) { pickingReminder = true }
                                    )
                                }
                            }
                        }
                        if (shapingNote) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Blue,
                                trackColor = Line
                            )
                            Text(
                                stringResource(R.string.ui_media_shaping_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = BlueText
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasShapeableText) {
                                PillButton(
                                    label = stringResource(R.string.ui_media_shape_note),
                                    background = BlueSoft,
                                    foreground = BlueText,
                                    border = BorderStroke(1.dp, Blue.copy(alpha = 0.44f)),
                                    enabled = !actionBusy,
                                    onClick = { pickingShapeFormat = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            PillButton(
                                label = stringResource(R.string.ui_media_voice_note),
                                background = SignalSoft,
                                foreground = SignalText,
                                enabled = !actionBusy,
                                onClick = {
                                    onDismiss()
                                    onStartVoiceNote(media)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (editingCaption) {
        var savingCaption by remember { mutableStateOf(false) }
        AppDialogSurface(
            onDismissRequest = { if (!savingCaption) editingCaption = false },
            modifier = Modifier.imePadding()
        ) {
            Text(stringResource(R.string.ui_media_caption_label), style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(14.dp))
            EditorField(
                label = "",
                value = captionDraft,
                placeholder = stringResource(R.string.ui_media_caption_hint),
                onChange = { captionDraft = it },
                minHeight = 64.dp,
                enabled = !savingCaption
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(
                    stringResource(R.string.ui_action_cancel),
                    Surface2,
                    Ink,
                    { editingCaption = false },
                    enabled = !savingCaption
                )
                Spacer(Modifier.width(10.dp))
                PillButton(
                    stringResource(R.string.ui_share_save),
                    SignalFill,
                    Color.White,
                    enabled = !savingCaption,
                    onClick = {
                        savingCaption = true
                        val next = captionDraft
                        scope.launch {
                            val ok = runCatching {
                                withContext(Dispatchers.IO) { MediaStore.updateCaption(media.id, next) }
                            }.isSuccess
                            savingCaption = false
                            if (ok) {
                                editingCaption = false
                                onMediaChanged()
                            } else {
                                Toast.makeText(context, R.string.ui_media_save_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    if (linkingNotes) {
        LinkNotesDialog(
            media = media,
            spaceNotes = spaceNotes,
            onDismiss = { linkingNotes = false },
            onSave = { selected ->
                val ok = runCatching {
                    withContext(Dispatchers.IO) { MediaStore.setNoteLinks(media.id, selected) }
                }.isSuccess
                if (ok) {
                    linkingNotes = false
                    onMediaChanged()
                } else {
                    Toast.makeText(context, R.string.ui_media_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.ui_media_delete_title),
            body = stringResource(R.string.ui_media_delete_body),
            confirmLabel = stringResource(R.string.ui_media_delete_confirm),
            destructive = true,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                actionBusy = true
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            MediaStore.delete(media.id)
                            ReminderStore.cancelForTarget(context, ReminderTargetType.MEDIA, media.id)
                        }
                    }.isSuccess
                    actionBusy = false
                    if (ok) {
                        onMediaChanged()
                        onDismiss()
                    } else {
                        Toast.makeText(context, R.string.ui_media_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (viewingOcr) {
        AppDialogSurface(onDismissRequest = { viewingOcr = false }) {
            Text(stringResource(R.string.ui_media_ocr_label), style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = media.ocrText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(stringResource(R.string.ui_action_close), Surface2, Ink, { viewingOcr = false })
                Spacer(Modifier.width(10.dp))
                PillButton(
                    stringResource(R.string.ui_media_ocr_copy),
                    SignalFill,
                    Color.White,
                    onClick = {
                        clipboard.setText(AnnotatedString(media.ocrText))
                        Toast.makeText(context, R.string.ui_copied_toast, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    if (pickingShapeFormat) {
        var chosen by remember(media.id) { mutableStateOf(Formats.next) }
        val llmReady = TransformModel.isInstalled(context.filesDir)
        AppDialogSurface(onDismissRequest = { pickingShapeFormat = false }) {
            Text(stringResource(R.string.ui_media_shape_dialog_title), style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (llmReady) R.string.ui_media_shape_dialog_body else R.string.ui_media_shape_dialog_plain_body
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
            if (llmReady) {
                Spacer(Modifier.height(14.dp))
                FormatChips(selected = chosen, onSelect = { chosen = it })
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, { pickingShapeFormat = false })
                Spacer(Modifier.width(10.dp))
                PillButton(
                    label = stringResource(R.string.ui_media_shape_confirm),
                    background = SignalFill,
                    foreground = Color.White,
                    onClick = { shapeIntoNote(if (llmReady) chosen else NoteFormat.PLAIN) }
                )
            }
        }
    }

    if (pickingReminder) {
        val label = media.caption.ifBlank {
            media.ocrText.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                ?: stringResource(R.string.ui_media_viewer_title)
        }
        ReminderPickerDialog(
            title = label,
            existing = reminder,
            onSchedule = { triggerAtMs ->
                pickingReminder = false
                scope.launch(Dispatchers.IO) {
                    ReminderStore.schedule(
                        context = context,
                        type = ReminderTargetType.MEDIA,
                        targetId = media.id,
                        title = label,
                        triggerAtMs = triggerAtMs
                    )
                }
                Toast.makeText(context, R.string.ui_reminder_saved_toast, Toast.LENGTH_SHORT).show()
            },
            onClear = if (reminder != null) {
                {
                    pickingReminder = false
                    scope.launch(Dispatchers.IO) {
                        ReminderStore.cancelForTarget(context, ReminderTargetType.MEDIA, media.id)
                    }
                    Toast.makeText(context, R.string.ui_reminder_cleared_toast, Toast.LENGTH_SHORT).show()
                }
            } else null,
            onDismiss = { pickingReminder = false }
        )
    }
}

@Composable
internal fun LinkNotesDialog(
    media: MediaItem,
    spaceNotes: List<NoteItem>,
    onDismiss: () -> Unit,
    onSave: suspend (List<Long>) -> Unit
) {
    var selected by remember(media.id, media.noteIds) { mutableStateOf(media.noteIds.toSet()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AppDialogSurface(onDismissRequest = { if (!saving) onDismiss() }) {
        Text(stringResource(R.string.ui_media_link_notes_title), style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(12.dp))
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
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !saving) {
                                selected = if (checked) selected - note.id else selected + note.id
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !saving,
                            colors = CheckboxDefaults.colors(
                                checkedColor = SignalFill,
                                uncheckedColor = Ink2,
                                checkmarkColor = Color.White
                            ),
                            onCheckedChange = { isChecked ->
                                selected = if (isChecked) selected + note.id else selected - note.id
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = note.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (note.whenText.isNotBlank()) {
                                Text(
                                    text = whenLabel(note.whenText),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink2,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(
                stringResource(R.string.ui_action_cancel),
                Surface2,
                Ink,
                onDismiss,
                enabled = !saving
            )
            if (spaceNotes.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                PillButton(
                    stringResource(R.string.ui_share_save),
                    SignalFill,
                    Color.White,
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            onSave(selected.toList())
                            saving = false
                        }
                    }
                )
            }
        }
    }
}
