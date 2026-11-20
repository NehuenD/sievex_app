package com.sievex.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sievex.app.AppLocale
import com.sievex.app.CaptureLog
import com.sievex.app.CaptureService
import com.sievex.app.Formats
import com.sievex.app.LensArtifact
import com.sievex.app.MediaItem
import com.sievex.app.MediaStore
import com.sievex.app.LensService
import com.sievex.app.LensSpec
import com.sievex.app.LensStore
import com.sievex.app.NoteFormat
import com.sievex.app.NoteStore
import com.sievex.app.Pipeline
import com.sievex.app.PipelineStage
import com.sievex.app.PipelineStep
import com.sievex.app.R
import com.sievex.app.Space
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import com.sievex.app.asr.ModelInstaller
import com.sievex.app.llm.Lens
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class NoteItem(
    val id: Long = 0L,
    val title: String,
    val mode: String,
    val oneLine: String,
    val whenText: String,
    val verbatim: String,
    val why: String,
    val question: String,
    val tags: List<String>,
    /** The space this note is filed under, or null for "No space". UI mirror only. */
    val spaceId: String? = null,
    /**
     * When the user last rewrote this note by hand, or null if they never have. Only the editor
     * sets it, so it doubles as the "this is the user's own wording now" badge.
     */
    val editedAt: Long? = null,
    /** How the note was shaped; see [NoteFormat]. Mirrors the stored id, never re-derived. */
    val format: String = NoteFormat.NOTE.id,
    /** The bullet or step list a LIST/STEPS note carries; empty for every other format. */
    val items: List<String> = emptyList(),
    val createdAt: Long = 0L
)

private val demoNotes = listOf(
    NoteItem(
        title = "Moat is switching cost, not data",
        mode = "Insight",
        oneLine = "Data isn't a moat unless you can't get it anywhere else — and for most of these companies you can.",
        whenText = "Today 15:36",
        verbatim = "Every founder kept saying the model isn't the moat, then described their moat as proprietary data, which is the same mistake with extra steps. The real moat is whatever makes leaving cost a week of someone's time.",
        why = "Reframes fine-tuning as a data-moat bet. If the moat is switching cost, the budget belongs in integration, not training.",
        question = "What's the cheapest way to buy switching cost inside a workflow I don't own?",
        tags = listOf("product", "strategy")
    ),
    NoteItem(
        title = "A review queue, not a feed",
        mode = "Idea",
        oneLine = "Captured thoughts should arrive in a queue you clear, not a feed you scroll.",
        whenText = "Today 15:41",
        verbatim = "The reason every notes app dies is that it becomes a feed. A feed rewards scrolling. A queue rewards clearing. If the thing after capture is a queue with a visible end, I'll actually come back.",
        why = "Names the retention failure mode of the category and implies a concrete UI: finite, dismissible, with a count.",
        question = "What's the smallest queue that still feels finite?",
        tags = listOf("product", "design")
    ),
    NoteItem(
        title = "Send Sarah the Q3 pricing deck",
        mode = "Action",
        oneLine = "Blocking the Thursday review.",
        whenText = "Today 15:44",
        verbatim = "I need to email Sarah about the Q3 pricing deck before Thursday, because the review can't happen without it.",
        why = "",
        question = "",
        tags = listOf("work")
    ),
    NoteItem(
        title = "Why does everyone optimise the wrong end?",
        mode = "Question",
        oneLine = "Dictation apps compete on transcription quality; the value is after transcription.",
        whenText = "Yesterday 18:02",
        verbatim = "Why does every dictation app pour everything into word error rate when the thing I actually struggle with is what happens to the text afterwards?",
        why = "",
        question = "What would a dictation app look like if WER were already solved?",
        tags = listOf("product")
    ),
    NoteItem(
        title = "Granary — speech corpus, 25 languages",
        mode = "Reference",
        oneLine = "The training corpus behind Parakeet v3.",
        whenText = "Yesterday 18:10",
        verbatim = "Look up the Granary paper, that's the multilingual corpus the Parakeet model is trained on.",
        why = "",
        question = "",
        tags = listOf("asr")
    )
)

/**
 * Everything the top level of the UI needs, read in one shot.
 *
 * The stores are process-wide singletons whose reads take a lock and whose writes hit
 * `filesDir`. Build this off the frame thread and hand it back to compose state on main.
 */
private data class UiSnapshot(
    val notes: List<NoteItem>,
    val spaces: List<Space>,
    val media: List<MediaItem>,
    val noteCounts: Map<String, Int>,
    val insights: Map<String, LensArtifact?>,
    val currentSpaceId: String?
)

/** Blocking: only call this from [Dispatchers.IO]. */
private fun readSnapshot(): UiSnapshot {
    val stored = NoteStore.all()
    val allSpaces = SpaceStore.all()
    return UiSnapshot(
        notes = stored.map { n ->
            NoteItem(
                id = n.id,
                title = n.title,
                mode = n.mode,
                oneLine = n.oneLine,
                whenText = n.whenText,
                verbatim = n.verbatim,
                why = n.why,
                question = n.question,
                tags = n.tags,
                spaceId = n.spaceId,
                editedAt = n.editedAt,
                format = n.format,
                items = n.items,
                createdAt = n.createdAt
            )
        },
        spaces = allSpaces,
        media = MediaStore.all(),
        noteCounts = allSpaces.associate { it.id to SpaceStore.noteCount(it.id) },
        insights = allSpaces.associate { it.id to SpaceStore.latestFor(it.id, it.lensIds) },
        currentSpaceId = SpaceStore.currentSpaceId
    )
}

private fun modeColor(mode: String): Color = when (mode) {
    "Insight" -> Signal
    "Idea" -> Blue
    "Action" -> Mint
    "Reference" -> Ink2
    "Question" -> Amber
    else -> Ink2
}

// --------------------------------------------------------------------------- localised vocabulary
//
// Modes, formats and built-in lenses are stored as canonical English data (the Actions tab filters
// on "Action", the LLM grammar emits "insight", artifacts are keyed by lens id), so the display
// layer translates them instead of the data. Unknown values fall through unchanged, which is what
// keeps custom lenses and future modes readable.

@Composable
private fun modeLabel(mode: String): String = when (mode) {
    "Insight" -> stringResource(R.string.mode_insight)
    "Idea" -> stringResource(R.string.mode_idea)
    "Action" -> stringResource(R.string.mode_action)
    "Reference" -> stringResource(R.string.mode_reference)
    "Question" -> stringResource(R.string.mode_question)
    "Reflection" -> stringResource(R.string.mode_reflection)
    "Note" -> stringResource(R.string.mode_note)
    else -> mode
}

@Composable
private fun formatLabel(format: NoteFormat): String = when (format) {
    NoteFormat.NOTE -> stringResource(R.string.format_note)
    NoteFormat.LIST -> stringResource(R.string.format_list)
    NoteFormat.STEPS -> stringResource(R.string.format_steps)
    NoteFormat.PLAIN -> stringResource(R.string.format_plain)
}

@Composable
private fun formatBlurb(format: NoteFormat): String = when (format) {
    NoteFormat.NOTE -> stringResource(R.string.format_note_blurb)
    NoteFormat.LIST -> stringResource(R.string.format_list_blurb)
    NoteFormat.STEPS -> stringResource(R.string.format_steps_blurb)
    NoteFormat.PLAIN -> stringResource(R.string.format_plain_blurb)
}

/** Built-in lenses are translated by id; a custom lens keeps the words the user gave it. */
@Composable
private fun lensName(spec: LensSpec): String = when (spec.id) {
    "insight" -> stringResource(R.string.lens_insight_name)
    "brainstorm" -> stringResource(R.string.lens_brainstorm_name)
    "reading" -> stringResource(R.string.lens_reading_name)
    "actions" -> stringResource(R.string.lens_actions_name)
    "tensions" -> stringResource(R.string.lens_tensions_name)
    "timeline" -> stringResource(R.string.lens_timeline_name)
    else -> spec.name
}

@Composable
private fun lensDescription(spec: LensSpec): String = when (spec.id) {
    "insight" -> stringResource(R.string.lens_insight_description)
    "brainstorm" -> stringResource(R.string.lens_brainstorm_description)
    "reading" -> stringResource(R.string.lens_reading_description)
    "actions" -> stringResource(R.string.lens_actions_description)
    "tensions" -> stringResource(R.string.lens_tensions_description)
    "timeline" -> stringResource(R.string.lens_timeline_description)
    else -> spec.description
}

/** "Today 15:36" is stored data; only its day word is translated at display time. */
@Composable
private fun whenLabel(text: String): String =
    if (text.startsWith("Today ")) {
        stringResource(R.string.common_today) + text.removePrefix("Today")
    } else {
        text
    }

/** The nearest Activity, through the configuration wrappers [AppLocale] and Compose introduce. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// --------------------------------------------------------------------------- links in note text
//
// A dictated note can contain a typed URL (a watch-later list is the obvious case). Any http(s)
// or www address in displayed text is underlined, opens the browser on tap, and is copied when
// held. List items also get an explicit Copy button, because that is the action people reach for
// when the link is one of several.

private const val LINK_TAG = "sievex-link"

private val URL_PATTERN = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

/** Character ranges of the links in [text], with trailing sentence punctuation trimmed off. */
private fun linkRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    for (match in URL_PATTERN.findAll(text)) {
        var end = match.range.last
        while (end > match.range.first && text[end] in ".,;:!?)]}\"'") end--
        if (end > match.range.first) ranges.add(match.range.first..end)
    }
    return ranges
}

/** `www.` is not a usable scheme; a picked link from a browser is already absolute. */
private fun openableUrl(raw: String): String =
    if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw

