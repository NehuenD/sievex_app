package com.sievex.app.ui

import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sievex.app.AppLocale
import com.sievex.app.CaptureLog
import com.sievex.app.CaptureService
import com.sievex.app.Formats
import com.sievex.app.LensArtifact
import com.sievex.app.LensStore
import com.sievex.app.MediaItem
import com.sievex.app.MediaStore
import com.sievex.app.NoteFormat
import com.sievex.app.NoteStore
import com.sievex.app.R
import com.sievex.app.Reminder
import com.sievex.app.ReminderStore
import com.sievex.app.ReminderTargetType
import com.sievex.app.Space
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NoteCard(
    note: NoteItem,
    spaceName: String? = null,
    mediaCount: Int = 0,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var menuOpen by remember(note.id) { mutableStateOf(false) }
    val format = NoteFormat.from(note.format)
    val snippet = remember(note.oneLine, note.items, note.verbatim) {
        when {
            note.oneLine.isNotBlank() -> note.oneLine
            note.items.isNotEmpty() -> note.items.take(3).joinToString(" · ")
            note.verbatim.isNotBlank() -> note.verbatim
            else -> ""
        }
    }

    Surface(
        onClick = onClick,
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 18.dp, end = if (onDelete != null) 8.dp else 18.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeChip(note.mode)
                if (format != NoteFormat.NOTE) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .border(1.dp, Line, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(formatLabel(format).uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink2)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (note.editedAt != null) {
                    EditedBadge()
                    Spacer(Modifier.width(8.dp))
                }
                Text(whenLabel(note.whenText), style = MaterialTheme.typography.labelSmall, color = Ink2)
                if (onDelete != null) {
                    Box {
                        Surface(
                            onClick = { menuOpen = true },
                            color = Color.Transparent,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.ui_action_more),
                                    tint = Ink2,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_note_delete_confirm), color = Red) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = if (onDelete != null) 10.dp else 0.dp)
            )
            if (snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = if (onDelete != null) 10.dp else 0.dp)
                )
            }
            if (spaceName != null || note.audioPath != null || mediaCount > 0 || note.followUps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (spaceName != null) {
                        Text(
                            spaceName,
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (note.audioPath != null) {
                        Text(
                            stringResource(R.string.ui_note_audio_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = Blue
                        )
                    }
                    if (note.followUps.isNotEmpty()) {
                        Text(
                            if (note.followUps.size == 1) stringResource(R.string.ui_followup_count_one)
                            else stringResource(R.string.ui_followup_count_many, note.followUps.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = BlueText
                        )
                    }
                    if (mediaCount > 0) {
                        Text(
                            if (mediaCount == 1) stringResource(R.string.ui_media_count_one) else stringResource(R.string.ui_media_count_many, mediaCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink2
                        )
                    }
                }
            }
        }
    }
}

private enum class SearchScope { ALL, NOTES, MEDIA, REPORTS }

private fun extractMatchSnippet(text: String, query: String, maxChars: Int = 110): String {
    val clean = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return ""
    val idx = clean.indexOf(query, ignoreCase = true)
    if (idx < 0) return clean.take(maxChars)
    val start = (idx - 28).coerceAtLeast(0)
    val end = (start + maxChars).coerceAtMost(clean.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < clean.length) "…" else ""
    return prefix + clean.substring(start, end) + suffix
}

@Composable
internal fun NotesScreen(
    notes: List<NoteItem>,
    spaces: List<Space>,
    allMedia: List<MediaItem> = emptyList(),
    allArtifacts: List<LensArtifact> = emptyList(),
    mediaNoteCounts: Map<Long, Int>,
    activeTagFilter: String?,
    onClearTagFilter: () -> Unit,
    onNewNote: () -> Unit,
    onOpen: (NoteItem) -> Unit,
    onOpenMedia: (MediaItem) -> Unit = {},
    onOpenSpaceLens: (String, String) -> Unit = { _, _ -> },
    onDeleteNote: (NoteItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf(SearchScope.ALL) }
    var selectedMode by rememberSaveable { mutableStateOf<String?>(null) }
    var noteToDelete by remember { mutableStateOf<NoteItem?>(null) }

    val spaceNames = remember(spaces) { spaces.associate { it.id to it.name } }
    val availableModes = remember(notes) {
        notes.map { it.mode }.distinct()
    }
    val trimmedQuery = query.trim()
    val searching = trimmedQuery.isNotEmpty()

    val filteredNotes = remember(notes, trimmedQuery, selectedMode, activeTagFilter, spaceNames) {
        notes.filter { n ->
            val matchesMode = selectedMode == null || n.mode == selectedMode
            val matchesTag = activeTagFilter == null || n.tags.any { it.equals(activeTagFilter, ignoreCase = true) }
            val matchesQuery = trimmedQuery.isEmpty() ||
                n.title.contains(trimmedQuery, ignoreCase = true) ||
                n.oneLine.contains(trimmedQuery, ignoreCase = true) ||
                n.verbatim.contains(trimmedQuery, ignoreCase = true) ||
                n.why.contains(trimmedQuery, ignoreCase = true) ||
                n.question.contains(trimmedQuery, ignoreCase = true) ||
                n.openQuestions.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                n.questionDrafts.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                n.tags.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                n.items.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                n.followUps.any { it.text.contains(trimmedQuery, ignoreCase = true) } ||
                (n.spaceId?.let { spaceNames[it] }?.contains(trimmedQuery, ignoreCase = true) == true)
            matchesMode && matchesTag && matchesQuery
        }
    }

    val matchingMedia = remember(allMedia, trimmedQuery, spaceNames) {
        if (trimmedQuery.isEmpty()) emptyList()
        else allMedia.filter { m ->
            m.caption.contains(trimmedQuery, ignoreCase = true) ||
                m.ocrText.contains(trimmedQuery, ignoreCase = true) ||
                (spaceNames[m.spaceId]?.contains(trimmedQuery, ignoreCase = true) == true)
        }
    }

    val matchingArtifacts = remember(allArtifacts, trimmedQuery, spaceNames) {
        if (trimmedQuery.isEmpty()) emptyList()
        else allArtifacts.filter { a ->
            a.body.contains(trimmedQuery, ignoreCase = true) ||
                a.kind.contains(trimmedQuery, ignoreCase = true) ||
                (spaceNames[a.spaceId]?.contains(trimmedQuery, ignoreCase = true) == true)
        }
    }

    val isFiltered = searching || selectedMode != null || activeTagFilter != null

    Box(
        Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(contentType = "header") {
                Column {
                    ScreenHeader(
                        stringResource(R.string.ui_tab_notes),
                        if (notes.size == 1) stringResource(R.string.ui_notes_count_one) else stringResource(R.string.ui_notes_count_many, notes.size)
                    )
                    // Unified Search bar (Notes + Verbatim + Screenshot OCR + Reports)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface1)
                            .border(1.dp, if (searching) Blue.copy(alpha = 0.55f) else Line, RoundedCornerShape(16.dp))
                            .heightIn(min = 48.dp)
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = if (searching) Blue else Ink2,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.ui_notes_search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PlaceholderInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                                singleLine = true,
                                cursorBrush = SolidColor(Signal),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (query.isNotEmpty()) {
                            CloseIconButton(
                                onClick = {
                                    query = ""
                                    searchScope = SearchScope.ALL
                                },
                                contentDescription = stringResource(R.string.ui_action_clear)
                            )
                        }
                    }

                    if (searching) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SubTab(
                                label = stringResource(R.string.ui_search_scope_all, filteredNotes.size + matchingMedia.size + matchingArtifacts.size),
                                selected = searchScope == SearchScope.ALL
                            ) { searchScope = SearchScope.ALL }
                            SubTab(
                                label = stringResource(R.string.ui_search_scope_notes, filteredNotes.size),
                                selected = searchScope == SearchScope.NOTES
                            ) { searchScope = SearchScope.NOTES }
                            SubTab(
                                label = stringResource(R.string.ui_search_scope_media, matchingMedia.size),
                                selected = searchScope == SearchScope.MEDIA
                            ) { searchScope = SearchScope.MEDIA }
                            SubTab(
                                label = stringResource(R.string.ui_search_scope_reports, matchingArtifacts.size),
                                selected = searchScope == SearchScope.REPORTS
                            ) { searchScope = SearchScope.REPORTS }
                        }
                    } else if (availableModes.size > 1 || activeTagFilter != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SubTab(
                                label = stringResource(R.string.ui_notes_filter_all),
                                selected = selectedMode == null && activeTagFilter == null
                            ) {
                                selectedMode = null
                                if (activeTagFilter != null) onClearTagFilter()
                            }
                            if (activeTagFilter != null) {
                                Surface(
                                    onClick = onClearTagFilter,
                                    color = SignalSoft,
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Row(
                                        Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("#$activeTagFilter", style = MaterialTheme.typography.labelMedium, color = SignalText)
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ui_action_clear), tint = SignalText, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            availableModes.forEach { mode ->
                                SubTab(
                                    label = modeLabel(mode),
                                    selected = selectedMode == mode
                                ) {
                                    selectedMode = if (selectedMode == mode) null else mode
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            val showNotes = !searching || searchScope == SearchScope.ALL || searchScope == SearchScope.NOTES
            val showMedia = searching && (searchScope == SearchScope.ALL || searchScope == SearchScope.MEDIA)
            val showReports = searching && (searchScope == SearchScope.ALL || searchScope == SearchScope.REPORTS)

            val totalShown = (if (showNotes) filteredNotes.size else 0) +
                (if (showMedia) matchingMedia.size else 0) +
                (if (showReports) matchingArtifacts.size else 0)

            if (totalShown == 0) {
                item(contentType = "empty") {
                    Text(
                        stringResource(if (isFiltered) R.string.ui_notes_filter_empty else R.string.ui_notes_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }

            if (showNotes) {
                items(filteredNotes, key = { it.id }, contentType = { "note" }) { note ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        NoteCard(
                            note = note,
                            spaceName = note.spaceId?.let { spaceNames[it] },
                            mediaCount = mediaNoteCounts[note.id] ?: 0,
                            onClick = { onOpen(note) },
                            onDelete = { noteToDelete = note }
                        )
                    }
                }
            }

            if (showMedia && matchingMedia.isNotEmpty()) {
                item(contentType = "search_media_header") {
                    Text(
                        stringResource(R.string.ui_search_section_media).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Blue,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
                    )
                }
                items(matchingMedia, key = { "media_${it.id}" }, contentType = { "search_media" }) { item ->
                    val snippet = remember(item.ocrText, item.caption, trimmedQuery) {
                        when {
                            item.ocrText.contains(trimmedQuery, ignoreCase = true) ->
                                extractMatchSnippet(item.ocrText, trimmedQuery)
                            item.caption.isNotBlank() -> item.caption
                            else -> ""
                        }
                    }
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        SearchMediaHitCard(
                            media = item,
                            spaceName = spaceNames[item.spaceId],
                            matchedSnippet = snippet,
                            onClick = { onOpenMedia(item) }
                        )
                    }
                }
            }

            if (showReports && matchingArtifacts.isNotEmpty()) {
                item(contentType = "search_reports_header") {
                    Text(
                        stringResource(R.string.ui_search_section_reports).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Mint,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
                    )
                }
                items(matchingArtifacts, key = { "art_${it.id}" }, contentType = { "search_report" }) { art ->
                    val spec = LensStore.byId(art.kind)
                    val title = if (spec != null) lensName(spec) else art.kind
                    val snippet = remember(art.body, trimmedQuery) { extractMatchSnippet(art.body, trimmedQuery) }
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        Surface(
                            onClick = { onOpenSpaceLens(art.spaceId, art.kind) },
                            color = Surface1,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Mint.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Mint)
                                    spaceNames[art.spaceId]?.let { sp ->
                                        Text(" · $sp", style = MaterialTheme.typography.labelSmall, color = Ink2)
                                    }
                                }
                                if (snippet.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewNote,
            containerColor = SignalFill,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.ui_notes_new_note)
            )
        }
    }

    val pendingDelete = noteToDelete
    if (pendingDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.ui_note_delete_title),
            body = stringResource(R.string.ui_note_delete_body),
            confirmLabel = stringResource(R.string.ui_note_delete_confirm),
            destructive = true,
            onDismiss = { noteToDelete = null },
            onConfirm = {
                noteToDelete = null
                onDeleteNote(pendingDelete)
            }
        )
    }
}

@Composable
private fun SearchMediaHitCard(
    media: MediaItem,
    spaceName: String?,
    matchedSnippet: String,
    onClick: () -> Unit
) {
    val thumbFile = remember(media.id, media.thumb, media.file) {
        MediaStore.resolveFile(media.thumb.ifBlank { media.file })
    }
    var thumb by remember(thumbFile) {
        mutableStateOf(thumbFile?.let { MediaBitmapCache.peekFile(it, 240) })
    }
    LaunchedEffect(thumbFile) {
        if (thumb == null && thumbFile != null) {
            thumb = MediaBitmapCache.loadFile(thumbFile, 240)
        }
    }

    Surface(
        onClick = onClick,
        color = Surface1,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Blue.copy(alpha = 0.32f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
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
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ui_search_screenshot_badge).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = BlueText
                    )
                    if (spaceName != null) {
                        Text(
                            " · $spaceName",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (media.caption.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        media.caption,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (matchedSnippet.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        matchedSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun NoteDetailScreen(
    note: NoteItem,
    spaces: List<Space>,
    allMedia: List<MediaItem>,
    reminder: Reminder? = null,
    recordingFollowUp: Boolean = false,
    recordingBusyElsewhere: Boolean = false,
    onStartFollowUp: () -> Unit = {},
    onStopFollowUp: () -> Unit = {},
    onCancelFollowUp: () -> Unit = {},
    onResolveQuestion: (String) -> Unit = {},
    onOpenMedia: (MediaItem) -> Unit,
    onTagClick: (String) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (Long) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    var pickingReminder by remember(note.id) { mutableStateOf(false) }
    var reprocessing by remember(note.id) { mutableStateOf(false) }
    var reanalyzing by remember(note.id) { mutableStateOf(false) }
    var reanalyzeFailed by remember(note.id) { mutableStateOf(false) }
    var menuOpen by remember(note.id) { mutableStateOf(false) }
    var confirmingDelete by remember(note.id) { mutableStateOf(false) }
    var followElapsed by remember(note.id) { mutableIntStateOf(0) }

    LaunchedEffect(recordingFollowUp) {
        if (!recordingFollowUp) {
            followElapsed = 0
            return@LaunchedEffect
        }
        val base = CaptureService.recordingStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        while (true) {
            val spentMs = (System.currentTimeMillis() - base).coerceAtLeast(0L)
            val sec = (spentMs / 1000L).toInt()
            followElapsed = sec
            val nextSecMs = (sec + 1) * 1000L
            delay((nextSecMs - spentMs).coerceAtLeast(16L))
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val copyLink = stringResource(R.string.common_link_copy)
    val spaceName = spaces.firstOrNull { it.id == note.spaceId }?.name
    val noteFormat = NoteFormat.from(note.format)
    val noteMedia = remember(note.id, allMedia) { allMedia.filter { note.id in it.noteIds } }
    val audioFile = remember(note.audioPath) {
        note.audioPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 44L }
    }
    val acceptedQuestions = remember(note.openQuestions, note.question) {
        note.openQuestions.ifEmpty {
            listOfNotNull(note.question.trim().takeIf { it.isNotEmpty() })
        }
    }

    fun reprocess(chosen: NoteFormat) {
        if (reanalyzing) return
        reprocessing = false
        reanalyzeFailed = false
        reanalyzing = true
        scope.launch(Dispatchers.IO) {
            val stored = NoteStore.byId(note.id)
            val combinedSource = stored?.let { s ->
                buildString {
                    if (s.verbatim.isNotBlank()) append(s.verbatim.trim())
                    s.followUps.forEach { fu ->
                        if (fu.text.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(fu.text.trim())
                        }
                    }
                }
            }.orEmpty()
            if (stored == null || combinedSource.isBlank()) {
                withContext(Dispatchers.Main) {
                    reanalyzing = false
                    reanalyzeFailed = true
                }
                return@launch
            }
            val (spaceNameForPrompt, spaceFocus) = SpaceStore.focusFor(stored.spaceId)
            val structured = Transform.apply(
                context.filesDir,
                combinedSource,
                chosen,
                AppLocale.modelLanguage(context),
                spaceName = spaceNameForPrompt,
                spaceFocus = spaceFocus
            )
            if (structured == null) {
                withContext(Dispatchers.Main) {
                    reanalyzing = false
                    reanalyzeFailed = true
                }
                return@launch
            }
            NoteStore.replaceWithAi(
                stored.copy(
                    title = structured.title,
                    oneLine = structured.oneLine,
                    mode = structured.mode,
                    why = structured.why,
                    question = stored.question,
                    tags = structured.tags,
                    format = chosen.id,
                    items = structured.items,
                    openQuestions = stored.openQuestions,
                    questionDrafts = structured.questionDrafts
                )
            )
            withContext(Dispatchers.Main) { reanalyzing = false }
        }
    }

    fun shareNote() {
        val body = buildString {
            appendLine(note.title)
            if (note.oneLine.isNotBlank()) {
                appendLine()
                appendLine(note.oneLine)
            }
            if (note.items.isNotEmpty()) {
                appendLine()
                note.items.forEachIndexed { i, item ->
                    appendLine(if (noteFormat.ordered) "${i + 1}. $item" else "- $item")
                }
            }
            if (note.verbatim.isNotBlank()) {
                appendLine()
                appendLine(note.verbatim)
            }
            if (note.followUps.isNotEmpty()) {
                note.followUps.forEach { fu ->
                    appendLine()
                    appendLine(fu.text)
                }
            }
            if (note.why.isNotBlank()) {
                appendLine()
                appendLine(note.why)
            }
            val openQs = note.openQuestions.ifEmpty {
                listOfNotNull(note.question.trim().takeIf { it.isNotEmpty() })
            }
            if (openQs.isNotEmpty()) {
                appendLine()
                openQs.forEach { q -> appendLine(q) }
            }
            if (note.tags.isNotEmpty()) {
                appendLine()
                appendLine(note.tags.joinToString(" ") { "#$it" })
            }
        }.trim()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackIconButton(onClick = onBack)
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.ui_note_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2,
                    modifier = Modifier.weight(1f)
                )
                PillButton(
                    stringResource(R.string.ui_note_reanalyze),
                    Surface2,
                    Ink,
                    { reprocessing = true },
                    enabled = note.verbatim.isNotBlank() && !reanalyzing
                )
                Spacer(Modifier.width(8.dp))
                PillButton(
                    stringResource(R.string.ui_action_edit),
                    Surface2,
                    Ink,
                    onEdit,
                    enabled = !reanalyzing
                )
                Box {
                    Surface(
                        onClick = { menuOpen = true },
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
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (reminder != null) R.string.ui_note_change_reminder
                                        else R.string.ui_note_set_reminder
                                    ),
                                    color = if (reminder != null) BlueText else Ink
                                )
                            },
                            onClick = {
                                menuOpen = false
                                pickingReminder = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_note_share), color = Ink) },
                            onClick = {
                                menuOpen = false
                                shareNote()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_note_delete_confirm), color = Red) },
                            enabled = !reanalyzing,
                            onClick = {
                                menuOpen = false
                                confirmingDelete = true
                            }
                        )
                    }
                }
            }
        }
        if (reanalyzing || reanalyzeFailed) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (reanalyzing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Signal,
                            trackColor = Line
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.ui_note_reprocessing),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                    }
                    if (reanalyzeFailed) {
                        Text(
                            stringResource(R.string.ui_note_reprocess_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = Red
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChip(note.mode)
                    Spacer(Modifier.weight(1f))
                    if (note.editedAt != null) {
                        EditedBadge()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(whenLabel(note.whenText), style = MaterialTheme.typography.labelSmall, color = Ink2)
                }
                Spacer(Modifier.height(14.dp))
                Text(note.title, style = MaterialTheme.typography.headlineMedium, color = Ink)
                if (note.oneLine.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    LinkifiedText(
                        text = note.oneLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink2
                    )
                }
                if (reminder != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = { pickingReminder = true },
                        color = BlueSoft,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Blue.copy(alpha = 0.4f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(Blue, CircleShape)
                            )
                            Text(
                                text = stringResource(
                                    R.string.ui_reminder_scheduled_for,
                                    formatReminderWhen(reminder.triggerAtMs)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = BlueText
                            )
                        }
                    }
                }
            }
        }
        if (audioFile != null) {
            item {
                AudioPlayerBar(noteId = note.id, file = audioFile)
            }
        }
        if (note.items.isNotEmpty()) {
            item {
                DetailSection(if (noteFormat.ordered) stringResource(R.string.ui_section_steps) else stringResource(R.string.ui_section_items)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        note.items.forEachIndexed { index, item ->
                            val links = remember(item) { linkRanges(item) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (noteFormat.ordered) "${index + 1}" else "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SignalText,
                                    modifier = Modifier.width(22.dp)
                                )
                                LinkifiedText(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Ink,
                                    modifier = Modifier.weight(1f),
                                    precomputedRanges = links
                                )
                                if (links.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        onClick = {
                                            val raw = item.substring(links.first())
                                            clipboard.setText(AnnotatedString(openableUrl(raw)))
                                            Toast.makeText(context, R.string.ui_copied_toast, Toast.LENGTH_SHORT).show()
                                        },
                                        color = Surface2,
                                        shape = RoundedCornerShape(999.dp),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 14.dp)
                                        ) {
                                            Text(copyLink, style = MaterialTheme.typography.labelSmall, color = SignalText)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (noteMedia.isNotEmpty()) {
            item { NoteMediaStrip(media = noteMedia, onOpenMedia = onOpenMedia) }
        }
        item {
            Surface(
                onClick = { picking = true },
                color = Surface1,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (note.spaceId == null) Line else Signal.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 48.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.ui_note_space_label), style = MaterialTheme.typography.bodyMedium, color = Ink2)
                    Text(
                        spaceName ?: stringResource(R.string.ui_space_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (spaceName == null) Ink2 else SignalText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.ui_action_change), style = MaterialTheme.typography.labelSmall, color = Ink2)
                }
            }
        }
        item {
            DetailSection(stringResource(R.string.ui_section_what_i_said)) {
                LinkifiedText(
                    text = note.verbatim,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
            }
        }
        if (note.followUps.isNotEmpty() || recordingFollowUp || !recordingBusyElsewhere) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (note.followUps.isNotEmpty()) {
                        Text(
                            stringResource(R.string.ui_followup_section_title).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = BlueText
                        )
                        note.followUps.forEachIndexed { idx, fu ->
                            Surface(
                                color = Surface1,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, Blue.copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        whenLabel(fu.whenText),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BlueText
                                    )
                                    LinkifiedText(
                                        text = fu.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink
                                    )
                                    val fuFile = remember(fu.audioPath) {
                                        fu.audioPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 44L }
                                    }
                                    if (fuFile != null) {
                                        AudioPlayerBar(
                                            noteId = note.id * 1000L + idx + 1L,
                                            file = fuFile,
                                            horizontalPadding = 0.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recordingFollowUp) {
                        Surface(
                            color = SignalSoft,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Signal),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(Signal, CircleShape)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.ui_followup_recording),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SignalText
                                    )
                                    Text(
                                        formatDuration(followElapsed),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Ink
                                    )
                                }
                                PillButton(
                                    label = stringResource(R.string.ui_record_cancel),
                                    background = Surface2,
                                    foreground = Red,
                                    onClick = onCancelFollowUp
                                )
                                PillButton(
                                    label = stringResource(R.string.ui_followup_stop_save),
                                    background = SignalFill,
                                    foreground = Color.White,
                                    onClick = onStopFollowUp
                                )
                            }
                        }
                    } else if (!recordingBusyElsewhere) {
                        Surface(
                            onClick = onStartFollowUp,
                            color = Surface1,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Line),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(Signal, CircleShape)
                                )
                                Text(
                                    stringResource(R.string.ui_followup_add_voice),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    stringResource(R.string.ui_followup_add_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink2
                                )
                            }
                        }
                    }
                }
            }
        }
        if (note.why.isNotBlank()) {
            item {
                DetailSection(stringResource(R.string.ui_section_why_it_matters)) {
                    LinkifiedText(
                        text = note.why,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Blue
                    )
                }
            }
        }
        if (acceptedQuestions.isNotEmpty()) {
            item {
                DetailSection(stringResource(R.string.ui_section_open_question)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        acceptedQuestions.forEach { q ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinkifiedText(
                                    text = q,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Amber
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    PillButton(
                                        label = stringResource(R.string.ui_question_resolve),
                                        background = Surface2,
                                        foreground = Mint,
                                        onClick = { onResolveQuestion(q) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (note.tags.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    note.tags.forEach { tag ->
                        Surface(
                            onClick = { onTagClick(tag) },
                            color = Surface1,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, Line),
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(stringResource(R.string.ui_note_tag, tag), style = MaterialTheme.typography.bodySmall, color = Ink2)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    if (picking) {
        SpacePickerDialog(
            title = stringResource(R.string.ui_space_picker_note_title),
            spaces = spaces,
            selectedId = note.spaceId,
            onSelect = { spaceId ->
                picking = false
                scope.launch(Dispatchers.IO) { NoteStore.assignSpace(note.id, spaceId) }
            },
            onDismiss = { picking = false }
        )
    }

    if (pickingReminder) {
        ReminderPickerDialog(
            title = note.title,
            existing = reminder,
            onSchedule = { triggerAtMs ->
                pickingReminder = false
                scope.launch(Dispatchers.IO) {
                    ReminderStore.schedule(
                        context = context,
                        type = ReminderTargetType.NOTE,
                        targetId = note.id.toString(),
                        title = note.title,
                        triggerAtMs = triggerAtMs
                    )
                }
                Toast.makeText(context, R.string.ui_reminder_saved_toast, Toast.LENGTH_SHORT).show()
            },
            onClear = if (reminder != null) {
                {
                    pickingReminder = false
                    scope.launch(Dispatchers.IO) {
                        ReminderStore.cancelForTarget(context, ReminderTargetType.NOTE, note.id.toString())
                    }
                    Toast.makeText(context, R.string.ui_reminder_cleared_toast, Toast.LENGTH_SHORT).show()
                }
            } else null,
            onDismiss = { pickingReminder = false }
        )
    }

    if (reprocessing) {
        ReprocessDialog(
            initialFormat = noteFormat,
            wasManuallyEdited = note.editedAt != null,
            onReprocess = { chosen -> reprocess(chosen) },
            onDismiss = { reprocessing = false }
        )
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.ui_note_delete_title),
            body = stringResource(R.string.ui_note_delete_body),
            confirmLabel = stringResource(R.string.ui_note_delete_confirm),
            destructive = true,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                onDelete(note.id)
            }
        )
    }
}

@Composable
private fun AudioPlayerBar(
    noteId: Long,
    file: File,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp
) {
    var playing by remember(noteId, file.absolutePath) { mutableStateOf(false) }
    var positionMs by remember(noteId, file.absolutePath) { mutableIntStateOf(0) }
    var durationMs by remember(noteId, file.absolutePath) { mutableIntStateOf(0) }
    val player = remember(noteId, file.absolutePath) { MediaPlayer() }
    var prepared by remember(noteId, file.absolutePath) { mutableStateOf(false) }

    DisposableEffect(noteId, file.absolutePath) {
        val ok = runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            durationMs = player.duration.coerceAtLeast(0)
            player.setOnCompletionListener {
                playing = false
                positionMs = 0
                runCatching { player.seekTo(0) }
            }
            true
        }.getOrDefault(false)
        prepared = ok
        onDispose {
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(0)
            delay(200)
        }
    }

    if (!prepared || durationMs <= 0) return

    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .border(1.dp, Line, RoundedCornerShape(20.dp))
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                onClick = {
                    if (playing) {
                        runCatching {
                            player.pause()
                            player.seekTo(0)
                        }
                        positionMs = 0
                        playing = false
                    } else {
                        runCatching {
                            player.start()
                            playing = true
                        }
                    }
                },
                color = if (playing) SignalFill else Surface2,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playing) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White)
                        )
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.ui_note_audio_play),
                            tint = Ink,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ui_note_audio_label), style = MaterialTheme.typography.labelSmall, color = Ink2)
                    Text(
                        "${formatDuration(positionMs / 1000)} / ${formatDuration(durationMs / 1000)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink2
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Signal,
                    trackColor = Line
                )
            }
        }
    }
}

