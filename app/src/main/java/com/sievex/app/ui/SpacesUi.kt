package com.sievex.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sievex.app.AppLocale
import com.sievex.app.Formats
import com.sievex.app.LensArtifact
import com.sievex.app.LensService
import com.sievex.app.LensSpec
import com.sievex.app.LensStore
import com.sievex.app.MediaItem
import com.sievex.app.MediaStore
import com.sievex.app.NoteFormat
import com.sievex.app.NoteStore
import com.sievex.app.R
import com.sievex.app.Space
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import com.sievex.app.llm.Lens
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 2-column Bookshelf Book-Cover Grid for Spaces.
 */
@Composable
internal fun SpacesScreen(
    spaces: List<Space>,
    noteCounts: Map<String, Int>,
    mediaCounts: Map<String, Int>,
    insights: Map<String, LensArtifact?>,
    onNewSpace: () -> Unit,
    onOpen: (Space) -> Unit,
    onEditRequest: (Space) -> Unit,
    onDeleteRequest: (Space) -> Unit
) {
    val minuteTick = rememberMinuteTick()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(2) }, contentType = "header") {
            Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ui_tab_spaces),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Ink
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.ui_spaces_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PillButton(
                        label = stringResource(R.string.ui_space_new),
                        background = SignalFill,
                        foreground = Color.White,
                        onClick = onNewSpace
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(thickness = 1.dp, color = Line)
            }
        }

        if (spaces.isEmpty()) {
            item(span = { GridItemSpan(2) }, contentType = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EditorialArtwork(
                        resId = R.drawable.art_onboard_2,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.ui_spaces_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2
                    )
                }
            }
        } else {
            itemsIndexed(spaces, key = { _, s -> s.id }, contentType = { _, _ -> "book_cover" }) { index, space ->
                val spineColor = when (index % 4) {
                    0 -> Signal
                    1 -> Blue
                    2 -> Mint
                    else -> Amber
                }
                SpaceBookCoverCard(
                    space = space,
                    spineColor = spineColor,
                    noteCount = noteCounts[space.id] ?: 0,
                    mediaCount = mediaCounts[space.id] ?: 0,
                    insight = insights[space.id],
                    minuteTick = minuteTick,
                    onClick = { onOpen(space) },
                    onEdit = { onEditRequest(space) },
                    onDelete = { onDeleteRequest(space) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceBookCoverCard(
    space: Space,
    spineColor: Color,
    noteCount: Int,
    mediaCount: Int,
    insight: LensArtifact?,
    minuteTick: Int,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)

    Surface(
        color = Surface1,
        shape = shape,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(shape)
            .combinedClickable(
                onClickLabel = stringResource(R.string.ui_space_open_action),
                onLongClickLabel = stringResource(R.string.ui_space_delete_confirm),
                onLongClick = onDelete,
                onClick = onClick
            )
    ) {
        Row(Modifier.fillMaxSize()) {
            // Crisp 6dp left colored book-spine edge band + engraved book crease
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(spineColor)
            )
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Ink.copy(alpha = if (IsDarkTheme) 0.08f else 0.06f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .let { base ->
                        if (IsDarkTheme) {
                            base.background(Brush.horizontalGradient(listOf(spineColor.copy(alpha = 0.10f), Color.Transparent)))
                        } else {
                            base
                        }
                    }
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top genre header + overflow trigger
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ui_spaces_folio_genre),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = EditorialMono,
                                fontSize = 10.sp,
                                letterSpacing = 1.4.sp
                            ),
                            color = spineColor,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            Surface(
                                onClick = { menuOpen = true },
                                color = Color.Transparent,
                                shape = CircleShape,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.ui_action_more),
                                        tint = Ink2,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_action_edit), color = Ink) },
                                    onClick = {
                                        menuOpen = false
                                        onEdit()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_action_delete), color = Red) },
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = space.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = EditorialSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp
                        ),
                        color = Ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Colophon footer
                Column(
                    modifier = Modifier.padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(thickness = 1.dp, color = Line)
                    Spacer(Modifier.height(2.dp))
                    val notesLabel = if (noteCount == 1) {
                        stringResource(R.string.ui_notes_count_one)
                    } else {
                        stringResource(R.string.ui_notes_count_many, noteCount)
                    }
                    val mediaLabel = when (mediaCount) {
                        0 -> ""
                        1 -> " · " + stringResource(R.string.ui_media_count_one)
                        else -> " · " + stringResource(R.string.ui_media_count_many, mediaCount)
                    }
                    Text(
                        text = notesLabel + mediaLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = EditorialMono,
                            fontWeight = FontWeight.Normal,
                            fontSize = 10.sp
                        ),
                        color = Ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (insight == null) {
                                stringResource(R.string.ui_space_no_report)
                            } else {
                                stringResource(R.string.ui_space_report_age, relativeAge(insight.generatedAt, minuteTick))
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (space.focusPrompt.isNotBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ui_space_focus_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = BlueText
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bound Chapter Volume view for a single Space:
 * editorial Focus banner + [Margin Notes] / [Exhibits] toggle + clean bottom action pill.
 */
@Composable
internal fun SpaceScreen(
    space: Space,
    allNotes: List<NoteItem>,
    allMedia: List<MediaItem>,
    initialLensId: String? = null,
    onBack: () -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onMediaChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sub by rememberSaveable(space.id) { mutableStateOf(if (initialLensId != null) 1 else 0) }
    var editingSpace by remember(space.id) { mutableStateOf(false) }
    var editingFocus by remember(space.id) { mutableStateOf(false) }
    var topMenuOpen by remember(space.id) { mutableStateOf(false) }
    var addMenuOpen by remember(space.id) { mutableStateOf(false) }
    var addingClip by remember(space.id) { mutableStateOf(false) }
    var importingMedia by remember(space.id) { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importingMedia = true
            scope.launch {
                val importedCount = withContext(Dispatchers.IO) {
                    uris.count { uri -> MediaStore.importFromUri(context, uri, space.id) != null }
                }
                importingMedia = false
                if (importedCount < uris.size) {
                    Toast.makeText(context, R.string.ui_space_import_partial_failed, Toast.LENGTH_SHORT).show()
                }
                if (importedCount > 0) onMediaChanged()
            }
        }
    }

    var lenses by remember(space.id) { mutableStateOf<List<LensSpec>>(emptyList()) }
    var lensEpoch by remember(space.id) { mutableStateOf(0) }
    var lensesLoaded by remember(space.id) { mutableStateOf(false) }
    var selectedLensId by rememberSaveable(space.id) { mutableStateOf(initialLensId) }
    LaunchedEffect(initialLensId) {
        if (initialLensId != null) {
            selectedLensId = initialLensId
            sub = 1
        }
    }
    var artifact by remember(space.id) { mutableStateOf<LensArtifact?>(null) }
    var runningLensId by remember(space.id) { mutableStateOf<String?>(null) }
    var failedLensId by remember(space.id) { mutableStateOf<String?>(null) }
    var addingLens by remember(space.id) { mutableStateOf(false) }
    var creatingOrEditingLens by remember(space.id) { mutableStateOf<LensSpec?>(null) }
    var showCustomLensDialog by remember(space.id) { mutableStateOf(false) }
    var removingLens by remember(space.id) { mutableStateOf<LensSpec?>(null) }
    var confirmingDeleteCustomLens by remember(space.id) { mutableStateOf<LensSpec?>(null) }

    val spaceNotes = remember(allNotes, space.id) { allNotes.filter { it.spaceId == space.id } }
    val spaceMedia = remember(allMedia, space.id) { allMedia.filter { it.spaceId == space.id } }
    val hasReadableContext = remember(spaceNotes, spaceMedia) {
        spaceNotes.isNotEmpty() || spaceMedia.any { it.caption.isNotBlank() || it.ocrText.isNotBlank() || !it.ocrScanned }
    }
    val feedEntries = remember(spaceNotes, spaceMedia) {
        (spaceNotes.map { FeedEntry.NoteEntry(it) } + spaceMedia.map { FeedEntry.MediaEntry(it) })
            .sortedByDescending { it.timestamp }
    }
    val selectedId = remember(selectedLensId, lenses) {
        selectedLensId?.takeIf { id -> lenses.any { it.id == id } } ?: lenses.firstOrNull()?.id
    }
    val selectedLens = remember(selectedId, lenses) { lenses.firstOrNull { it.id == selectedId } }
    val shown = artifact
    val stale = remember(shown, spaceNotes, spaceMedia, space.focusPrompt) {
        shown != null && artifactIsStale(shown, spaceNotes, spaceMedia, space.focusPrompt)
    }

    val lensStoreVersion by LensStore.version.collectAsState()
    LaunchedEffect(space.id, space.lensIds, lensEpoch, lensStoreVersion) {
        lenses = withContext(Dispatchers.IO) { LensStore.resolve(space.lensIds) }
        lensesLoaded = true
    }

    LaunchedEffect(space.id, selectedId) {
        artifact = null
        runningLensId = null
        failedLensId = null
        val wanted = selectedId ?: return@LaunchedEffect
        combine(SpaceStore.version, LensService.version) { _, _ -> Unit }.collect {
            val latest = withContext(Dispatchers.IO) { SpaceStore.latest(space.id, wanted) }
            if (latest != artifact) artifact = latest
            when (LensService.state(space.id, wanted)) {
                LensService.RunState.RUNNING -> {
                    runningLensId = wanted
                    failedLensId = null
                }
                LensService.RunState.DONE -> {
                    if (runningLensId == wanted) runningLensId = null
                    failedLensId = null
                }
                LensService.RunState.FAILED -> {
                    if (runningLensId == wanted) runningLensId = null
                    failedLensId = wanted
                }
                LensService.RunState.IDLE -> Unit
            }
        }
    }

    val generate: (LensSpec) -> Unit = { spec ->
        if (runningLensId == null && hasReadableContext) {
            runningLensId = spec.id
            failedLensId = null
            LensService.start(context, space.id, spec.id)
        }
    }

    val spaceWrites = remember { Mutex() }

    val setLensEnabled: (String, Boolean) -> Unit = { lensId, enabled ->
        scope.launch {
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(
                    lensIds = if (enabled) (current.lensIds + lensId).distinct() else current.lensIds - lensId
                )
            }
            lensEpoch++
        }
    }

    val saveCustomLens: (LensSpec?, LensDraft) -> Unit = { editing, draft ->
        scope.launch {
            val id = editing?.id ?: ("cx_" + UUID.randomUUID().toString().replace("-", "").take(12))
            withContext(Dispatchers.IO) {
                LensStore.saveCustom(
                    LensSpec(
                        id = id,
                        name = draft.name,
                        description = draft.description,
                        instruction = draft.instruction,
                        sections = draft.sections,
                        builtIn = false
                    )
                )
            }
            if (editing == null) {
                spaceWrites.updateSpace(space.id) { current ->
                    current.copy(lensIds = (current.lensIds + id).distinct())
                }
                selectedLensId = id
            }
            lensEpoch++
        }
    }

    val deleteCustomLens: (String) -> Unit = { lensId ->
        scope.launch {
            withContext(Dispatchers.IO) {
                LensStore.deleteCustom(lensId)
                SpaceStore.removeLensEverywhere(lensId)
            }
            if (selectedLensId == lensId) selectedLensId = null
            lensEpoch++
        }
    }

    val saveSpaceEdits: (String, String, String) -> Unit = { name, description, focusPrompt ->
        scope.launch {
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(
                    name = name,
                    description = description.trim(),
                    focusPrompt = focusPrompt.trim()
                )
            }
        }
    }

    val saveSpaceFocus: (String) -> Unit = { focusPrompt ->
        scope.launch {
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(focusPrompt = focusPrompt.trim())
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
            .imePadding()
    ) {
        // Bound volume top bar
        Row(
            Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackIconButton(onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.ui_spaces_folio_genre),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = EditorialMono,
                    letterSpacing = 1.4.sp
                ),
                color = Ink2
            )
            Spacer(Modifier.weight(1f))
            Box {
                Surface(
                    onClick = { topMenuOpen = true },
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
                    expanded = topMenuOpen,
                    onDismissRequest = { topMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_space_edit_title), color = Ink) },
                        onClick = {
                            topMenuOpen = false
                            editingSpace = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_space_focus_title), color = Ink) },
                        onClick = {
                            topMenuOpen = false
                            editingFocus = true
                        }
                    )
                }
            }
        }

        // Bound chapter title + colophon + Focus banner
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                space.name,
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            val notesSummary = if (spaceNotes.size == 1) stringResource(R.string.ui_notes_count_one) else stringResource(R.string.ui_notes_count_many, spaceNotes.size)
            val mediaSummary = when (spaceMedia.size) {
                0 -> ""
                1 -> " · " + stringResource(R.string.ui_media_count_one)
                else -> " · " + stringResource(R.string.ui_media_count_many, spaceMedia.size)
            }
            Text(
                notesSummary + mediaSummary,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                color = Ink2
            )
            if (space.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(space.description, style = MaterialTheme.typography.bodySmall, color = Ink2)
            }
            Spacer(Modifier.height(10.dp))
            SpaceFocusStrip(
                focusPrompt = space.focusPrompt,
                onClick = { editingFocus = true }
            )
        }
        Spacer(Modifier.height(12.dp))

        // [Margin Notes] / [Exhibits] editorial segmented toggle
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Line),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpaceSegmentTab(
                    label = stringResource(R.string.ui_space_margin_notes_tab),
                    selected = sub == 0,
                    onClick = { sub = 0 },
                    modifier = Modifier.weight(1f)
                )
                SpaceSegmentTab(
                    label = stringResource(R.string.ui_space_exhibits_tab),
                    selected = sub == 1,
                    onClick = { sub = 1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (importingMedia) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                color = Signal,
                trackColor = Line
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = Line)

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (sub == 0) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    if (feedEntries.isEmpty()) {
                        item(contentType = "empty") {
                            Text(
                                stringResource(R.string.ui_space_empty_context),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink2,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                    items(
                        feedEntries,
                        key = { entry ->
                            when (entry) {
                                is FeedEntry.NoteEntry -> "n_${entry.note.id}"
                                is FeedEntry.MediaEntry -> "m_${entry.media.id}"
                            }
                        },
                        contentType = { entry ->
                            when (entry) {
                                is FeedEntry.NoteEntry -> "note"
                                is FeedEntry.MediaEntry -> "media"
                            }
                        }
                    ) { entry ->
                        when (entry) {
                            is FeedEntry.NoteEntry -> {
                                EditorialNoteRow(entry.note, onClick = { onOpenNote(entry.note) })
                                HorizontalDivider(thickness = 1.dp, color = Line)
                            }
                            is FeedEntry.MediaEntry -> {
                                Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                                    MediaCard(entry.media, onClick = { onOpenMedia(entry.media) })
                                }
                                HorizontalDivider(thickness = 1.dp, color = Line)
                            }
                        }
                    }
                }

                // Clean bottom action pill for Margin Notes
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    Surface(
                        onClick = { if (!importingMedia) addMenuOpen = true },
                        color = SignalFill,
                        shape = RoundedCornerShape(999.dp),
                        shadowElevation = 2.dp,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                stringResource(R.string.ui_space_add_to_space),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_space_add_image), color = Ink) },
                            enabled = !importingMedia,
                            onClick = {
                                addMenuOpen = false
                                pickImages.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_space_add_clip), color = Ink) },
                            onClick = {
                                addMenuOpen = false
                                addingClip = true
                            }
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(contentType = "chips") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(lenses, key = { it.id }) { spec ->
                                LensChip(
                                    spec = spec,
                                    selected = spec.id == selectedId,
                                    onClick = { selectedLensId = spec.id },
                                    onManage = { removingLens = spec }
                                )
                            }
                            item(contentType = "add_chip") { AddLensChip { addingLens = true } }
                        }
                    }
                    if (lenses.isNotEmpty()) {
                        item(contentType = "hint") {
                            Text(
                                stringResource(R.string.ui_space_hold_chip_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink2,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                    if (!lensesLoaded) {
                        item(contentType = "loading_lenses") {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                                color = Signal,
                                trackColor = Line
                            )
                        }
                    } else {
                        val lens = selectedLens
                        if (lens != null && failedLensId == lens.id) {
                            item(contentType = "error_banner") {
                                Surface(
                                    color = Surface1,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .border(1.dp, Red.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            stringResource(R.string.ui_lens_failed),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Red,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        PillButton(
                                            label = stringResource(R.string.ui_action_dismiss),
                                            background = Surface2,
                                            foreground = Ink,
                                            onClick = { failedLensId = null }
                                        )
                                    }
                                }
                            }
                        }
                        item(contentType = "artifact") {
                            Box(Modifier.padding(horizontal = 20.dp)) {
                                LensArtifactCard(
                                    spec = lens,
                                    artifact = shown,
                                    stale = stale,
                                    onAddLens = { addingLens = true }
                                )
                            }
                        }
                        if (lens != null) {
                            item(contentType = "actions") {
                                Column(Modifier.padding(horizontal = 20.dp)) {
                                    if (!lens.builtIn) {
                                        PillButton(
                                            label = stringResource(R.string.ui_lens_edit),
                                            background = Surface1,
                                            foreground = Ink,
                                            border = BorderStroke(1.dp, Line),
                                            onClick = {
                                                creatingOrEditingLens = lens
                                                showCustomLensDialog = true
                                            }
                                        )
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    if (runningLensId == lens.id) {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Signal,
                                            trackColor = Line
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            stringResource(R.string.ui_lens_working),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Ink2
                                        )
                                    }
                                    if (!hasReadableContext) {
                                        Text(
                                            stringResource(R.string.ui_lens_needs_notes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Ink2
                                        )
                                    } else if (stale) {
                                        Text(
                                            stringResource(R.string.ui_lens_stale_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Amber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Clean bottom action pill for Exhibits / Lenses
                val activeLens = selectedLens
                if (lensesLoaded && activeLens != null) {
                    val busy = runningLensId != null
                    val enabled = !busy && hasReadableContext
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    ) {
                        PillButton(
                            label = if (shown == null) stringResource(R.string.ui_lens_generate) else stringResource(R.string.ui_lens_regenerate),
                            background = if (enabled) SignalFill else Surface2,
                            foreground = if (enabled) Color.White else Ink2,
                            border = if (enabled) null else BorderStroke(1.dp, Line),
                            enabled = enabled,
                            onClick = { generate(activeLens) }
                        )
                    }
                }
            }
        }
    }

    if (addingClip) {
        AddClipToSpaceDialog(
            spaceId = space.id,
            onDismiss = { addingClip = false },
            onSaved = {
                addingClip = false
                onMediaChanged()
            }
        )
    }

    if (editingSpace) {
        SpaceEditorDialog(
            name = space.name,
            description = space.description,
            focusPrompt = space.focusPrompt,
            onSave = { name, description, focusPrompt ->
                editingSpace = false
                saveSpaceEdits(name, description, focusPrompt)
            },
            onDismiss = { editingSpace = false }
        )
    }

    if (editingFocus) {
        SpaceFocusDialog(
            focusPrompt = space.focusPrompt,
            onSave = { nextFocus ->
                editingFocus = false
                saveSpaceFocus(nextFocus)
            },
            onDismiss = { editingFocus = false }
        )
    }

    if (addingLens) {
        var customLenses by remember { mutableStateOf<List<LensSpec>>(emptyList()) }
        var customLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(addingLens) {
            customLoaded = false
            customLenses = withContext(Dispatchers.IO) { LensStore.custom() }
            customLoaded = true
        }
        LensPickerDialog(
            loadingCustom = !customLoaded,
            availableBuiltIns = LensStore.builtIns().filter { spec -> lenses.none { it.id == spec.id } },
            availableCustom = customLenses.filter { spec -> lenses.none { it.id == spec.id } },
            onPick = { spec ->
                addingLens = false
                selectedLensId = spec.id
                setLensEnabled(spec.id, true)
            },
            onNewCustom = {
                addingLens = false
                creatingOrEditingLens = null
                showCustomLensDialog = true
            },
            onDismiss = { addingLens = false }
        )
    }

    if (showCustomLensDialog) {
        val editing = creatingOrEditingLens
        CustomLensDialog(
            initial = editing,
            onSave = { draft ->
                showCustomLensDialog = false
                creatingOrEditingLens = null
                saveCustomLens(editing, draft)
            },
            onDismiss = {
                showCustomLensDialog = false
                creatingOrEditingLens = null
            }
        )
    }

    val lensToRemove = removingLens
    if (lensToRemove != null) {
        RemoveLensDialog(
            spec = lensToRemove,
            onRemoveFromSpace = {
                removingLens = null
                if (selectedLensId == lensToRemove.id) selectedLensId = null
                setLensEnabled(lensToRemove.id, false)
            },
            onEditCustom = if (lensToRemove.builtIn) null else {
                {
                    removingLens = null
                    creatingOrEditingLens = lensToRemove
                    showCustomLensDialog = true
                }
            },
            onDeleteRequest = if (lensToRemove.builtIn) null else {
                {
                    removingLens = null
                    confirmingDeleteCustomLens = lensToRemove
                }
            },
            onDismiss = { removingLens = null }
        )
    }

    val deleteConfirmLens = confirmingDeleteCustomLens
    if (deleteConfirmLens != null) {
        ConfirmDialog(
            title = stringResource(R.string.ui_lens_delete_confirm_title, lensName(deleteConfirmLens)),
            body = stringResource(R.string.ui_lens_delete_confirm_body),
            confirmLabel = stringResource(R.string.ui_lens_delete),
            destructive = true,
            onConfirm = {
                confirmingDeleteCustomLens = null
                deleteCustomLens(deleteConfirmLens.id)
            },
            onDismiss = { confirmingDeleteCustomLens = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LensChip(
    spec: LensSpec,
    selected: Boolean,
    onClick: () -> Unit,
    onManage: () -> Unit
) {
    val name = lensName(spec)
    Surface(
        color = if (selected) SignalSoft else Surface1,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (selected) Signal.copy(alpha = 0.45f) else Line),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .combinedClickable(
                onClickLabel = stringResource(R.string.ui_lens_show_action, name),
                onLongClickLabel = stringResource(R.string.ui_lens_remove_action, name),
                onLongClick = onManage,
                onClick = onClick
            )
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = if (selected) 8.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) SignalText else Ink2
            )
            if (!spec.builtIn) {
                Spacer(Modifier.width(7.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (selected) SignalText else Blue, CircleShape)
                )
            }
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onManage() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.ui_lens_remove_action, name),
                        tint = SignalText,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddLensChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Surface1,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.heightIn(min = 44.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(stringResource(R.string.ui_lens_add_chip), style = MaterialTheme.typography.labelMedium, color = Ink)
        }
    }
}

@Composable
private fun LensArtifactCard(
    spec: LensSpec?,
    artifact: LensArtifact?,
    stale: Boolean,
    onAddLens: () -> Unit
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            val lens = spec
            if (lens == null) {
                Text(
                    stringResource(R.string.ui_lens_none_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ui_lens_none_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(14.dp))
                PillButton(stringResource(R.string.ui_lens_add), SignalFill, Color.White, onAddLens)
            } else if (artifact == null) {
                val lensLabel = lensName(lens)
                Text(
                    stringResource(R.string.ui_lens_empty_title, lensLabel.uppercase()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    lensDescription(lens),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ui_lens_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lensName(lens).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalText
                    )
                    Spacer(Modifier.weight(1f))
                    if (stale) StaleBadge()
                }
                Spacer(Modifier.height(12.dp))
                val strippedBody = remember(artifact.body) { Lens.stripCitations(artifact.body) }
                MarkdownBody(strippedBody)
                Spacer(Modifier.height(14.dp))
                val minuteTick = rememberMinuteTick()
                val age = relativeAge(artifact.generatedAt, minuteTick)
                Text(
                    if (artifact.covered.size == 1) stringResource(R.string.ui_lens_from_notes_one, age)
                    else stringResource(R.string.ui_lens_from_notes, artifact.covered.size, age),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialMono),
                    color = Ink2
                )
            }
        }
    }
}

@Composable
private fun StaleBadge(label: String = stringResource(R.string.ui_stale)) {
    Box(
        Modifier
            .border(1.dp, Amber.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Amber)
    }
}

@Composable
private fun MarkdownBody(markdown: String) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(
                    Modifier.height(
                        when (block.kind) {
                            MdKind.H2, MdKind.H3 -> 16.dp
                            else -> 8.dp
                        }
                    )
                )
            }
            when (block.kind) {
                MdKind.H2 -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialSerif),
                    color = Ink
                )
                MdKind.H3 -> Text(block.text.uppercase(), style = MaterialTheme.typography.labelSmall, color = SignalText)
                MdKind.BULLET -> Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = SignalText)
                    Spacer(Modifier.width(8.dp))
                    LinkifiedText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2,
                        modifier = Modifier.weight(1f),
                        precomputedRanges = block.linkRanges
                    )
                }
                MdKind.PARAGRAPH -> LinkifiedText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                    precomputedRanges = block.linkRanges
                )
            }
        }
    }
}