/**
 * Text that renders its links as links. Everything else is untouched, so a note without a URL looks
 * exactly as it did before; the gesture layer only acts when the tap lands on a link.
 */
@Composable
private fun LinkifiedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val ranges = remember(text) { linkRanges(text) }
    if (ranges.isEmpty()) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val openFailed = stringResource(R.string.common_link_open_failed)

    val annotated = remember(text, ranges) {
        buildAnnotatedString {
            append(text)
            ranges.forEach { range ->
                addStyle(
                    SpanStyle(color = Signal, textDecoration = TextDecoration.Underline),
                    range.first,
                    range.last + 1
                )
                addStringAnnotation(
                    tag = LINK_TAG,
                    annotation = text.substring(range.first, range.last + 1),
                    start = range.first,
                    end = range.last + 1
                )
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    fun linkAt(position: Offset): String? {
        val result = layout ?: return null
        val offset = result.getOffsetForPosition(position)
        return annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.item
    }

    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures(
                onTap = { position ->
                    val link = linkAt(position) ?: return@detectTapGestures
                    val url = openableUrl(link)
                    runCatching { uriHandler.openUri(url) }.onFailure {
                        // No browser (or the intent was refused): copying is the useful fallback.
                        clipboard.setText(AnnotatedString(url))
                        Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                    }
                },
                onLongPress = { position ->
                    val link = linkAt(position) ?: return@detectTapGestures
                    clipboard.setText(AnnotatedString(openableUrl(link)))
                }
            )
        },
        onTextLayout = { layout = it }
    )
}

@Composable
fun SievexApp(
    initialSpaceId: String? = null,
    initialLensId: String? = null,
    onInitialSpaceConsumed: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(0) }
    var openNoteId by remember { mutableStateOf<Long?>(null) }
    var openSpaceId by remember { mutableStateOf<String?>(null) }
    /** The lens a report notification asked to open, consumed by the space screen. */
    var openSpaceLensId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // First run: say what the app is before showing the recorder. The flag is written only when
    // the intro finishes or is skipped, so a process death mid-onboarding shows it again.
    var onboarded by remember { mutableStateOf(OnboardingPrefs.done(context)) }
    if (!onboarded) {
        OnboardingScreen(onDone = {
            OnboardingPrefs.markDone(context)
            onboarded = true
        })
        return
    }

    // The microphone is requested when capture is armed (below); notifications are requested here,
    // once the user has seen what the app does. A refusal only costs the "report ready" ping: the
    // report itself is still generated and saved.
    val notifyPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var armed by remember { mutableStateOf(CaptureService.armed) }
    var recording by remember { mutableStateOf(CaptureService.isRecording) }
    var notes by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var noteCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var insights by remember { mutableStateOf<Map<String, LensArtifact?>>(emptyMap()) }
    var currentSpaceId by remember { mutableStateOf<String?>(null) }
    var openMediaId by remember { mutableStateOf<String?>(null) }
    var notifAccess by remember { mutableStateOf(false) }
    var pickingCurrentSpace by remember { mutableStateOf(false) }
    var namingNewSpace by remember { mutableStateOf(false) }
    var deletingSpace by remember { mutableStateOf<Space?>(null) }
    /** The note whose editor is open, if any. The editor sits on top of the note detail. */
    var editingNoteId by remember { mutableStateOf<Long?>(null) }
    /** The hand-written note composer is open. It owns the whole screen while it is. */
    var composingNote by remember { mutableStateOf(false) }
    /** Advances once per poll tick so screens reading non-observable state can recompose. */
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    /**
     * What to do once the microphone permission lands. The launcher's callback cannot tell which
     * button asked, so the action is remembered here: without it the first tap on the record
     * button would arm the podcast trigger instead of starting a burst.
     */
    var pendingAudioAction by remember { mutableStateOf<String?>(null) }
    val audioPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAudioAction
        pendingAudioAction = null
        if (granted && action != null) {
            CaptureService.send(context, action, "after grant")
        } else if (!granted) {
            CaptureLog.add("microphone permission denied")
        }
    }

    /** Mirrors a store snapshot into compose state. Main thread only. */
    fun applySnapshot(snapshot: UiSnapshot) {
        notes = snapshot.notes
        spaces = snapshot.spaces
        media = snapshot.media
        noteCounts = snapshot.noteCounts
        insights = snapshot.insights
        currentSpaceId = snapshot.currentSpaceId
    }

    // Parsing notes.json (and the spaces/jobs stores that load with it) is file work: keep it off the frame thread.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { NoteStore.init(context.filesDir) }
        ModelInstaller.refresh(context)
        TransformModel.refresh(context)
        // The picker is persisted for podcast-triggered bursts; the UI is the first screen that
        // can restore it before the user sees a stale Note selection.
        Formats.restore(context)
    }

    // The poll must not run while the activity is stopped; otherwise it burns battery and
    // keeps taking store locks behind a screen nobody is looking at.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_STOP -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            applySnapshot(withContext(Dispatchers.IO) { readSnapshot() })
            armed = CaptureService.armed
            recording = CaptureService.isRecording
            notifAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
            // A held burst is a failure the app can heal by itself: once both models are
            // present again, drop the hold so the drained job's result is not masked by a
            // stale banner. Non-blocked failures stay on screen until the user dismisses them.
            if (ModelInstaller.installed && TransformModel.installed &&
                Pipeline.stage == PipelineStage.FAILED && Pipeline.blocked
            ) {
                Pipeline.reset()
            }
            // The event log is only on screen in Settings; refresh it while it is visible.
            if (tab == 4) tick++
            delay(400)
        }
    }

    // A completion notification opens the space (and the lens) it built. The stores load
    // asynchronously, so wait for the space to appear before deciding it is gone; a deleted space
    // just drops the request.
    var pendingSpaceId by remember { mutableStateOf(initialSpaceId) }
    var pendingLensId by remember { mutableStateOf(initialLensId) }
    LaunchedEffect(initialSpaceId, initialLensId) {
        if (initialSpaceId != null) {
            pendingSpaceId = initialSpaceId
            pendingLensId = initialLensId
        }
    }
    LaunchedEffect(spaces, pendingSpaceId) {
        val wanted = pendingSpaceId ?: return@LaunchedEffect
        if (spaces.isEmpty()) return@LaunchedEffect
        if (spaces.any { it.id == wanted }) {
            // A notification tap must land on the space even when a note or the editor is open:
            // those screens render before the tab scaffold and would otherwise swallow the jump.
            editingNoteId = null
            openNoteId = null
            openSpaceLensId = pendingLensId
            openSpaceId = wanted
            tab = 1
        }
        pendingSpaceId = null
        pendingLensId = null
        onInitialSpaceConsumed()
    }

    // One back stack, three levels: the editor sits on top of a note, which sits on top of a space.
    BackHandler(enabled = editingNoteId != null || openNoteId != null || openSpaceId != null) {        when {
            editingNoteId != null -> editingNoteId = null
            openNoteId != null -> openNoteId = null
            else -> openSpaceId = null
        }
    }

    val editingNote = notes.firstOrNull { it.id == editingNoteId }
    if (editingNote != null) {
        NoteEditorScreen(
            note = editingNote,
            onCancel = { editingNoteId = null },
            onSave = { edited ->
                editingNoteId = null
                // Only the editor writes note content, and it writes it through NoteStore.update:
                // the write is file work, so it never runs on the frame thread.
                scope.launch {
                    val snapshot = withContext(Dispatchers.IO) {
                        val stored = NoteStore.byId(edited.id)
                        if (stored != null) {
                            NoteStore.update(
                                edited.copy(
                                    audioPath = stored.audioPath,
                                    spaceId = stored.spaceId,
                                    whenText = stored.whenText,
                                    editedAt = stored.editedAt
                                )
                            )
                        }
                        readSnapshot()
                    }
                    applySnapshot(snapshot)
                }
            }
        )
        return
    }

    val openNote = notes.firstOrNull { it.id == openNoteId }
    if (composingNote) {
        // Before note detail and the tab scaffold, so the composer is the only thing on screen;
        // the editor branch above still wins if a note is already open for editing.
        NewNoteScreen(
            spaces = spaces,
            initialSpaceId = currentSpaceId,
            onCancel = { composingNote = false },
            onSaved = {
                composingNote = false
                // The note exists on disk now; land on Notes and read it back immediately rather
                // than waiting for the next poll tick.
                tab = 2
                scope.launch { applySnapshot(withContext(Dispatchers.IO) { readSnapshot() }) }
            }
        )
        return
    }
    val openMedia = media.firstOrNull { it.id == openMediaId }
    if (openMedia != null) {
        MediaViewerDialog(
            media = openMedia,
            spaceNotes = notes.filter { it.spaceId == openMedia.spaceId },
            onDismiss = { openMediaId = null },
            onMediaChanged = {
                scope.launch { applySnapshot(withContext(Dispatchers.IO) { readSnapshot() }) }
            }
        )
    }

    if (openNote != null) {
        NoteDetailScreen(
            note = openNote,
            spaces = spaces,
            allMedia = media,
            onOpenMedia = { openMediaId = it.id },
            onBack = { openNoteId = null },
            onEdit = { editingNoteId = openNote.id }
        )
        return
    }

    val openSpace = spaces.firstOrNull { it.id == openSpaceId }
    if (openSpace != null) {
        SpaceScreen(
            space = openSpace,
            allNotes = notes,
            allMedia = media,
            initialLensId = openSpaceLensId,
            onBack = {
                openSpaceId = null
                openSpaceLensId = null
            },
            onOpenNote = { openNoteId = it.id },
            onOpenMedia = { openMediaId = it.id },
            onMediaChanged = {
                scope.launch { applySnapshot(withContext(Dispatchers.IO) { readSnapshot() }) }
            }
        )
        return
    }

    Scaffold(
        containerColor = Canvas,
        bottomBar = {
            NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
                NavItem(0, stringResource(R.string.ui_tab_record), tab, { tab = it }) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (tab == 0) Signal else Ink2)
                    )
                }
                NavItem(1, stringResource(R.string.ui_tab_spaces), tab, { tab = it }) {
                    Icon(Icons.Filled.List, contentDescription = stringResource(R.string.ui_tab_spaces), modifier = Modifier.size(22.dp))
                }
                NavItem(2, stringResource(R.string.ui_tab_notes), tab, { tab = it }) {
                    Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.ui_tab_notes), modifier = Modifier.size(22.dp))
                }
                NavItem(3, stringResource(R.string.ui_tab_actions), tab, { tab = it }) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.ui_tab_actions), modifier = Modifier.size(22.dp))
                }
                NavItem(4, stringResource(R.string.ui_tab_settings), tab, { tab = it }) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.ui_tab_settings), modifier = Modifier.size(22.dp))
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> RecordScreen(
                    armed = armed,
                    recording = recording,
                    // Plain never calls the transform model, so a missing LLM must not block a
                    // quick dictated note.
                    modelsReady = ModelInstaller.installed &&
                        (TransformModel.installed || Formats.next == NoteFormat.PLAIN),
                    onToggle = {
                        val busy = Pipeline.stage == PipelineStage.TRANSCRIBING ||
                            Pipeline.stage == PipelineStage.TRANSFORMING
                        if (busy) {
                            // previous burst still processing
                        } else if (recording) {
                            CaptureService.trigger(false, "record screen stop")
                        } else if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            Pipeline.reset()
                            CaptureService.send(context, CaptureService.ACTION_START_BURST, "record screen")
                        } else {
                            Pipeline.reset()
                            pendingAudioAction = CaptureService.ACTION_START_BURST
                            audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onArm = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            CaptureService.send(context, CaptureService.ACTION_ARM)
                        } else {
                            pendingAudioAction = CaptureService.ACTION_ARM
                            audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDisarm = { CaptureService.send(context, CaptureService.ACTION_DISARM) },
                    onOpenNotes = {
                        Pipeline.reset()
                        tab = 2
                    },
                    spaceName = spaces.firstOrNull { it.id == currentSpaceId }?.name,
                    onPickSpace = { pickingCurrentSpace = true }
                )
                1 -> SpacesScreen(
                    spaces = spaces,
                    noteCounts = noteCounts,
                    insights = insights,
                    onNewSpace = { namingNewSpace = true },
                    onOpen = {
                        openSpaceLensId = null
                        openSpaceId = it.id
                    },
                    onDeleteRequest = { deletingSpace = it }
                )
                2 -> NotesScreen(
                    notes = notes,
                    armed = armed,
                    recording = recording,
                    notifAccess = notifAccess,
                    onArm = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            CaptureService.send(context, CaptureService.ACTION_ARM)
                        } else {
                            pendingAudioAction = CaptureService.ACTION_ARM
                            audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDisarm = { CaptureService.send(context, CaptureService.ACTION_DISARM) },
                    onRecord = { CaptureService.trigger(true, "record now") },
                    onStop = { CaptureService.trigger(false, "stop") },
                    onNotifAccess = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onNewNote = { composingNote = true }
                ) { openNoteId = it.id }
                3 -> ActionsScreen(notes.filter { it.mode == "Action" })
                4 -> SettingsScreen(armed, recording, tick)
            }
        }
    }

    if (pickingCurrentSpace) {
        SpacePickerDialog(
            title = stringResource(R.string.ui_space_picker_record_title),
            spaces = spaces,
            selectedId = currentSpaceId,
            onSelect = { spaceId ->
                pickingCurrentSpace = false
                // Assigning currentSpaceId persists spaces.json: off the frame thread.
                scope.launch {
                    withContext(Dispatchers.IO) { SpaceStore.currentSpaceId = spaceId }
                    currentSpaceId = SpaceStore.currentSpaceId
                }
            },
            onDismiss = { pickingCurrentSpace = false }
        )
    }

    if (namingNewSpace) {
        SpaceNameDialog(
            title = stringResource(R.string.ui_space_new),
            confirmLabel = stringResource(R.string.ui_space_create),
            onDismiss = { namingNewSpace = false },
            onConfirm = { name ->
                namingNewSpace = false
                // Creating a space writes spaces.json: off the frame thread, then refresh state.
                scope.launch {
                    val snapshot = withContext(Dispatchers.IO) {
                        SpaceStore.create(name)
                        readSnapshot()
                    }
                    applySnapshot(snapshot)
                }
            }
        )
    }

    val spaceToDelete = deletingSpace
    if (spaceToDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.ui_space_delete_title, spaceToDelete.name),
            body = stringResource(R.string.ui_space_delete_body),
            confirmLabel = stringResource(R.string.ui_space_delete_confirm),
            onConfirm = {
                deletingSpace = null
                // Delete rewrites spaces.json and detaches notes: off the frame thread.
                scope.launch {
                    val snapshot = withContext(Dispatchers.IO) {
                        SpaceStore.delete(spaceToDelete.id)
                        readSnapshot()
                    }
                    applySnapshot(snapshot)
                    if (openSpaceId == spaceToDelete.id) openSpaceId = null
                }
            },
            onDismiss = { deletingSpace = null }
        )
    }
}