@Composable
private fun ReprocessDialog(
    initialFormat: NoteFormat,
    wasManuallyEdited: Boolean,
    onReprocess: (NoteFormat) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(initialFormat) }
    val modelMissing = chosen.usesLlm && !TransformModel.installed

    AppDialogSurface(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_reprocess_title), style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.ui_reprocess_body),
            style = MaterialTheme.typography.bodySmall,
            color = Ink2
        )
        if (wasManuallyEdited) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = Surface2,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.ui_reprocess_edited_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        FormatChips(chosen, { chosen = it })
        Spacer(Modifier.height(6.dp))
        Text(formatBlurb(chosen), style = MaterialTheme.typography.bodySmall, color = Ink2)
        if (modelMissing) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ui_reprocess_missing_model), style = MaterialTheme.typography.bodySmall, color = Red)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
            Spacer(Modifier.width(10.dp))
            PillButton(
                stringResource(R.string.ui_reprocess_confirm),
                if (modelMissing) Surface2 else SignalFill,
                if (modelMissing) Ink2 else Color.White,
                enabled = !modelMissing,
                onClick = { onReprocess(chosen) }
            )
        }
    }
}

@Composable
private fun DetailSection(label: String, body: @Composable () -> Unit) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Ink2)
            Spacer(Modifier.height(10.dp))
            body()
        }
    }
}