private enum class MdKind { H2, H3, BULLET, PARAGRAPH }

private data class MdBlock(
    val kind: MdKind,
    val text: String,
    val linkRanges: List<IntRange> = emptyList()
)

private fun parseMarkdown(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            val text = paragraph.toString().trim()
            blocks.add(MdBlock(MdKind.PARAGRAPH, text, linkRanges(text)))
            paragraph.setLength(0)
        }
    }

    markdown.split("\n").forEach { raw ->
        val clean = raw.trim().replace("**", "").trim()
        when {
            clean.isEmpty() -> flush()
            clean.startsWith("### ") -> {
                flush()
                blocks.add(MdBlock(MdKind.H3, clean.removePrefix("###").trim()))
            }
            clean.startsWith("## ") -> {
                flush()
                blocks.add(MdBlock(MdKind.H2, clean.removePrefix("##").trim()))
            }
            clean.startsWith("# ") -> {
                flush()
                blocks.add(MdBlock(MdKind.H2, clean.removePrefix("#").trim()))
            }
            clean.startsWith("- ") || clean.startsWith("* ") || clean.startsWith("• ") -> {
                flush()
                val text = clean.drop(2).trim()
                blocks.add(MdBlock(MdKind.BULLET, text, linkRanges(text)))
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(clean)
            }
        }
    }
    flush()
    return blocks
}