@Composable
private fun RowScope.NavItem(
    index: Int,
    label: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    icon: @Composable () -> Unit
) {
    NavigationBarItem(
        selected = selected == index,
        onClick = { onSelect(index) },
        icon = icon,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Signal,
            selectedTextColor = Signal,
            indicatorColor = SignalSoft,
            unselectedIconColor = Ink2,
            unselectedTextColor = Ink2
        )
    )
}

@Composable
private fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Signal, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ui_brand_name), style = MaterialTheme.typography.labelSmall, color = Ink2)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink2)
        }
    }
}

@Composable
internal fun PillButton(
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        color = background,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun CaptureCard(
    armed: Boolean,
    recording: Boolean,
    notifAccess: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onNotifAccess: () -> Unit
) {
    val live = armed || recording
    Surface(
        color = if (live) SignalSoft else Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(1.dp, if (live) Signal else Line, RoundedCornerShape(24.dp))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (live) Signal else Ink2, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        recording -> stringResource(R.string.ui_capture_status_recording)
                        armed -> stringResource(R.string.ui_capture_status_armed)
                        else -> stringResource(R.string.ui_capture_status_ready)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) Signal else Ink2
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    recording -> stringResource(R.string.ui_capture_body_recording)
                    armed -> stringResource(R.string.ui_capture_body_armed)
                    else -> stringResource(R.string.ui_capture_body_ready)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Ink
            )

            if (!notifAccess) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Surface2,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { onNotifAccess() }
                ) {
                    Text(
                        stringResource(R.string.ui_capture_enable_notification_access),
                        style = MaterialTheme.typography.labelMedium,
                        color = Blue,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    recording -> PillButton(stringResource(R.string.ui_action_stop), Signal, Color.White, onStop)
                    armed -> {
                        PillButton(stringResource(R.string.ui_capture_record_now), Surface2, Ink, onRecord)
                        PillButton(stringResource(R.string.ui_capture_disarm), Surface2, Ink2, onDisarm)
                    }
                    else -> PillButton(stringResource(R.string.ui_capture_arm), Signal, Color.White, onArm)
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: String) {
    val c = modeColor(mode)
    Box(
        Modifier
            .border(1.dp, c.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(modeLabel(mode).uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
    }
}

/**
 * Marks a note the user has rewritten by hand. Deliberately quiet: it is a fact about the note's
 * provenance, not a status, so it borrows the same hairline pill as the other small chips.
 */
@Composable
private fun EditedBadge() {
    Box(
        Modifier
            .border(1.dp, Line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(stringResource(R.string.ui_note_edited), style = MaterialTheme.typography.labelSmall, color = Ink2)
    }
}

@Composable
private fun NoteCard(note: NoteItem, onClick: () -> Unit) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeChip(note.mode)
                Spacer(Modifier.weight(1f))
                if (note.editedAt != null) {
                    EditedBadge()
                    Spacer(Modifier.width(8.dp))
                }
                Text(whenLabel(note.whenText), style = MaterialTheme.typography.labelSmall, color = Ink2)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Plain notes and list notes with no summary have nothing to put here; an empty Text
            // would still take a line of height.
            if (note.oneLine.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    note.oneLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ModelSetupCard() {
    val context = LocalContext.current
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(1.dp, Blue.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.ui_speech_model), style = MaterialTheme.typography.labelSmall, color = Blue)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_speech_model_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
            if (ModelInstaller.busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { ModelInstaller.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Signal,
                    trackColor = Line
                )
                Spacer(Modifier.height(6.dp))
                Text(ModelInstaller.status, style = MaterialTheme.typography.bodySmall, color = Ink2)
            } else {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Signal,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { ModelInstaller.download(context) }
                ) {
                    Text(
                        stringResource(R.string.ui_speech_model_download),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
                if (ModelInstaller.failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(ModelInstaller.status, style = MaterialTheme.typography.bodySmall, color = Red)
                }
            }
        }
    }
}

@Composable
private fun TransformSetupCard() {
    val context = LocalContext.current
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(1.dp, Mint.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.ui_transform_model), style = MaterialTheme.typography.labelSmall, color = Mint)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_transform_model_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
            if (TransformModel.busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { TransformModel.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Mint,
                    trackColor = Line
                )
                Spacer(Modifier.height(6.dp))
                Text(TransformModel.status, style = MaterialTheme.typography.bodySmall, color = Ink2)
            } else {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Mint,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { TransformModel.download(context) }
                ) {
                    Text(
                        stringResource(R.string.ui_transform_model_download),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF00382F),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
                if (TransformModel.failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(TransformModel.status, style = MaterialTheme.typography.bodySmall, color = Red)
                }
            }
        }
    }
}