private val noteModes =
    listOf("Insight", "Idea", "Action", "Reference", "Question", "Reflection", "Note")

@Composable
internal fun NoteEditorScreen(
    note: NoteItem,
    onCancel: () -> Unit,
    onSave: (StoredNote) -> Unit
) {
    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var oneLine by rememberSaveable(note.id) { mutableStateOf(note.oneLine) }
    var verbatim by rememberSaveable(note.id) { mutableStateOf(note.verbatim) }
    var why by rememberSaveable(note.id) { mutableStateOf(note.why) }
    var question by rememberSaveable(note.id) { mutableStateOf(note.question) }
    var tagsText by rememberSaveable(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
    var mode by rememberSaveable(note.id) { mutableStateOf(note.mode) }
    var itemsText by rememberSaveable(note.id) { mutableStateOf(note.items.joinToString("\n")) }
    var showDetails by rememberSaveable(note.id) { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf(false) }

    val noteFormat = NoteFormat.from(note.format)
    val showsItems = noteFormat == NoteFormat.LIST || noteFormat == NoteFormat.STEPS
    val editedItems = remember(itemsText) {
        itemsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val tags = remember(tagsText) {
        tagsText.split(",")
            .map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val dirty = title != note.title ||
        oneLine != note.oneLine ||
        verbatim != note.verbatim ||
        why != note.why ||
        question != note.question ||
        tagsText != note.tags.joinToString(", ") ||
        mode != note.mode ||
        itemsText != note.items.joinToString("\n")
    val hasContent = title.isNotBlank() || verbatim.isNotBlank() || oneLine.isNotBlank() || editedItems.isNotEmpty()

    val attemptCancel = {
        if (dirty) confirmingDiscard = true else onCancel()
    }

    BackHandler { attemptCancel() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 20.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackIconButton(
                onClick = attemptCancel,
                contentDescription = stringResource(R.string.ui_action_cancel)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.ui_editor_title),
                style = MaterialTheme.typography.labelSmall,
                color = Ink2,
                modifier = Modifier.weight(1f)
            )
            PillButton(
                label = stringResource(R.string.ui_action_save),
                background = if (hasContent) SignalFill else Surface2,
                foreground = if (hasContent) Color.White else Ink2,
                onClick = {
                    if (!hasContent) {
                        validationError = true
                        return@PillButton
                    }
                    val cleanQ = question.trim()
                    val nextOpen = if (cleanQ.isNotEmpty()) {
                        (listOf(cleanQ) + note.openQuestions.drop(1)).distinct()
                    } else {
                        note.openQuestions.drop(1)
                    }
                    onSave(
                        StoredNote(
                            id = note.id,
                            title = title.trim(),
                            oneLine = oneLine.trim(),
                            mode = mode,
                            whenText = note.whenText,
                            verbatim = verbatim.trim(),
                            why = why.trim(),
                            question = nextOpen.firstOrNull() ?: "",
                            tags = tags,
                            audioPath = null,
                            spaceId = note.spaceId,
                            editedAt = null,
                            format = note.format,
                            items = editedItems,
                            openQuestions = nextOpen,
                            questionDrafts = note.questionDrafts
                        )
                    )
                }
            )
        }

        if (validationError) {
            Text(
                stringResource(R.string.ui_editor_needs_content),
                style = MaterialTheme.typography.bodySmall,
                color = Red,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditorField(
                stringResource(R.string.ui_section_title),
                title,
                stringResource(R.string.ui_placeholder_title),
                {
                    title = it
                    validationError = false
                },
                singleLine = true
            )
            if (showsItems) {
                EditorField(
                    stringResource(R.string.ui_section_items),
                    itemsText,
                    stringResource(R.string.ui_placeholder_items),
                    {
                        itemsText = it
                        validationError = false
                    },
                    minHeight = 120.dp
                )
            }
            EditorField(
                stringResource(R.string.ui_section_what_i_said),
                verbatim,
                stringResource(R.string.ui_placeholder_what_i_said),
                {
                    verbatim = it
                    validationError = false
                },
                minHeight = 106.dp
            )
            Column {
                Text(stringResource(R.string.ui_section_mode), style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    noteModes.forEach { candidate ->
                        SubTab(modeLabel(candidate), candidate == mode) { mode = candidate }
                    }
                }
            }

            // Progressive disclosure for secondary fields
            Surface(
                onClick = { showDetails = !showDetails },
                color = Surface1,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.ui_editor_more_details),
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Ink2
                    )
                }
            }

            if (showDetails) {
                Column {
                    Text(
                        stringResource(R.string.ui_editor_format, formatLabel(noteFormat)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink2
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ui_editor_format_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
                }
                EditorField(
                    stringResource(R.string.ui_section_one_line),
                    oneLine,
                    stringResource(R.string.ui_placeholder_one_line),
                    { oneLine = it },
                    minHeight = 46.dp
                )
                EditorField(
                    stringResource(R.string.ui_section_why_it_matters),
                    why,
                    stringResource(R.string.ui_placeholder_why),
                    { why = it },
                    minHeight = 70.dp
                )
                EditorField(
                    stringResource(R.string.ui_section_open_question),
                    question,
                    stringResource(R.string.ui_placeholder_question),
                    { question = it },
                    minHeight = 70.dp
                )
                EditorField(
                    stringResource(R.string.ui_section_tags),
                    tagsText,
                    stringResource(R.string.ui_placeholder_tags),
                    { tagsText = it },
                    singleLine = true
                )
            }

            Text(
                stringResource(R.string.ui_editor_save_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
        }
    }

    if (confirmingDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                confirmingDiscard = false
                onCancel()
            },
            onKeepEditing = { confirmingDiscard = false }
        )
    }
}

private val wordGap = Regex("\\s+")

private fun derivedTitle(text: String, fallback: String): String {
    val words = text.trim()
        .split(wordGap)
        .filter { it.isNotEmpty() }
        .take(8)
        .joinToString(" ")
    return words.trimEnd('.', ',', ';', ':').ifBlank { fallback }
}

@Composable
internal fun NewNoteScreen(
    spaces: List<Space>,
    initialSpaceId: String?,
    onCancel: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textFocus = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf("") }
    var titleInput by rememberSaveable { mutableStateOf("") }
    var selectedSpaceId by rememberSaveable { mutableStateOf(initialSpaceId) }
    var format by rememberSaveable { mutableStateOf(Formats.next) }
    var useAi by rememberSaveable { mutableStateOf(TransformModel.installed) }
    var saving by remember { mutableStateOf(false) }
    var savingWithAi by remember { mutableStateOf(false) }
    var pickingSpace by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        textFocus.requestFocus()
    }

    val aiAvailable = TransformModel.installed && format.usesLlm
    val aiOn = useAi && aiAvailable
    val canSave = text.isNotBlank() && !saving
    val dirty = text.isNotBlank() || titleInput.isNotBlank()
    val spaceName = spaces.firstOrNull { it.id == selectedSpaceId }?.name
    val untitled = stringResource(R.string.common_untitled_note)

    fun save() {
        if (saving || text.isBlank()) return
        val typed = text
        val typedTitle = titleInput.trim()
        val chosen = format
        val shapeRequested = chosen.usesLlm
        val shape = shapeRequested && useAi && TransformModel.installed
        savingWithAi = shape
        saving = true
        scope.launch(Dispatchers.IO) {
            val (spaceNameForPrompt, spaceFocus) = SpaceStore.focusFor(selectedSpaceId)
            val fields = if (shape) {
                Transform.apply(
                    context.filesDir,
                    typed,
                    chosen,
                    AppLocale.modelLanguage(context),
                    spaceName = spaceNameForPrompt,
                    spaceFocus = spaceFocus
                )
            } else {
                null
            }
            val aiFallback = shape && fields == null
            if (aiFallback) {
                CaptureLog.add("notes: shaping failed, saved the typed text as written")
            }
            val newId = NoteStore.nextId()
            NoteStore.add(
                StoredNote(
                    id = newId,
                    title = typedTitle.ifBlank {
                        fields?.title?.takeIf { it.isNotBlank() } ?: derivedTitle(typed, untitled)
                    },
                    oneLine = fields?.oneLine ?: "",
                    mode = fields?.mode ?: "Note",
                    whenText = "Today " + SimpleDateFormat("HH:mm", Locale.US).format(Date()),
                    verbatim = typed.trim(),
                    why = fields?.why ?: "",
                    question = fields?.question ?: "",
                    tags = fields?.tags ?: emptyList(),
                    audioPath = null,
                    spaceId = selectedSpaceId,
                    format = if (shapeRequested && fields == null) NoteFormat.PLAIN.id else chosen.id,
                    items = fields?.items ?: emptyList(),
                    createdAt = System.currentTimeMillis(),
                    questionDrafts = fields?.questionDrafts ?: emptyList()
                )
            )
            withContext(Dispatchers.Main) {
                if (aiFallback) {
                    Toast.makeText(context, R.string.ui_new_note_ai_failed_toast, Toast.LENGTH_LONG).show()
                }
                onSaved(newId)
            }
        }
    }

    val attemptCancel = {
        if (!saving) {
            if (dirty) confirmingDiscard = true else onCancel()
        }
    }

    BackHandler { attemptCancel() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 20.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackIconButton(
                onClick = attemptCancel,
                contentDescription = stringResource(R.string.ui_action_cancel),
                enabled = !saving
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.ui_new_note_title),
                style = MaterialTheme.typography.labelSmall,
                color = Ink2,
                modifier = Modifier.weight(1f)
            )
            PillButton(
                stringResource(R.string.ui_action_save),
                if (canSave) SignalFill else Surface2,
                if (canSave) Color.White else Ink2,
                { save() },
                enabled = canSave
            )
        }

        if (saving) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Signal,
                    trackColor = Line
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (savingWithAi) stringResource(R.string.ui_new_note_shaping) else stringResource(R.string.ui_new_note_saving),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditorField(
                stringResource(R.string.ui_section_text),
                text,
                stringResource(R.string.ui_placeholder_new_note_text),
                { text = it },
                minHeight = 130.dp,
                enabled = !saving,
                required = true,
                focusRequester = textFocus
            )
            EditorField(
                stringResource(R.string.ui_section_title),
                titleInput,
                stringResource(R.string.ui_placeholder_new_note_title),
                { titleInput = it },
                singleLine = true,
                enabled = !saving
            )
            Column {
                Text(stringResource(R.string.ui_section_space), style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { pickingSpace = true },
                    enabled = !saving,
                    color = Surface1,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (selectedSpaceId == null) Line else Signal.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            spaceName ?: stringResource(R.string.ui_space_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (spaceName == null) Ink2 else SignalText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(stringResource(R.string.ui_action_change), style = MaterialTheme.typography.labelSmall, color = Ink2)
                    }
                }
            }
            Column {
                Text(stringResource(R.string.ui_section_format), style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.height(8.dp))
                FormatChips(format, { format = it }, enabled = !saving)
                Spacer(Modifier.height(6.dp))
                Text(formatBlurb(format), style = MaterialTheme.typography.bodySmall, color = Ink2)
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ui_new_note_shape_with_ai),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    SubTab(
                        stringResource(R.string.ui_action_on),
                        aiOn,
                        enabled = aiAvailable && !saving
                    ) { useAi = true }
                    Spacer(Modifier.width(8.dp))
                    SubTab(
                        stringResource(R.string.ui_action_off),
                        !aiOn,
                        enabled = !saving
                    ) { useAi = false }
                }
                if (!format.usesLlm) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ui_new_note_plain_hint), style = MaterialTheme.typography.bodySmall, color = Ink2)
                }
                if (!TransformModel.installed && format.usesLlm) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ui_new_note_no_model), style = MaterialTheme.typography.bodySmall, color = Ink2)
                }
            }
        }
    }

    if (pickingSpace) {
        SpacePickerDialog(
            title = stringResource(R.string.ui_space_picker_new_note_title),
            spaces = spaces,
            selectedId = selectedSpaceId,
            onSelect = { spaceId ->
                pickingSpace = false
                selectedSpaceId = spaceId
            },
            onDismiss = { pickingSpace = false }
        )
    }

    if (confirmingDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                confirmingDiscard = false
                onCancel()
            },
            onKeepEditing = { confirmingDiscard = false }
        )
    }
}