@Composable
private fun rememberMinuteTick(): Int {
    var minuteTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            minuteTick++
        }
    }
    return minuteTick
}

@Composable
private fun relativeAge(timestamp: Long, minuteTick: Int = 0): String {
    val diff = System.currentTimeMillis() - timestamp + (minuteTick * 0L)
    return when {
        timestamp <= 0L -> stringResource(R.string.ui_age_never)
        diff <= 60_000L -> stringResource(R.string.ui_age_just_now)
        diff < 3_600_000L -> stringResource(R.string.ui_age_minutes, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.ui_age_hours, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.ui_age_days, diff / 86_400_000L)
        else -> stringResource(R.string.ui_age_weeks, diff / 604_800_000L)
    }
}

private fun artifactIsStale(
    artifact: LensArtifact,
    spaceNotes: List<NoteItem>,
    spaceMedia: List<MediaItem>,
    spaceFocusPrompt: String
): Boolean {
    if (artifact.focusPrompt.trim() != spaceFocusPrompt.trim()) return true
    if (spaceNotes.any { it.id !in artifact.covered }) return true
    val present = spaceNotes.mapTo(HashSet()) { it.id }
    if (artifact.covered.any { it !in present }) return true
    val readableMedia = spaceMedia.filter { it.caption.isNotBlank() || it.ocrText.isNotBlank() }
    if (readableMedia.any { it.id !in artifact.coveredMedia }) return true
    val presentMedia = readableMedia.mapTo(HashSet()) { it.id }
    return artifact.coveredMedia.any { it !in presentMedia }
}