@Composable
private fun RecordScreen(
    armed: Boolean,
    recording: Boolean,
    modelsReady: Boolean,
    onToggle: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onOpenNotes: () -> Unit,
    spaceName: String?,
    onPickSpace: () -> Unit
) {
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(recording) {
        if (!recording) {
            elapsed = 0
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (true) {
            elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(200)
        }
    }

    LaunchedEffect(Pipeline.stage) {
        if (Pipeline.stage == PipelineStage.DONE) {
            delay(9000)
            Pipeline.reset()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        ScreenHeader(
            stringResource(R.string.ui_tab_record),
            if (modelsReady) stringResource(R.string.ui_record_subtitle_ready) else stringResource(R.string.ui_record_subtitle_setup)
        )

        if (!modelsReady) {
            // Scrollable: the two setup cards plus the picker can exceed a short screen, and a
            // clipped picker is the one control that would make Plain unreachable.
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!ModelInstaller.installed) ModelSetupCard()
                if (!TransformModel.installed) {
                    Spacer(Modifier.height(12.dp))
                    TransformSetupCard()
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.ui_record_setup_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(16.dp))
                FormatPicker()
                Spacer(Modifier.height(24.dp))
            }
            return@Column
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            RecordButton(recording, onToggle)
        }

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (Pipeline.stage) {
                // Action labels, not model names: the phone is doing something, and what it is
                // doing is the only thing worth putting on this card.
                PipelineStage.TRANSCRIBING -> StatusCard(
                    title = stringResource(R.string.ui_record_stage_transcribing),
                    body = "",
                    accent = Blue
                )
                PipelineStage.TRANSFORMING -> StatusCard(
                    title = pipelineStepLabel(Pipeline.step).uppercase(Locale.US),
                    body = "",
                    accent = Mint
                )
                PipelineStage.DONE -> DoneCard(Pipeline.lastTitle, onOpenNotes)
                PipelineStage.FAILED -> StatusCard(
                    title = stringResource(R.string.ui_record_stage_failed),
                    body = Pipeline.detail,
                    accent = Red
                )
                PipelineStage.IDLE -> {
                    Text(
                        if (recording) formatDuration(elapsed) else stringResource(R.string.ui_record_tap_to_record),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (recording) Signal else Ink
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (recording) stringResource(R.string.ui_record_recording) else stringResource(R.string.ui_record_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = if (armed) SignalSoft else Surface2,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable { if (armed) onDisarm() else onArm() }
                    ) {
                        Text(
                            if (armed) stringResource(R.string.ui_record_podcast_trigger_on) else stringResource(R.string.ui_record_podcast_trigger_off),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (armed) Signal else Ink2,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            // The format applies to the next capture, so it stays reachable while a note is being
            // transcribed or shaped, and while a failure waits to be dismissed.
            Spacer(Modifier.height(14.dp))
            FormatPicker()
            Spacer(Modifier.height(16.dp))
            SpaceChip(spaceName, onPickSpace)
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The step label the status card shows. [Pipeline] carries a key, not a sentence, so the same
 * service state reads in whatever language the UI is in.
 */
@Composable
private fun pipelineStepLabel(step: PipelineStep?): String = when (step) {
    PipelineStep.DICTATION -> stringResource(R.string.step_dictation)
    PipelineStep.TITLE -> stringResource(R.string.step_title)
    PipelineStep.SUMMARY -> stringResource(R.string.step_summary)
    PipelineStep.ITEMS -> stringResource(R.string.step_items)
    PipelineStep.STEPS -> stringResource(R.string.step_steps)
    PipelineStep.INSIGHTS -> stringResource(R.string.step_insights)
    PipelineStep.TAGS -> stringResource(R.string.step_tags)
    PipelineStep.SAVING -> stringResource(R.string.step_saving)
    PipelineStep.INTENT, null -> stringResource(R.string.step_intent)
}

@Composable
private fun StatusCard(title: String, body: String, accent: Color) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, color = accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Ink2)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                trackColor = Line
            )
        }
    }
}

@Composable
private fun DoneCard(title: String, onOpenNotes: () -> Unit) {
    Surface(
        color = SignalSoft,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(1.dp, Signal, RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_record_note_ready), style = MaterialTheme.typography.labelSmall, color = Signal)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = Signal,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.clickable { onOpenNotes() }
            ) {
                Text(
                    stringResource(R.string.ui_record_view_in_notes),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onToggle: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp)) {
        if (recording) {
            val transition = rememberInfiniteTransition(label = "waves")
            val t by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "wave"
            )
            Canvas(Modifier.fillMaxSize()) {
                val base = 96.dp.toPx()
                for (i in 0 until 3) {
                    val p = (t + i / 3f) % 1f
                    drawCircle(
                        color = Signal.copy(alpha = (1f - p) * 0.30f),
                        radius = base + p * 56.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(if (recording) Signal else Surface2)
                .border(2.dp, if (recording) Signal else Line, CircleShape)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            if (recording) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                )
            } else {
                Box(
                    Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(Signal)
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

@Composable
private fun NotesScreen(
    notes: List<NoteItem>,
    armed: Boolean,
    recording: Boolean,
    notifAccess: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onNotifAccess: () -> Unit,
    onNewNote: () -> Unit,
    onOpen: (NoteItem) -> Unit
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                ScreenHeader(
                    stringResource(R.string.ui_tab_notes),
                    if (notes.size == 1) stringResource(R.string.ui_notes_count_one) else stringResource(R.string.ui_notes_count_many, notes.size)
                )
                // Part of the header copy, not a bar above the list: writing a note is the same
                // kind of act as capturing one, so it sits with the title it belongs to.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    PillButton(stringResource(R.string.ui_notes_new_note), Signal, Color.White, onNewNote)
                }
                Spacer(Modifier.height(12.dp))
                CaptureCard(
                    armed = armed,
                    recording = recording,
                    notifAccess = notifAccess,
                    onArm = onArm,
                    onDisarm = onDisarm,
                    onRecord = onRecord,
                    onStop = onStop,
                    onNotifAccess = onNotifAccess
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.ui_notes_recent),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (notes.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ui_notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        items(notes) { note ->
            Box(Modifier.padding(horizontal = 20.dp)) {
                NoteCard(note, onClick = { onOpen(note) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The record screen's current-space control. Opens the same picker a note uses. */
@Composable
private fun SpaceChip(spaceName: String?, onClick: () -> Unit) {
    val active = spaceName != null
    Surface(
        color = if (active) SignalSoft else Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (active) Signal else Ink2, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (active) stringResource(R.string.ui_space_chip_active, spaceName.orEmpty()) else stringResource(R.string.ui_space_chip_none),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Signal else Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SubTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) SignalSoft else Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Signal else Ink2,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
        )
    }
}

/**
 * One chip per [NoteFormat], scrolling sideways so four of them fit a narrow screen.
 *
 * [horizontalPadding] is applied inside the scroll: the chips line up with the 20dp gutter at
 * rest, then scroll under it instead of being clipped at the edge.
 */
@Composable
private fun FormatChips(
    selected: NoteFormat,
    onSelect: (NoteFormat) -> Unit,
    horizontalPadding: Dp = 0.dp
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NoteFormat.entries.forEach { format ->
            SubTab(formatLabel(format), format == selected) { onSelect(format) }
        }
    }
}

/**
 * The shape the next capture is filed in: one label and one chip row, deliberately compact
 * because it shares the bottom of the screen with a 320 dp record button. The choice is
 * persisted by [Formats] so a podcast-triggered burst after a process death still uses it.
 * The longer blurbs live in the composer and the reprocess dialog, where there is room.
 */
@Composable
private fun FormatPicker() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ui_section_format),
            style = MaterialTheme.typography.labelSmall,
            color = Ink2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(4.dp))
        FormatChips(
            Formats.next,
            { format -> Formats.select(context, format) },
            horizontalPadding = 20.dp
        )
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
private fun SpacesScreen(
    spaces: List<Space>,
    noteCounts: Map<String, Int>,
    insights: Map<String, LensArtifact?>,
    onNewSpace: () -> Unit,
    onOpen: (Space) -> Unit,
    onDeleteRequest: (Space) -> Unit
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                ScreenHeader(stringResource(R.string.ui_tab_spaces), stringResource(R.string.ui_spaces_subtitle))
                Row(Modifier.padding(horizontal = 20.dp)) {
                    PillButton(stringResource(R.string.ui_space_new), Signal, Color.White, onNewSpace)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ui_spaces_hold_to_delete),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        if (spaces.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ui_spaces_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        items(spaces) { space ->
            Box(Modifier.padding(horizontal = 20.dp)) {
                SpaceRow(
                    space = space,
                    noteCount = noteCounts[space.id] ?: 0,
                    insight = insights[space.id],
                    onClick = { onOpen(space) },
                    onLongClick = { onDeleteRequest(space) }
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceRow(
    space: Space,
    noteCount: Int,
    insight: LensArtifact?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = stringResource(R.string.ui_space_open_action),
                onLongClickLabel = stringResource(R.string.ui_space_delete_confirm),
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (insight == null) Line else Signal, CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    space.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (noteCount == 1) stringResource(R.string.ui_notes_count_one) else stringResource(R.string.ui_notes_count_many, noteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (insight == null) stringResource(R.string.ui_space_no_report) else stringResource(R.string.ui_space_report_age, relativeAge(insight.generatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = Ink2
            )
        }
    }
}

@Composable
private fun SpaceScreen(
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
    var sub by remember(space.id) { mutableStateOf(0) }
    var editingSpace by remember(space.id) { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri -> MediaStore.importFromUri(context, uri, space.id) }
                }
                onMediaChanged()
            }
        }
    }

    // The lenses enabled on this space, resolved from `space.lensIds`. `lensEpoch` forces a
    // re-read after this screen writes a lens selection, so chips do not wait for the poll.
    var lenses by remember(space.id) { mutableStateOf<List<LensSpec>>(emptyList()) }
    var lensEpoch by remember(space.id) { mutableStateOf(0) }
    /** False until the first resolve lands: "no lenses" must never be shown for a space that has them. */
    var lensesLoaded by remember(space.id) { mutableStateOf(false) }
    // A report notification names the lens too, so the screen opens on the report it announced
    // instead of whichever chip happens to be first.
    var selectedLensId by remember(space.id) { mutableStateOf(initialLensId) }
    var artifact by remember(space.id) { mutableStateOf<LensArtifact?>(null) }
    var runningLensId by remember(space.id) { mutableStateOf<String?>(null) }
    var failedLensId by remember(space.id) { mutableStateOf<String?>(null) }
    var addingLens by remember(space.id) { mutableStateOf(false) }
    var creatingLens by remember(space.id) { mutableStateOf(false) }
    var removingLens by remember(space.id) { mutableStateOf<LensSpec?>(null) }

    val spaceNotes = allNotes.filter { it.spaceId == space.id }
    val spaceMedia = allMedia.filter { it.spaceId == space.id }
    val feedEntries = remember(spaceNotes, spaceMedia) {
        (spaceNotes.map { FeedEntry.NoteEntry(it) } + spaceMedia.map { FeedEntry.MediaEntry(it) })
            .sortedByDescending { it.timestamp }
    }
    // A selection that no longer resolves (the lens was removed) falls back to the first chip
    // instead of leaving the tab blank with a dead chip highlighted.
    val selectedId = selectedLensId?.takeIf { id -> lenses.any { it.id == id } }
        ?: lenses.firstOrNull()?.id
    val selectedLens = lenses.firstOrNull { it.id == selectedId }
    val shown = artifact
    val stale = shown != null && artifactIsStale(shown, spaceNotes)

    // LensStore.resolve takes a store lock that a save can hold while it writes: never on the
    // frame thread.
    LaunchedEffect(space.id, space.lensIds, lensEpoch) {
        lenses = withContext(Dispatchers.IO) { LensStore.resolve(space.lensIds) }
        lensesLoaded = true
    }

    // Every (space, lens) pair owns its own artifact, so the poll follows only the selected lens.
    // The artifact is cleared first: a switch must never show the previous lens's report under
    // the new lens's heading, not even for the frame before the read lands.
    LaunchedEffect(space.id, selectedId) {
        artifact = null
        // Switching chips must not leave the previous lens's id gating this screen: the state
        // read below is authoritative for the lens actually selected.
        runningLensId = null
        val wanted = selectedId ?: return@LaunchedEffect
        while (true) {
            val latest = withContext(Dispatchers.IO) { SpaceStore.latest(space.id, wanted) }
            if (latest != artifact) artifact = latest
            // A run started from this screen, or one already going when the screen opened. The
            // service owns the work; this only mirrors its state onto the button and the notice.
            when (LensService.state(space.id, wanted)) {
                LensService.RunState.RUNNING -> runningLensId = wanted
                LensService.RunState.DONE -> {
                    if (runningLensId == wanted) runningLensId = null
                    failedLensId = null
                }
                LensService.RunState.FAILED -> {
                    if (runningLensId == wanted) runningLensId = null
                    // Only worth saying when there is no older report to read instead.
                    if (artifact == null) failedLensId = wanted
                }
                // The request has not been claimed by the service yet; keep the button as it is.
                LensService.RunState.IDLE -> Unit
            }
            delay(600)
        }
    }

    // The lens on screen when this lambda was built, not when the run returns.
    // (The service writes the artifact by lens kind, so a stale switch cannot overwrite another.)

    // The report can take a minute on a large space, so it runs in a foreground service: leaving
    // this screen, locking the phone, or closing the app no longer cancels it, and the completion
    // notification is the signal that it is done.
    val generate: (LensSpec) -> Unit = { spec ->
        if (runningLensId == null && spaceNotes.isNotEmpty()) {
            runningLensId = spec.id
            failedLensId = null
            LensService.start(context, space.id, spec.id)
        }
    }

    /**
     * Every space write in this screen reads the space fresh and writes it straight back, so two
     * taps in quick succession cannot each start from the same stale copy and drop one another's
     * lens. The mutex makes that read-then-write one step; the stores keep their own locks.
     */
    val spaceWrites = remember { Mutex() }

    /**
     * Adds or drops one lens id on this space. Notes and artifacts are untouched, so removing a
     * lens only hides it here and every report it made stays on disk.
     */
    val setLensEnabled: (String, Boolean) -> Unit = { lensId, enabled ->
        scope.launch {
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(
                    lensIds = if (enabled) current.lensIds + lensId else current.lensIds - lensId
                )
            }
            lensEpoch++
        }
    }

    /** Saves a user-authored lens, then enables it here: the two writes share one IO hop. */
    val createCustomLens: (LensDraft) -> Unit = { draft ->
        scope.launch {
            val id = "cx_" + UUID.randomUUID().toString().replace("-", "").take(12)
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
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(lensIds = current.lensIds + id)
            }
            selectedLensId = id
            lensEpoch++
        }
    }

    /** Deletes a custom lens, its reports, and its id off this space. */
    val deleteCustomLens: (String) -> Unit = { lensId ->
        scope.launch {
            withContext(Dispatchers.IO) {
                LensStore.deleteCustom(lensId)
                // Its reports would otherwise linger as orphans no space can ever select.
                SpaceStore.deleteArtifactsOfKind(lensId)
            }
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(lensIds = current.lensIds - lensId)
            }
            if (selectedLensId == lensId) selectedLensId = null
            lensEpoch++
        }
    }

    /** Name and description are the user's words, so they go through SpaceStore.update. */
    val saveSpaceEdits: (String, String) -> Unit = { name, description ->
        scope.launch {
            spaceWrites.updateSpace(space.id) { current ->
                current.copy(name = name, description = description)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 20.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ui_action_back),
                tint = Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onBack() }
                    .padding(8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_section_space), style = MaterialTheme.typography.labelSmall, color = Ink2)
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.ui_action_edit), Surface2, Ink, { editingSpace = true })
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
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
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
            if (space.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    space.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubTab(stringResource(R.string.ui_space_context_subtab), sub == 0) { sub = 0 }
            SubTab(stringResource(R.string.ui_space_insights_subtab), sub == 1) { sub = 1 }
            Spacer(Modifier.weight(1f))
            if (sub == 0) {
                PillButton(
                    label = stringResource(R.string.ui_space_add_image),
                    background = Surface2,
                    foreground = Ink,
                    onClick = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (sub == 0) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (feedEntries.isEmpty()) {
                    item {
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
                    }
                ) { entry ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        when (entry) {
                            is FeedEntry.NoteEntry -> NoteCard(entry.note, onClick = { onOpenNote(entry.note) })
                            is FeedEntry.MediaEntry -> MediaCard(entry.media, onClick = { onOpenMedia(entry.media) })
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    // Edge to edge, so the chips scroll under the gutters rather than around them.
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(lenses, key = { it.id }) { spec ->
                            LensChip(
                                spec = spec,
                                selected = spec.id == selectedId,
                                onClick = { selectedLensId = spec.id },
                                onLongClick = { removingLens = spec }
                            )
                        }
                        item { AddLensChip { addingLens = true } }
                    }
                }
                if (lenses.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.ui_space_hold_chip_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
                if (lensesLoaded) {
                    item {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            LensArtifactCard(
                                spec = selectedLens,
                                artifact = shown,
                                stale = stale,
                                onAddLens = { addingLens = true }
                            )
                        }
                    }
                    val lens = selectedLens
                    if (lens != null) {
                        item {
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                val busy = runningLensId != null
                                PillButton(
                                    label = if (shown == null) stringResource(R.string.ui_lens_generate) else stringResource(R.string.ui_lens_regenerate),
                                    background = if (busy || spaceNotes.isEmpty()) Surface2 else Signal,
                                    foreground = if (busy || spaceNotes.isEmpty()) Ink2 else Color.White,
                                    onClick = { generate(lens) },
                                    enabled = !busy && spaceNotes.isNotEmpty()
                                )
                                if (runningLensId == lens.id) {
                                    Spacer(Modifier.height(12.dp))
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
                                if (failedLensId == lens.id) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        stringResource(R.string.ui_lens_failed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Red
                                    )
                                }
                                if (spaceNotes.isEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        stringResource(R.string.ui_lens_needs_notes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink2
                                    )
                                } else if (stale) {
                                    Spacer(Modifier.height(10.dp))
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
        }
    }

    if (editingSpace) {
        SpaceEditorDialog(
            name = space.name,
            description = space.description,
            onSave = { name, description ->
                editingSpace = false
                saveSpaceEdits(name, description)
            },
            onDismiss = { editingSpace = false }
        )
    }

    if (addingLens) {
        LensPickerDialog(
            availableBuiltIns = LensStore.builtIns()
                .filter { spec -> lenses.none { it.id == spec.id } },
            availableCustom = LensStore.custom()
                .filter { spec -> lenses.none { it.id == spec.id } },
            onPick = { spec ->
                addingLens = false
                selectedLensId = spec.id
                setLensEnabled(spec.id, true)
            },
            onNewCustom = {
                addingLens = false
                creatingLens = true
            },
            onDismiss = { addingLens = false }
        )
    }

    if (creatingLens) {
        CustomLensDialog(
            onCreate = { draft ->
                creatingLens = false
                createCustomLens(draft)
            },
            onDismiss = { creatingLens = false }
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
            onDelete = if (lensToRemove.builtIn) {
                null
            } else {
                {
                    removingLens = null
                    deleteCustomLens(lensToRemove.id)
                }
            },
            onDismiss = { removingLens = null }
        )
    }
}

/** One chip per enabled lens: tap to read its report, hold to take it off this space. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LensChip(
    spec: LensSpec,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val name = lensName(spec)
    Surface(
        color = if (selected) SignalSoft else Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.ui_lens_show_action, name),
            onLongClickLabel = stringResource(R.string.ui_lens_remove_action, name),
            onLongClick = onLongClick,
            onClick = onClick
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Signal else Ink2
            )
            if (!spec.builtIn) {
                // Custom lenses look different because the hold action offers a different choice.
                Spacer(Modifier.width(7.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (selected) Signal else Blue, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun AddLensChip(onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, Line, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(stringResource(R.string.ui_lens_add_chip), style = MaterialTheme.typography.labelMedium, color = Ink2)
    }
}

/**
 * The selected lens's latest report, or the empty state for the whole tab.
 *
 * A lens is disposable: the card only ever reads the newest artifact for this (space, lens) pair,
 * so a second lens on the same space can never show the first one's report.
 */
@Composable
private fun LensArtifactCard(
    spec: LensSpec?,
    artifact: LensArtifact?,
    stale: Boolean,
    onAddLens: () -> Unit
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            val lens = spec
            if (lens == null) {
                Text(
                    stringResource(R.string.ui_lens_none_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ui_lens_none_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(14.dp))
                PillButton(stringResource(R.string.ui_lens_add), Signal, Color.White, onAddLens)
            } else if (artifact == null) {
                val lensLabel = lensName(lens)
                Text(
                    stringResource(R.string.ui_lens_empty_title, lensLabel.uppercase()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    lensDescription(lens),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(10.dp))
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
                        color = Signal
                    )
                    Spacer(Modifier.weight(1f))
                    if (stale) StaleBadge()
                }
                Spacer(Modifier.height(12.dp))
                MarkdownBody(Lens.stripCitations(artifact.body))
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.ui_lens_from_notes, artifact.covered.size, relativeAge(artifact.generatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = Ink2)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(confirmLabel, Red, Color.White, onConfirm)
                }
            }
        }
    }
}

@Composable
private fun SpacePickerDialog(
    title: String,
    spaces: List<Space>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    PickerRow(stringResource(R.string.ui_space_none), selectedId == null) { onSelect(null) }
                    spaces.forEach { space ->
                        Spacer(Modifier.height(6.dp))
                        PickerRow(space.name, selectedId == space.id) { onSelect(space.id) }
                    }
                    if (spaces.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.ui_space_picker_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) SignalSoft else Surface2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Signal else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Signal,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SpaceNameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val clean = name.trim()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface2)
                        .border(1.dp, Line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    if (name.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_space_name_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink2
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                        singleLine = true,
                        cursorBrush = SolidColor(Signal),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { if (clean.isNotEmpty()) onConfirm(clean) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(
                        confirmLabel,
                        if (clean.isEmpty()) Surface2 else Signal,
                        if (clean.isEmpty()) Ink2 else Color.White,
                        { if (clean.isNotEmpty()) onConfirm(clean) },
                        enabled = clean.isNotEmpty()
                    )
                }
                // Inside the dialog composition, so the field is attached before the request.
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
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
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
                MdKind.H3 -> Text(
                    block.text.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Signal
                )
                MdKind.BULLET -> Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = Signal)
                    Spacer(Modifier.width(8.dp))
                    LinkifiedText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2,
                        modifier = Modifier.weight(1f)
                    )
                }
                MdKind.PARAGRAPH -> LinkifiedText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2
                )
            }
        }
    }
}