private suspend fun Mutex.updateSpace(spaceId: String, mutate: (Space) -> Space) {
    withLock {
        withContext(Dispatchers.IO) {
            val fresh = SpaceStore.all().firstOrNull { it.id == spaceId } ?: return@withContext
            SpaceStore.update(mutate(fresh))
        }
    }
}

@Composable
private fun SpaceSegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (selected) SignalSoft else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = if (selected) BorderStroke(1.dp, Signal.copy(alpha = 0.4f)) else null,
        modifier = modifier.heightIn(min = 40.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = EditorialSerif,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (selected) SignalText else Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpaceFocusStrip(
    focusPrompt: String,
    onClick: () -> Unit
) {
    val active = focusPrompt.isNotBlank()
    Surface(
        onClick = onClick,
        color = if (active) BlueSoft else Surface1,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (active) Blue.copy(alpha = 0.42f) else Line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (active) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ui_space_focus_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = BlueText
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        focusPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    stringResource(R.string.ui_action_edit),
                    style = MaterialTheme.typography.labelSmall,
                    color = BlueText
                )
            } else {
                Text(
                    stringResource(R.string.ui_space_focus_add),
                    style = MaterialTheme.typography.labelMedium,
                    color = BlueText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.ui_space_focus_add_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SpaceFocusDialog(
    focusPrompt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(focusPrompt) }
    val clean = draft.trim()
    val dirty = clean != focusPrompt.trim()

    AppDialogSurface(onDismissRequest = onDismiss, modifier = Modifier.imePadding()) {
        Column(
            Modifier
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.ui_space_focus_title), style = MaterialTheme.typography.headlineSmall, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ui_space_focus_hint), style = MaterialTheme.typography.bodySmall, color = Ink2)
            Spacer(Modifier.height(14.dp))
            EditorField(
                label = stringResource(R.string.ui_section_focus_prompt),
                value = draft,
                placeholder = stringResource(R.string.ui_placeholder_space_focus),
                onChange = { draft = it },
                minHeight = 96.dp
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (draft.isNotBlank()) {
                    PillButton(
                        label = stringResource(R.string.ui_action_clear),
                        background = Surface2,
                        foreground = Red,
                        onClick = { draft = "" }
                    )
                    Spacer(Modifier.width(8.dp))
                }
                PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
                Spacer(Modifier.width(10.dp))
                PillButton(
                    label = stringResource(R.string.ui_action_save),
                    background = if (dirty) SignalFill else Surface2,
                    foreground = if (dirty) Color.White else Ink2,
                    enabled = dirty,
                    onClick = { if (dirty) onSave(clean) }
                )
            }
        }
    }
}

@Composable
internal fun SpaceEditorDialog(
    name: String,
    description: String,
    focusPrompt: String = "",
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var editName by remember { mutableStateOf(name) }
    var editDescription by remember { mutableStateOf(description) }
    var editFocus by remember { mutableStateOf(focusPrompt) }
    var confirmingDiscard by remember { mutableStateOf(false) }

    val clean = editName.trim()
    val cleanDesc = editDescription.trim()
    val cleanFocus = editFocus.trim()
    val dirty = clean != name.trim() || cleanDesc != description.trim() || cleanFocus != focusPrompt.trim()
    val ready = clean.isNotEmpty() && dirty

    val attemptDismiss = {
        if (dirty) confirmingDiscard = true else onDismiss()
    }

    AppDialogSurface(onDismissRequest = attemptDismiss, modifier = Modifier.imePadding()) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.ui_space_edit_title), style = MaterialTheme.typography.headlineSmall, color = Ink)
            Spacer(Modifier.height(14.dp))
            EditorField(
                stringResource(R.string.ui_section_name),
                editName,
                stringResource(R.string.ui_space_name_placeholder),
                { editName = it },
                singleLine = true,
                required = true
            )
            Spacer(Modifier.height(14.dp))
            EditorField(
                stringResource(R.string.ui_section_description),
                editDescription,
                stringResource(R.string.ui_placeholder_space_description),
                { editDescription = it },
                minHeight = 64.dp
            )
            Spacer(Modifier.height(14.dp))
            EditorField(
                stringResource(R.string.ui_section_focus_prompt),
                editFocus,
                stringResource(R.string.ui_placeholder_space_focus),
                { editFocus = it },
                minHeight = 84.dp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ui_space_focus_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, attemptDismiss)
                Spacer(Modifier.width(10.dp))
                PillButton(
                    stringResource(R.string.ui_action_save),
                    if (ready) SignalFill else Surface2,
                    if (ready) Color.White else Ink2,
                    { if (ready) onSave(clean, cleanDesc, cleanFocus) },
                    enabled = ready
                )
            }
        }
    }

    if (confirmingDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                confirmingDiscard = false
                onDismiss()
            },
            onKeepEditing = { confirmingDiscard = false }
        )
    }
}