private enum class MdKind { H2, H3, BULLET, PARAGRAPH }

private data class MdBlock(val kind: MdKind, val text: String)

/**
 * Deliberately small markdown: `##`/`###` headings, `-` bullets and paragraphs.
 * The lens prompt asks for exactly this shape and never for inline emphasis,
 * so `**` is stripped rather than parsed.
 */
private fun parseMarkdown(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock(MdKind.PARAGRAPH, paragraph.toString().trim()))
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
                blocks.add(MdBlock(MdKind.BULLET, clean.drop(2).trim()))
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

/** "just now", "4m ago", "2h ago", "3d ago". Ages are re-read on every poll tick. */
@Composable
private fun relativeAge(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        timestamp <= 0L -> stringResource(R.string.ui_age_never)
        diff <= 60_000L -> stringResource(R.string.ui_age_just_now)
        diff < 3_600_000L -> stringResource(R.string.ui_age_minutes, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.ui_age_hours, diff / 3_600_000L)
        diff < 604_800_000L -> stringResource(R.string.ui_age_days, diff / 86_400_000L)
        else -> stringResource(R.string.ui_age_weeks, diff / 604_800_000L)
    }
}

/**
 * An artifact is stale when it and its space disagree about which notes exist: the space holds a
 * note the artifact never saw, or the artifact cites a note the space no longer has.
 *
 * Note ids only ever grow, but comparing ids against `generatedAt` was never the same thing as
 * comparing note sets; this checks the sets. One artifact per (space, lens) means the same rule
 * applies to every lens.
 */