private data class LensDraft(
    val name: String,
    val description: String,
    val instruction: String,
    val sections: List<String>
)

@Composable
private fun LensPickerDialog(
    loadingCustom: Boolean,
    availableBuiltIns: List<LensSpec>,
    availableCustom: List<LensSpec>,
    onPick: (LensSpec) -> Unit,
    onNewCustom: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialogSurface(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_lens_add), style = MaterialTheme.typography.headlineSmall, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.ui_lens_picker_body),
            style = MaterialTheme.typography.bodySmall,
            color = Ink2
        )
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
        ) {
            availableBuiltIns.forEach { spec ->
                LensPickRow(spec, onClick = { onPick(spec) })
                Spacer(Modifier.height(6.dp))
            }
            if (loadingCustom) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Signal,
                    trackColor = Line
                )
                Spacer(Modifier.height(8.dp))
            } else {
                availableCustom.forEach { spec ->
                    LensPickRow(spec, onClick = { onPick(spec) })
                    Spacer(Modifier.height(6.dp))
                }
                if (availableBuiltIns.isEmpty() && availableCustom.isEmpty()) {
                    Text(
                        stringResource(R.string.ui_lens_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            PickerRow(stringResource(R.string.ui_lens_picker_new_custom), false, onNewCustom)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
        }
    }
}

@Composable
private fun LensPickRow(spec: LensSpec, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(lensName(spec), style = MaterialTheme.typography.bodyMedium, color = Ink)
            if (lensDescription(spec).isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    lensDescription(spec),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!spec.builtIn) {
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ui_lens_custom_badge), style = MaterialTheme.typography.labelSmall, color = BlueText)
        }
    }
}

@Composable
private fun CustomLensDialog(
    initial: LensSpec?,
    onSave: (LensDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var instruction by remember { mutableStateOf(initial?.instruction ?: "") }
    var sectionsText by remember { mutableStateOf(initial?.sections?.joinToString(", ") ?: "") }
    var confirmingDiscard by remember { mutableStateOf(false) }

    val cleanName = name.trim()
    val cleanInstruction = instruction.trim()
    val sections = remember(sectionsText) {
        sectionsText.split(",")
            .map { it.trim().removePrefix("##").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val ready = cleanName.isNotEmpty() && cleanInstruction.isNotEmpty()
    val dirty = if (initial == null) {
        name.isNotBlank() || description.isNotBlank() || instruction.isNotBlank() || sectionsText.isNotBlank()
    } else {
        cleanName != initial.name ||
            description.trim() != initial.description ||
            cleanInstruction != initial.instruction ||
            sections != initial.sections
    }

    val attemptDismiss = {
        if (dirty) confirmingDiscard = true else onDismiss()
    }

    AppDialogSurface(onDismissRequest = attemptDismiss, modifier = Modifier.imePadding()) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(if (initial == null) R.string.ui_lens_custom_title else R.string.ui_lens_custom_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Ink
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ui_lens_custom_body),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
            Spacer(Modifier.height(14.dp))
            EditorField(
                stringResource(R.string.ui_section_name),
                name,
                stringResource(R.string.ui_placeholder_lens_name),
                { name = it },
                singleLine = true,
                required = true
            )
            Spacer(Modifier.height(12.dp))
            EditorField(
                stringResource(R.string.ui_section_description),
                description,
                stringResource(R.string.ui_placeholder_lens_description),
                { description = it },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            EditorField(
                stringResource(R.string.ui_section_instruction),
                instruction,
                stringResource(R.string.ui_placeholder_lens_instruction),
                { instruction = it },
                minHeight = 106.dp,
                required = true
            )
            Spacer(Modifier.height(12.dp))
            EditorField(
                stringResource(R.string.ui_section_sections),
                sectionsText,
                stringResource(R.string.ui_placeholder_lens_sections),
                { sectionsText = it },
                singleLine = true
            )
            if (sections.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sections.forEach { sec ->
                        Box(
                            Modifier
                                .border(1.dp, Line, CircleShape)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("## $sec", style = MaterialTheme.typography.labelSmall, color = Ink2)
                        }
                    }
                }
            }
            if (!ready) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.ui_lens_custom_required_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, attemptDismiss)
                Spacer(Modifier.width(10.dp))
                PillButton(
                    label = stringResource(if (initial == null) R.string.ui_lens_create else R.string.ui_action_save),
                    background = if (ready) SignalFill else Surface2,
                    foreground = if (ready) Color.White else Ink2,
                    onClick = {
                        if (ready) {
                            onSave(
                                LensDraft(
                                    name = cleanName,
                                    description = description.trim(),
                                    instruction = cleanInstruction,
                                    sections = sections
                                )
                            )
                        }
                    },
                    enabled = ready
                )
            }
        }
    }

    if (confirmingDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                confirmingDiscard = false
                onDismiss()
            },
            onKeepEditing = { confirmingDiscard = false }
        )
    }
}