private fun artifactIsStale(
    artifact: LensArtifact,
    spaceNotes: List<NoteItem>
): Boolean {
    if (spaceNotes.any { it.id !in artifact.covered }) return true
    val present = spaceNotes.mapTo(HashSet()) { it.id }
    return artifact.covered.any { it !in present }
}

/**
 * Applies one edit to one space: read the stored copy, transform it, write it back — all under
 * [this] lock, so two edits in flight cannot each start from the same stale copy.
 *
 * Callers pass a transform rather than a [Space] on purpose: the screen's copy of a space is a
 * polled mirror, and writing that back would undo whatever else changed it. `SpaceStore.update`
 * itself keeps the id, the creation date and the notes untouched.
 */
private suspend fun Mutex.updateSpace(spaceId: String, mutate: (Space) -> Space) {
    withLock {
        withContext(Dispatchers.IO) {
            val fresh = SpaceStore.all().firstOrNull { it.id == spaceId } ?: return@withContext
            SpaceStore.update(mutate(fresh))
        }
    }
}

@Composable
private fun ActionsScreen(actions: List<NoteItem>) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ScreenHeader(stringResource(R.string.ui_tab_actions), stringResource(R.string.ui_actions_pending, actions.size)) }
        if (actions.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ui_actions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        items(actions) { note ->
            Box(Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Surface1, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .border(1.5.dp, Line, CircleShape)
                                .clickable { }
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(note.title, style = MaterialTheme.typography.bodyMedium, color = Ink)
                            Spacer(Modifier.height(3.dp))
                            Text(note.oneLine, style = MaterialTheme.typography.bodySmall, color = Ink2)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The one switch that changes both the app's language and the language the model writes notes and
 * reports in. The activity is recreated on change, so every resource re-resolves immediately.
 */
@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val spanish = AppLocale.language(context) == AppLocale.SPANISH
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.common_language),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink
            )
            Spacer(Modifier.weight(1f))
            SubTab(stringResource(R.string.common_language_english), !spanish) {
                activity?.let { AppLocale.set(it, AppLocale.ENGLISH) }
            }
            Spacer(Modifier.width(8.dp))
            SubTab(stringResource(R.string.common_language_spanish), spanish) {
                activity?.let { AppLocale.set(it, AppLocale.SPANISH) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.common_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Ink2
        )
    }
}