@Composable
private fun RemoveLensDialog(
    spec: LensSpec,
    onRemoveFromSpace: () -> Unit,
    onEditCustom: (() -> Unit)?,
    onDeleteRequest: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    AppDialogSurface(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.ui_lens_remove_title, lensName(spec)),
            style = MaterialTheme.typography.headlineSmall,
            color = Ink
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (onDeleteRequest == null) {
                stringResource(R.string.ui_lens_remove_body_builtin)
            } else {
                stringResource(R.string.ui_lens_remove_body_custom)
            },
            style = MaterialTheme.typography.bodySmall,
            color = Ink2
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
            Spacer(Modifier.width(10.dp))
            PillButton(stringResource(R.string.ui_action_remove), Surface2, SignalText, onRemoveFromSpace)
        }
        if (onEditCustom != null || onDeleteRequest != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onEditCustom != null) {
                    PillButton(stringResource(R.string.ui_action_edit), Surface2, Ink, onEditCustom)
                    if (onDeleteRequest != null) Spacer(Modifier.width(10.dp))
                }
                if (onDeleteRequest != null) {
                    PillButton(stringResource(R.string.ui_lens_delete), RedFill, Color.White, onDeleteRequest)
                }
            }
        }
    }
}

@Composable
private fun AddClipToSpaceDialog(
    spaceId: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val llmReady = remember { TransformModel.isInstalled(context.filesDir) }

    var clipText by rememberSaveable { mutableStateOf("") }
    var reaction by rememberSaveable { mutableStateOf("") }
    var chosenFormat by remember { mutableStateOf(if (llmReady) Formats.next else NoteFormat.PLAIN) }
    var saving by remember { mutableStateOf(false) }

    AppDialogSurface(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ui_share_text_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                PillButton(
                    label = stringResource(R.string.ui_clip_paste_clipboard),
                    background = BlueSoft,
                    foreground = BlueText,
                    border = BorderStroke(1.dp, Blue.copy(alpha = 0.44f)),
                    enabled = !saving,
                    onClick = {
                        val pasted = clipboard.getText()?.text?.trim().orEmpty()
                        if (pasted.isNotEmpty()) {
                            clipText = pasted
                        } else {
                            Toast.makeText(context, R.string.ui_clip_clipboard_empty, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            EditorField(
                label = stringResource(R.string.ui_share_text_clip_label).uppercase(),
                value = clipText,
                placeholder = stringResource(R.string.ui_share_text_clip_hint),
                onChange = { clipText = it },
                minHeight = 84.dp,
                enabled = !saving
            )

            EditorField(
                label = stringResource(R.string.ui_share_text_reaction_label).uppercase(),
                value = reaction,
                placeholder = stringResource(R.string.ui_share_text_reaction_hint),
                onChange = { reaction = it },
                minHeight = 48.dp,
                enabled = !saving
            )

            if (llmReady) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.ui_section_format),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink2
                    )
                    FormatChips(
                        selected = chosenFormat,
                        onSelect = { chosenFormat = it },
                        enabled = !saving
                    )
                }
            }

            if (saving && llmReady && chosenFormat.usesLlm) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Blue,
                    trackColor = Line
                )
            }

            val canSave = (clipText.isNotBlank() || reaction.isNotBlank()) && !saving
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(
                    label = stringResource(R.string.ui_action_cancel),
                    background = Surface2,
                    foreground = Ink,
                    enabled = !saving,
                    onClick = onDismiss
                )
                Spacer(Modifier.width(10.dp))
                PillButton(
                    label = if (saving) stringResource(R.string.ui_share_saving) else stringResource(R.string.ui_share_save),
                    background = if (canSave) SignalFill else Surface2,
                    foreground = if (canSave) Color.White else Ink2,
                    enabled = canSave,
                    onClick = {
                        saving = true
                        val cleanClip = clipText.trim()
                        val cleanReaction = reaction.trim()
                        val fmt = if (llmReady) chosenFormat else NoteFormat.PLAIN
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val combined = buildString {
                                    if (cleanReaction.isNotBlank()) {
                                        appendLine(cleanReaction)
                                        if (cleanClip.isNotBlank()) appendLine()
                                    }
                                    if (cleanClip.isNotBlank()) append(cleanClip)
                                }.trim()
                                val (spaceName, spaceFocus) = SpaceStore.focusFor(spaceId)
                                val structured = Transform.apply(
                                    context.filesDir,
                                    combined,
                                    fmt,
                                    AppLocale.modelLanguage(context),
                                    spaceName = spaceName,
                                    spaceFocus = spaceFocus,
                                    inputKind = Transform.InputKind.CLIP
                                )
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
                                        verbatim = combined,
                                        why = structured.why,
                                        question = structured.question,
                                        tags = structured.tags,
                                        audioPath = null,
                                        spaceId = spaceId,
                                        format = fmt.id,
                                        items = structured.items,
                                        createdAt = System.currentTimeMillis(),
                                        questionDrafts = structured.questionDrafts
                                    )
                                } else {
                                    StoredNote(
                                        id = id,
                                        title = combined.lineSequence().firstOrNull { it.isNotBlank() }?.take(72)
                                            ?: context.getString(R.string.common_untitled_note),
                                        oneLine = "",
                                        mode = "Reference",
                                        whenText = whenText,
                                        verbatim = combined,
                                        why = "",
                                        question = "",
                                        tags = emptyList(),
                                        audioPath = null,
                                        spaceId = spaceId,
                                        format = NoteFormat.PLAIN.id,
                                        createdAt = System.currentTimeMillis()
                                    )
                                }
                                NoteStore.add(note)
                            }
                            saving = false
                            Toast.makeText(context, R.string.ui_share_text_saved, Toast.LENGTH_SHORT).show()
                            onSaved()
                        }
                    }
                )
            }
        }
    }
}