@Composable
private fun SettingsScreen(armed: Boolean, recording: Boolean, tick: Int) {    val context = LocalContext.current
    // CaptureLog is a plain singleton, not compose state: key the read on the poll tick so the
    // log actually refreshes instead of freezing at whatever it held on first composition.
    val logTail = remember(tick) {
        CaptureLog.dump().split("\n").takeLast(14).reversed().joinToString("\n")
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ScreenHeader(stringResource(R.string.ui_tab_settings), stringResource(R.string.ui_settings_subtitle)) }

        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Surface1, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        SettingRow(stringResource(R.string.ui_settings_capture_service), if (armed) stringResource(R.string.ui_settings_armed) else stringResource(R.string.ui_settings_disarmed), if (armed) Signal else Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow(stringResource(R.string.ui_settings_recording), if (recording) stringResource(R.string.ui_settings_live) else stringResource(R.string.ui_settings_idle), if (recording) Signal else Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow(stringResource(R.string.ui_settings_transcription), stringResource(R.string.ui_settings_transcription_value), Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow(stringResource(R.string.ui_settings_transform), stringResource(R.string.ui_settings_transform_value), Ink2)
                        Spacer(Modifier.height(14.dp))
                        LanguageSetting()
                    }
                }
            }
        }

        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Surface1, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.ui_speech_model), style = MaterialTheme.typography.labelSmall, color = Ink2)
                        Spacer(Modifier.height(10.dp))
                        Text(ModelInstaller.status, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.ui_settings_speech_model_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                        if (ModelInstaller.busy) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { ModelInstaller.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Signal,
                                trackColor = Line
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = SignalSoft,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.clickable(
                                enabled = !ModelInstaller.busy && !ModelInstaller.installed
                            ) {
                                ModelInstaller.download(context)
                            }
                        ) {
                            Text(
                                when {
                                    ModelInstaller.installed -> stringResource(R.string.ui_settings_model_ready)
                                    ModelInstaller.busy -> stringResource(R.string.ui_settings_model_progress, (ModelInstaller.progress * 100).toInt())
                                    else -> stringResource(R.string.ui_settings_download_model)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (ModelInstaller.installed) Mint else Signal,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Surface1, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.ui_settings_event_log), style = MaterialTheme.typography.labelSmall, color = Ink2)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (logTail.isBlank()) stringResource(R.string.ui_settings_no_events) else logTail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

@Composable
private fun NoteDetailScreen(
    note: NoteItem,
    spaces: List<Space>,
    allMedia: List<MediaItem>,
    onOpenMedia: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    /** The reprocess dialog is open. Separate from [reanalyzing], which is the model run. */
    var reprocessing by remember(note.id) { mutableStateOf(false) }
    var reanalyzing by remember(note.id) { mutableStateOf(false) }
    var reanalyzeFailed by remember(note.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val copyLink = stringResource(R.string.common_link_copy)
    val spaceName = spaces.firstOrNull { it.id == note.spaceId }?.name
    val noteFormat = NoteFormat.from(note.format)
    val noteMedia = remember(note.id, allMedia) { allMedia.filter { note.id in it.noteIds } }

    /**
     * Reruns one format over the words already stored. Only the model's fields are replaced, so
     * the user's text, the recording and the space survive; the app-level poll re-reads the note,
     * which is why nothing here has to mirror the result back into state.
     */
    fun reprocess(chosen: NoteFormat) {
        if (reanalyzing) return
        // Start from the stored note, not the mirror: the poll may be a few hundred ms behind, and
        // the words the run is based on have to be the words on disk.
        val started = NoteStore.byId(note.id) ?: return
        val startedVerbatim = started.verbatim
        val startedEditedAt = started.editedAt
        reprocessing = false
        reanalyzeFailed = false
        reanalyzing = true
        scope.launch(Dispatchers.IO) {
            val fields = Transform.apply(
                context.filesDir,
                startedVerbatim,
                chosen,
                AppLocale.modelLanguage(context)
            )
            if (fields != null) {
                val stored = NoteStore.byId(note.id)
                if (stored != null &&
                    (stored.verbatim != startedVerbatim || stored.editedAt != startedEditedAt)
                ) {
                    // The user rewrote the note while the pass was running: their edit wins, and
                    // overwriting it with fields derived from older words would be a silent lie.
                    CaptureLog.add("notes: reanalysis skipped, note changed while it ran")
                } else if (stored != null) {
                    NoteStore.replaceWithAi(
                        stored.copy(
                            title = fields.title,
                            oneLine = fields.oneLine,
                            mode = fields.mode,
                            verbatim = stored.verbatim,
                            why = fields.why,
                            question = fields.question,
                            tags = fields.tags,
                            items = fields.items,
                            format = chosen.id
                        )
                    )
                }
            }
            withContext(Dispatchers.Main) {
                reanalyzing = false
                reanalyzeFailed = fields == null
            }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.padding(start = 12.dp, end = 20.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.ui_action_back),
                    tint = Ink,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onBack() }
                        .padding(8.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_note_label), style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.weight(1f))
                // Nothing to reprocess when there are no words: a note can only be re-shaped
                // from what was said, not from fields a previous run produced.
                PillButton(
                    stringResource(R.string.ui_note_reanalyze),
                    Surface2,
                    Ink,
                    { reprocessing = true },
                    enabled = note.verbatim.isNotBlank() && !reanalyzing
                )
                Spacer(Modifier.width(8.dp))
                PillButton(stringResource(R.string.ui_action_edit), Surface2, Ink, onEdit)
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
                // Plain and list notes have no one-liner of their own; the empty line would just
                // add a gap under the title.
                if (note.oneLine.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    LinkifiedText(
                        text = note.oneLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink2
                    )
                }
            }
        }
        if (note.items.isNotEmpty()) {
            item {
                DetailSection(if (noteFormat.ordered) stringResource(R.string.ui_section_steps) else stringResource(R.string.ui_section_items)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        note.items.forEachIndexed { index, item ->
                            val links = remember(item) { linkRanges(item) }
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    if (noteFormat.ordered) "${index + 1}" else "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Signal,
                                    modifier = Modifier.width(22.dp)
                                )
                                LinkifiedText(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                // Copying is the other thing you do with a link, and a visible
                                // button beats discovering a long-press by accident.
                                if (links.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        copyLink,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Signal,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable {
                                                val raw = item.substring(links.first())
                                                clipboard.setText(AnnotatedString(openableUrl(raw)))
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
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
                color = Surface1,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .border(
                        1.dp,
                        if (note.spaceId == null) Line else Signal.copy(alpha = 0.45f),
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { picking = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.ui_note_space_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2
                    )
                    Text(
                        spaceName ?: stringResource(R.string.ui_space_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (spaceName == null) Ink2 else Signal,
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
        if (note.question.isNotBlank()) {
            item {
                DetailSection(stringResource(R.string.ui_section_open_question)) {
                    LinkifiedText(
                        text = note.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Amber
                    )
                }
            }
        }
        if (note.tags.isNotEmpty()) {
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    note.tags.forEach { tag ->
                        Box(
                            Modifier
                                .border(1.dp, Line, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(stringResource(R.string.ui_note_tag, tag), style = MaterialTheme.typography.bodySmall, color = Ink2)
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
                // Only spaceId is written; the 400ms poll mirrors it back into the note.
                scope.launch(Dispatchers.IO) { NoteStore.assignSpace(note.id, spaceId) }
            },
            onDismiss = { picking = false }
        )
    }

    if (reprocessing) {
        ReprocessDialog(
            initialFormat = noteFormat,
            onReprocess = { chosen -> reprocess(chosen) },
            onDismiss = { reprocessing = false }
        )
    }
}

/**
 * Asks which shape to reprocess a note into. It defaults to the note's current format, because
 * "try that again" is the common case and changing the shape is the exception.
 */
@Composable
private fun ReprocessDialog(
    initialFormat: NoteFormat,
    onReprocess: (NoteFormat) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(initialFormat) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.ui_reprocess_title), style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.ui_reprocess_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(14.dp))
                FormatChips(chosen, { chosen = it })
                Spacer(Modifier.height(6.dp))
                Text(
                    formatBlurb(chosen),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(stringResource(R.string.ui_reprocess_confirm), Signal, Color.White, { onReprocess(chosen) })
                }
            }
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

// --------------------------------------------------------------------------- note editing

/**
 * The modes a note can be filed under, in the order the editor's chip row shows them.
 *
 * "Note" is last and is the mode a plain or list note carries: it is not an intent, so it stays
 * out of the way rather than sitting at the front of the row.
 */
private val noteModes =
    listOf("Insight", "Idea", "Action", "Reference", "Question", "Reflection", "Note")

/**
 * Full-screen note editor: the only screen in the app that rewrites note content.
 *
 * Every field starts from what is stored, including the empty ones, because a capture that
 * produced no "why" or no question must still be editable. Save hands a fully-formed
 * [StoredNote] up to [SievexApp], which is where the `NoteStore.update` write happens; Cancel
 * throws the draft away and touches nothing.
 */
@Composable
private fun NoteEditorScreen(
    note: NoteItem,
    onCancel: () -> Unit,
    onSave: (StoredNote) -> Unit
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var oneLine by remember(note.id) { mutableStateOf(note.oneLine) }
    var verbatim by remember(note.id) { mutableStateOf(note.verbatim) }
    var why by remember(note.id) { mutableStateOf(note.why) }
    var question by remember(note.id) { mutableStateOf(note.question) }
    var tagsText by remember(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
    var mode by remember(note.id) { mutableStateOf(note.mode) }
    // One item per line: the stored list round-trips through this text unchanged.
    var itemsText by remember(note.id) { mutableStateOf(note.items.joinToString("\n")) }

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

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 20.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ui_action_cancel),
                tint = Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onCancel() }
                    .padding(8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_editor_title), style = MaterialTheme.typography.labelSmall, color = Ink2)
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onCancel)
            Spacer(Modifier.width(8.dp))
            PillButton(
                stringResource(R.string.ui_action_save),
                Signal,
                Color.White,
                {
                    onSave(
                        StoredNote(
                            id = note.id,
                            title = title.trim(),
                            oneLine = oneLine.trim(),
                            mode = mode,
                            whenText = note.whenText,
                            verbatim = verbatim.trim(),
                            why = why.trim(),
                            question = question.trim(),
                            tags = tags,
                            // Not content: `NoteStore.update` keeps the stored audio link, space
                            // and edit stamp, so the editor never has to carry them.
                            audioPath = null,
                            spaceId = note.spaceId,
                            editedAt = null,
                            // The format is not editable here: reanalyzing is what changes the
                            // shape, and that path owns the prompt as well as the fields.
                            format = note.format,
                            items = editedItems
                        )
                    )
                }
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
            EditorField(stringResource(R.string.ui_section_title), title, stringResource(R.string.ui_placeholder_title), { title = it }, singleLine = true)
            EditorField(
                stringResource(R.string.ui_section_one_line),
                oneLine,
                stringResource(R.string.ui_placeholder_one_line),
                { oneLine = it },
                minHeight = 46.dp
            )
            if (showsItems) {
                EditorField(
                    stringResource(R.string.ui_section_items),
                    itemsText,
                    stringResource(R.string.ui_placeholder_items),
                    { itemsText = it },
                    minHeight = 120.dp
                )
            }
            EditorField(
                stringResource(R.string.ui_section_what_i_said),
                verbatim,
                stringResource(R.string.ui_placeholder_what_i_said),
                { verbatim = it },
                minHeight = 106.dp
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
            Text(
                stringResource(R.string.ui_editor_save_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
        }
    }
}

/**
 * One labelled text field in the app's field style: `Surface2` well, hairline border, placeholder
 * drawn behind the field so it disappears the moment there is text.
 *
 * [minHeight] is the minimum height of the *text area*; the well's own padding sits outside it, so
 * the whole well is tappable and a multi-line field cannot leave a dead strip below the caret.
 */
@Composable
private fun EditorField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    minHeight: Dp = 24.dp
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink2)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2)
                .border(1.dp, Line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2.copy(alpha = 0.6f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                singleLine = singleLine,
                cursorBrush = SolidColor(Signal),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
            )
        }
    }
}

// --------------------------------------------------------------------------- new note

private val wordGap = Regex("\\s+")

/** A title for a note nobody titled: the opening words of what was typed. */
private fun derivedTitle(text: String, fallback: String): String {
    val words = text.trim()
        .split(wordGap)
        .filter { it.isNotEmpty() }
        .take(8)
        .joinToString(" ")
    return words.trimEnd('.', ',', ';', ':').ifBlank { fallback }
}

/**
 * Writes a note by hand instead of by voice.
 *
 * The typed text is the record: whatever the model does or fails to do, it ends up as
 * [StoredNote.verbatim], so a failed shaping still saves the words the user wrote rather than
 * losing them. Shaping is forced off when there is nothing to shape with — no model, or a
 * [NoteFormat.PLAIN] note, which exists precisely to keep the words untouched.
 */
@Composable
private fun NewNoteScreen(
    spaces: List<Space>,
    initialSpaceId: String?,
    onCancel: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var selectedSpaceId by remember { mutableStateOf(initialSpaceId) }
    // Starts on the format the next capture would use; the chips below are the same four.
    var format by remember { mutableStateOf(Formats.next) }
    var useAi by remember { mutableStateOf(TransformModel.installed) }
    var saving by remember { mutableStateOf(false) }
    // Read from state when saving starts, not from the chips: changing a chip mid-save must not
    // relabel work that is already running.
    var savingWithAi by remember { mutableStateOf(false) }
    var pickingSpace by remember { mutableStateOf(false) }

    val aiAvailable = TransformModel.installed && format.usesLlm
    val aiOn = useAi && aiAvailable
    val canSave = text.isNotBlank() && !saving
    val spaceName = spaces.firstOrNull { it.id == selectedSpaceId }?.name
    val untitled = stringResource(R.string.common_untitled_note)

    fun save() {
        if (saving || text.isBlank()) return
        val typed = text
        val typedTitle = titleInput.trim()
        val chosen = format
        // Whether the shape was *asked for* decides the fallback, not whether the model happened
        // to be present: a List note with AI off must not be saved as a list with no items.
        val shapeRequested = chosen.usesLlm
        val shape = shapeRequested && useAi && TransformModel.installed
        savingWithAi = shape
        saving = true
        // Both the model call and the notes.json write are blocking: one IO hop, then back to
        // main only to tell the caller the note is on disk.
        scope.launch(Dispatchers.IO) {
            val fields = if (shape) {
                Transform.apply(
                    context.filesDir,
                    typed,
                    chosen,
                    AppLocale.modelLanguage(context)
                )
            } else {
                null
            }
            if (shape && fields == null) {
                CaptureLog.add("notes: shaping failed, saved the typed text as written")
            }
            NoteStore.add(
                StoredNote(
                    id = NoteStore.nextId(),
                    // A title the user typed is theirs; the model's is only a fallback for a
                    // blank one, and the words themselves are the last resort.
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
                    // Shaping that failed leaves a plain note, not a list-shaped note with no
                    // items: the format label should say what the note actually is.
                    format = if (shapeRequested && fields == null) NoteFormat.PLAIN.id else chosen.id,
                    items = fields?.items ?: emptyList(),
                    createdAt = System.currentTimeMillis()
                )
            )
            withContext(Dispatchers.Main) { onSaved() }
        }
    }

    // A half-written note must not be orphaned by the system back gesture either.
    BackHandler { if (!saving) onCancel() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 20.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ui_action_cancel),
                tint = if (saving) Ink2 else Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(enabled = !saving) { onCancel() }
                    .padding(8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_new_note_title), style = MaterialTheme.typography.labelSmall, color = Ink2)
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onCancel, enabled = !saving)
            Spacer(Modifier.width(8.dp))
            PillButton(
                stringResource(R.string.ui_action_save),
                if (canSave) Signal else Surface2,
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
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditorField(
                stringResource(R.string.ui_section_text),
                text,
                stringResource(R.string.ui_placeholder_new_note_text),
                { text = it },
                minHeight = 130.dp
            )
            EditorField(
                stringResource(R.string.ui_section_title),
                titleInput,
                stringResource(R.string.ui_placeholder_new_note_title),
                { titleInput = it },
                singleLine = true
            )
            Column {
                Text(stringResource(R.string.ui_section_space), style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Surface1,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (selectedSpaceId == null) Line else Signal.copy(alpha = 0.45f),
                            RoundedCornerShape(20.dp)
                        )
                        .clickable(enabled = !saving) { pickingSpace = true }
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            spaceName ?: stringResource(R.string.ui_space_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (spaceName == null) Ink2 else Signal,
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
                FormatChips(format, { format = it })
                Spacer(Modifier.height(6.dp))
                Text(
                    formatBlurb(format),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ui_new_note_shape_with_ai),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    SubTab(stringResource(R.string.ui_action_on), aiOn) { if (aiAvailable) useAi = true }
                    Spacer(Modifier.width(8.dp))
                    SubTab(stringResource(R.string.ui_action_off), !aiOn) { useAi = false }
                }
                if (!format.usesLlm) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ui_new_note_plain_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
                }
                if (!TransformModel.installed && format.usesLlm) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ui_new_note_no_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
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
}

// --------------------------------------------------------------------------- space editing

/**
 * Rename a space and write down what it is for. Only these two fields are edited here: the lens
 * selection is its own affordance in the Insights tab, and both writes go through
 * `SpaceStore.update`.
 */
@Composable
private fun SpaceEditorDialog(
    name: String,
    description: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var editName by remember { mutableStateOf(name) }
    var editDescription by remember { mutableStateOf(description) }
    val clean = editName.trim()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.ui_space_edit_title), style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(14.dp))
                EditorField(stringResource(R.string.ui_section_name), editName, stringResource(R.string.ui_space_name_placeholder), { editName = it }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                EditorField(
                    stringResource(R.string.ui_section_description),
                    editDescription,
                    stringResource(R.string.ui_placeholder_space_description),
                    { editDescription = it },
                    minHeight = 70.dp
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(
                        stringResource(R.string.ui_action_save),
                        if (clean.isEmpty()) Surface2 else Signal,
                        if (clean.isEmpty()) Ink2 else Color.White,
                        { if (clean.isNotEmpty()) onSave(clean, editDescription) },
                        enabled = clean.isNotEmpty()
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- lenses

/** What the custom-lens dialog collects, before it is given an id and saved. */
private data class LensDraft(
    val name: String,
    val description: String,
    val instruction: String,
    val sections: List<String>
)

@Composable
private fun LensPickerDialog(
    availableBuiltIns: List<LensSpec>,
    availableCustom: List<LensSpec>,
    onPick: (LensSpec) -> Unit,
    onNewCustom: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.ui_lens_add), style = MaterialTheme.typography.titleMedium, color = Ink)
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
                    PickerRow(stringResource(R.string.ui_lens_picker_new_custom), false, onNewCustom)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun LensPickRow(spec: LensSpec, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
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
            Text(stringResource(R.string.ui_lens_custom_badge), style = MaterialTheme.typography.labelSmall, color = Blue)
        }
    }
}

@Composable
private fun CustomLensDialog(
    onCreate: (LensDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var sectionsText by remember { mutableStateOf("") }

    val cleanName = name.trim()
    val cleanInstruction = instruction.trim()
    val sections = remember(sectionsText) {
        sectionsText.split(",")
            .map { it.trim().removePrefix("##").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val ready = cleanName.isNotEmpty() && cleanInstruction.isNotEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.ui_lens_custom_title), style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ui_lens_custom_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(14.dp))
                EditorField(stringResource(R.string.ui_section_name), name, stringResource(R.string.ui_placeholder_lens_name), { name = it }, singleLine = true)
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
                    minHeight = 106.dp
                )
                Spacer(Modifier.height(12.dp))
                EditorField(
                    stringResource(R.string.ui_section_sections),
                    sectionsText,
                    stringResource(R.string.ui_placeholder_lens_sections),
                    { sectionsText = it },
                    singleLine = true
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(
                        stringResource(R.string.ui_lens_create),
                        if (ready) Signal else Surface2,
                        if (ready) Color.White else Ink2,
                        {
                            if (ready) {
                                onCreate(
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
    }
}

/**
 * Taking a lens off a space, with the harder "delete this lens" offered only for custom ones.
 *
 * Removing never deletes a built-in: it only drops the id from this space, so every artifact it
 * produced stays on disk for other spaces and the lens stays available to add back.
 */
@Composable
private fun RemoveLensDialog(
    spec: LensSpec,
    onRemoveFromSpace: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.ui_lens_remove_title, lensName(spec)),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (onDelete == null) {
                        stringResource(R.string.ui_lens_remove_body_builtin)
                    } else {
                        stringResource(R.string.ui_lens_remove_body_custom)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(stringResource(R.string.ui_action_remove), Surface2, Ink, onRemoveFromSpace)
                }
                if (onDelete != null) {
                    // On its own row: three pills abreast would not fit a narrow screen, and the
                    // destructive choice should not sit next to the ordinary one by accident.
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PillButton(stringResource(R.string.ui_lens_delete), Red, Color.White, onDelete)
                    }
                }
            }
        }
    }
}
