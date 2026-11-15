package com.sievex.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sievex.app.CaptureLog
import com.sievex.app.CaptureService
import com.sievex.app.LensArtifact
import com.sievex.app.LensService
import com.sievex.app.LensSpec
import com.sievex.app.LensStore
import com.sievex.app.NoteStore
import com.sievex.app.Pipeline
import com.sievex.app.PipelineStage
import com.sievex.app.Space
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import com.sievex.app.asr.ModelInstaller
import com.sievex.app.llm.TransformModel
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
    val editedAt: Long? = null
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
                editedAt = n.editedAt
            )
        },
        spaces = allSpaces,
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
    var noteCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var insights by remember { mutableStateOf<Map<String, LensArtifact?>>(emptyMap()) }
    var currentSpaceId by remember { mutableStateOf<String?>(null) }
    var notifAccess by remember { mutableStateOf(false) }
    var pickingCurrentSpace by remember { mutableStateOf(false) }
    var namingNewSpace by remember { mutableStateOf(false) }
    var deletingSpace by remember { mutableStateOf<Space?>(null) }
    /** The note whose editor is open, if any. The editor sits on top of the note detail. */
    var editingNoteId by remember { mutableStateOf<Long?>(null) }
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
        noteCounts = snapshot.noteCounts
        insights = snapshot.insights
        currentSpaceId = snapshot.currentSpaceId
    }

    // Parsing notes.json (and the spaces/jobs stores that load with it) is file work: keep it off the frame thread.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { NoteStore.init(context.filesDir) }
        ModelInstaller.refresh(context)
        TransformModel.refresh(context)
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
    if (openNote != null) {
        NoteDetailScreen(
            note = openNote,
            spaces = spaces,
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
            initialLensId = openSpaceLensId,
            onBack = {
                openSpaceId = null
                openSpaceLensId = null
            },
            onOpenNote = { openNoteId = it.id }
        )
        return
    }

    Scaffold(
        containerColor = Canvas,
        bottomBar = {
            NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
                NavItem(0, "Record", tab, { tab = it }) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (tab == 0) Signal else Ink2)
                    )
                }
                NavItem(1, "Spaces", tab, { tab = it }) {
                    Icon(Icons.Filled.List, contentDescription = "Spaces", modifier = Modifier.size(22.dp))
                }
                NavItem(2, "Notes", tab, { tab = it }) {
                    Icon(Icons.Filled.Home, contentDescription = "Notes", modifier = Modifier.size(22.dp))
                }
                NavItem(3, "Actions", tab, { tab = it }) {
                    Icon(Icons.Filled.Check, contentDescription = "Actions", modifier = Modifier.size(22.dp))
                }
                NavItem(4, "Settings", tab, { tab = it }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(22.dp))
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> RecordScreen(
                    armed = armed,
                    recording = recording,
                    modelsReady = ModelInstaller.installed && TransformModel.installed,
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
                    }
                ) { openNoteId = it.id }
                3 -> ActionsScreen(notes.filter { it.mode == "Action" })
                4 -> SettingsScreen(armed, recording, tick)
            }
        }
    }

    if (pickingCurrentSpace) {
        SpacePickerDialog(
            title = "Record into space",
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
            title = "New space",
            confirmLabel = "Create space",
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
            title = "Delete \"${spaceToDelete.name}\"?",
            body = "Its reports are deleted with it. The notes inside are kept and move to No space.",
            confirmLabel = "Delete space",
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
            Text("SievexAI", style = MaterialTheme.typography.labelSmall, color = Ink2)
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
private fun PillButton(
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
                        recording -> "RECORDING"
                        armed -> "ARMED"
                        else -> "READY"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) Signal else Ink2
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    recording -> "Capturing your burst."
                    armed -> "Listening. Tap pause on your podcast, or record now."
                    else -> "Arm to start listening for a podcast pause."
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
                        "Enable notification access",
                        style = MaterialTheme.typography.labelMedium,
                        color = Blue,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    recording -> PillButton("Stop", Signal, Color.White, onStop)
                    armed -> {
                        PillButton("Record now", Surface2, Ink, onRecord)
                        PillButton("Disarm", Surface2, Ink2, onDisarm)
                    }
                    else -> PillButton("Arm capture", Signal, Color.White, onArm)
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
        Text(mode.uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
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
        Text("edited", style = MaterialTheme.typography.labelSmall, color = Ink2)
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
                Text(note.whenText, style = MaterialTheme.typography.labelSmall, color = Ink2)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
            Text("SPEECH MODEL", style = MaterialTheme.typography.labelSmall, color = Blue)
            Spacer(Modifier.height(8.dp))
            Text(
                "One-time download, 640 MB. Everything runs on-device afterwards.",
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
                        "Download speech model",
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
            Text("TRANSFORM MODEL", style = MaterialTheme.typography.labelSmall, color = Mint)
            Spacer(Modifier.height(8.dp))
            Text(
                "LFM3-1.7B, 1.1 GB. Gives every note its mode, title, why it matters and an open question.",
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
                        "Download transform model",
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
            "Record",
            if (modelsReady) "Tap to capture, tap again to stop" else "Finish setup first"
        )

        if (!modelsReady) {
            if (!ModelInstaller.installed) ModelSetupCard()
            if (!TransformModel.installed) {
                Spacer(Modifier.height(12.dp))
                TransformSetupCard()
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Both models are needed before recording. After they land, everything runs on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
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
                    title = "TRANSCRIBING AUDIO",
                    body = "",
                    accent = Blue
                )
                PipelineStage.TRANSFORMING -> StatusCard(
                    title = Pipeline.step.ifBlank { "Understanding intent" }.uppercase(Locale.US),
                    body = "",
                    accent = Mint
                )
                PipelineStage.DONE -> DoneCard(Pipeline.lastTitle, onOpenNotes)
                PipelineStage.FAILED -> StatusCard(
                    title = "NOTHING FILED",
                    body = Pipeline.detail,
                    accent = Red
                )
                PipelineStage.IDLE -> {
                    Text(
                        if (recording) formatDuration(elapsed) else "Tap to record",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (recording) Signal else Ink
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (recording) "Recording" else "Press the circle, speak, press again",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        color = if (armed) SignalSoft else Surface2,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable { if (armed) onDisarm() else onArm() }
                    ) {
                        Text(
                            if (armed) "Podcast trigger: on" else "Podcast trigger: off",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (armed) Signal else Ink2,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SpaceChip(spaceName, onPickSpace)
            Spacer(Modifier.height(28.dp))
        }
    }
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
            Text("NOTE READY", style = MaterialTheme.typography.labelSmall, color = Signal)
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
                    "View in Notes",
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
                ScreenHeader("Notes", notes.size.toString() + " captured · walking mode")
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
                    "RECENT",
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
                    "Nothing captured yet. Arm capture, import the model in Settings, then tap pause on your podcast.",
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
                if (active) "Space: $spaceName" else "Space: none",
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

@Composable
private fun StaleBadge(label: String = "STALE") {
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
                ScreenHeader("Spaces", "One container for related notes.")
                Row(Modifier.padding(horizontal = 20.dp)) {
                    PillButton("New space", Signal, Color.White, onNewSpace)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hold a space to delete it.",
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
                    "No spaces yet. A space groups related notes; its insight is a transform over all of them.",
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
                onClickLabel = "Open space",
                onLongClickLabel = "Delete space",
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
                    if (noteCount == 1) "1 note" else "$noteCount notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (insight == null) "no report yet" else "report ${relativeAge(insight.generatedAt)}",
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
    initialLensId: String? = null,
    onBack: () -> Unit,
    onOpenNote: (NoteItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sub by remember(space.id) { mutableStateOf(0) }
    var editingSpace by remember(space.id) { mutableStateOf(false) }

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
                contentDescription = "Back",
                tint = Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onBack() }
                    .padding(8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("SPACE", style = MaterialTheme.typography.labelSmall, color = Ink2)
            Spacer(Modifier.weight(1f))
            PillButton("Edit", Surface2, Ink, { editingSpace = true })
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
            Text(
                if (spaceNotes.size == 1) "1 note" else "${spaceNotes.size} notes",
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
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubTab("Notes", sub == 0) { sub = 0 }
            SubTab("Insights", sub == 1) { sub = 1 }
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
                if (spaceNotes.isEmpty()) {
                    item {
                        Text(
                            "No notes in this space yet. Open a note from Notes and set its Space row.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink2,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
                items(spaceNotes) { note ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        NoteCard(note, onClick = { onOpenNote(note) })
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
                            "Hold a chip to remove that lens from this space.",
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
                                    label = if (shown == null) "Generate" else "Regenerate",
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
                                        "Working in the background. You can leave this screen — " +
                                            "we'll notify you when the report is ready.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink2
                                    )
                                }
                                if (failedLensId == lens.id) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "No report produced. Check the transform model in " +
                                            "Settings, then try again.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Red
                                    )
                                }
                                if (spaceNotes.isEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "A space needs at least one note before a lens can " +
                                            "say anything.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink2
                                    )
                                } else if (stale) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "Notes changed since this was generated. Regenerate " +
                                            "to bring it current.",
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
    Surface(
        color = if (selected) SignalSoft else Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.combinedClickable(
            onClickLabel = "Show ${spec.name}",
            onLongClickLabel = "Remove ${spec.name} from this space",
            onLongClick = onLongClick,
            onClick = onClick
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                spec.name,
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
        Text("+ Add lens", style = MaterialTheme.typography.labelMedium, color = Ink2)
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
                    "NO LENSES ON THIS SPACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A lens is one way of reading every note in this space: it picks the " +
                        "headings, cites the notes it used, and can be regenerated any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(14.dp))
                PillButton("Add a lens", Signal, Color.White, onAddLens)
            } else if (artifact == null) {
                Text(
                    "NO ${lens.name.uppercase()} YET",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    lens.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "It reads every note in this space and cites the ones it used. " +
                        "Nothing is written until you generate it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lens.name.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Signal
                    )
                    Spacer(Modifier.weight(1f))
                    if (stale) StaleBadge()
                }
                Spacer(Modifier.height(12.dp))
                MarkdownBody(artifact.body)
                Spacer(Modifier.height(14.dp))
                Text(
                    "from ${artifact.covered.size} notes · " +
                        "${relativeAge(artifact.generatedAt)} · ${artifact.model}",
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
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
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
                    PickerRow("No space", selectedId == null) { onSelect(null) }
                    spaces.forEach { space ->
                        Spacer(Modifier.height(6.dp))
                        PickerRow(space.name, selectedId == space.id) { onSelect(space.id) }
                    }
                    if (spaces.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No spaces yet. Make one in the Spaces tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
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
                            "Name this space",
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
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
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
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2
                    )
                }
                MdKind.PARAGRAPH -> Text(
                    block.text,
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
private fun relativeAge(timestamp: Long): String {
    if (timestamp <= 0L) return "never"
    val diff = System.currentTimeMillis() - timestamp
    if (diff <= 0L) return "just now"
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 604_800_000L -> "${diff / 86_400_000L}d ago"
        else -> "${diff / 604_800_000L}w ago"
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
        item { ScreenHeader("Actions", actions.size.toString() + " pending") }
        if (actions.isEmpty()) {
            item {
                Text(
                    "Nothing pending. Action-mode captures land here.",
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

@Composable
private fun SettingsScreen(armed: Boolean, recording: Boolean, tick: Int) {
    val context = LocalContext.current
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
        item { ScreenHeader("Settings", "Everything local. No account, no cloud.") }

        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Surface1, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        SettingRow("Capture service", if (armed) "Armed" else "Disarmed", if (armed) Signal else Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow("Recording", if (recording) "Live" else "Idle", if (recording) Signal else Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow("Transcription", "Parakeet TDT 0.6B v3", Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow("Transform", "LFM3-1.7B", Ink2)
                        Spacer(Modifier.height(14.dp))
                        SettingRow("Language", "English", Ink2)
                    }
                }
            }
        }

        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Surface1, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("SPEECH MODEL", style = MaterialTheme.typography.labelSmall, color = Ink2)
                        Spacer(Modifier.height(10.dp))
                        Text(ModelInstaller.status, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Parakeet TDT 0.6B v3, 640 MB. Downloaded once over HTTPS and verified " +
                                "against pinned checksums. After that the app runs fully offline.",
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
                                    ModelInstaller.installed -> "Model ready"
                                    ModelInstaller.busy -> "${(ModelInstaller.progress * 100).toInt()}%"
                                    else -> "Download model (640 MB)"
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
                        Text("EVENT LOG", style = MaterialTheme.typography.labelSmall, color = Ink2)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (logTail.isBlank()) "no events yet" else logTail,
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
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spaceName = spaces.firstOrNull { it.id == note.spaceId }?.name

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
                    contentDescription = "Back",
                    tint = Ink,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onBack() }
                        .padding(8.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("NOTE", style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.weight(1f))
                PillButton("Edit", Surface2, Ink, onEdit)
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
                    Text(note.whenText, style = MaterialTheme.typography.labelSmall, color = Ink2)
                }
                Spacer(Modifier.height(14.dp))
                Text(note.title, style = MaterialTheme.typography.headlineMedium, color = Ink)
                Spacer(Modifier.height(10.dp))
                Text(note.oneLine, style = MaterialTheme.typography.bodyLarge, color = Ink2)
            }
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
                        "Space: ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2
                    )
                    Text(
                        spaceName ?: "No space",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (spaceName == null) Ink2 else Signal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("Change", style = MaterialTheme.typography.labelSmall, color = Ink2)
                }
            }
        }
        item {
            DetailSection("WHAT I SAID") {
                Text(note.verbatim, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
        if (note.why.isNotBlank()) {
            item {
                DetailSection("WHY IT MATTERS") {
                    Text(note.why, style = MaterialTheme.typography.bodyMedium, color = Blue)
                }
            }
        }
        if (note.question.isNotBlank()) {
            item {
                DetailSection("OPEN QUESTION") {
                    Text(note.question, style = MaterialTheme.typography.bodyMedium, color = Amber)
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
                            Text("#$tag", style = MaterialTheme.typography.bodySmall, color = Ink2)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    if (picking) {
        SpacePickerDialog(
            title = "File note in space",
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

/** The six modes a note can be filed under, in the order the editor's chip row shows them. */
private val noteModes =
    listOf("Insight", "Idea", "Action", "Reference", "Question", "Reflection")

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
                contentDescription = "Cancel",
                tint = Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onCancel() }
                    .padding(8.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("EDIT NOTE", style = MaterialTheme.typography.labelSmall, color = Ink2)
            Spacer(Modifier.weight(1f))
            PillButton("Cancel", Surface2, Ink2, onCancel)
            Spacer(Modifier.width(8.dp))
            PillButton(
                "Save",
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
                            editedAt = null
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
            EditorField("TITLE", title, "What this note is about", { title = it }, singleLine = true)
            EditorField(
                "ONE LINE",
                oneLine,
                "The note in one sentence",
                { oneLine = it },
                minHeight = 46.dp
            )
            EditorField(
                "WHAT I SAID",
                verbatim,
                "Your words, as captured",
                { verbatim = it },
                minHeight = 106.dp
            )
            EditorField(
                "WHY IT MATTERS",
                why,
                "Optional: why this is worth keeping",
                { why = it },
                minHeight = 70.dp
            )
            EditorField(
                "OPEN QUESTION",
                question,
                "Optional: what is still unresolved",
                { question = it },
                minHeight = 70.dp
            )
            EditorField(
                "TAGS",
                tagsText,
                "product, strategy",
                { tagsText = it },
                singleLine = true
            )
            Column {
                Text("MODE", style = MaterialTheme.typography.labelSmall, color = Ink2)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    noteModes.forEach { candidate ->
                        SubTab(candidate, candidate == mode) { mode = candidate }
                    }
                }
            }
            Text(
                "Saving rewrites this note's words and marks it edited. Its recording and its " +
                    "space are left exactly as they are.",
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
                Text("Edit space", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(14.dp))
                EditorField("NAME", editName, "Name this space", { editName = it }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                EditorField(
                    "DESCRIPTION",
                    editDescription,
                    "What this space is for, in your words",
                    { editDescription = it },
                    minHeight = 70.dp
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(
                        "Save",
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
                Text("Add a lens", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "A lens is one way of reading this space's notes. Enabling one gives it its " +
                        "own report; lenses never overwrite each other.",
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
                            "Every lens is already on this space.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    PickerRow("New custom lens…", false, onNewCustom)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
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
            Text(spec.name, style = MaterialTheme.typography.bodyMedium, color = Ink)
            if (spec.description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!spec.builtIn) {
            Spacer(Modifier.width(10.dp))
            Text("CUSTOM", style = MaterialTheme.typography.labelSmall, color = Blue)
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
                Text("New custom lens", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The instruction is the body of the prompt: say what to produce and how to " +
                        "cite notes as [n<id>]. Headings are enforced on top of it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(14.dp))
                EditorField("NAME", name, "e.g. Risks", { name = it }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                EditorField(
                    "DESCRIPTION",
                    description,
                    "One line: what this lens is for",
                    { description = it },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                EditorField(
                    "INSTRUCTION",
                    instruction,
                    "What the report must do",
                    { instruction = it },
                    minHeight = 106.dp
                )
                Spacer(Modifier.height(12.dp))
                EditorField(
                    "SECTIONS",
                    sectionsText,
                    "What could go wrong, Early signals, Mitigations",
                    { sectionsText = it },
                    singleLine = true
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton(
                        "Create lens",
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
                    "Remove \"${spec.name}\" from this space?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (onDelete == null) {
                        "This only takes the lens off this space. Its reports stay on disk and " +
                            "you can add it back at any time."
                    } else {
                        "Removing takes it off this space and keeps every report it made. " +
                            "Deleting removes the lens itself, so no space can use it again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Cancel", Surface2, Ink2, onDismiss)
                    Spacer(Modifier.width(10.dp))
                    PillButton("Remove", Surface2, Ink, onRemoveFromSpace)
                }
                if (onDelete != null) {
                    // On its own row: three pills abreast would not fit a narrow screen, and the
                    // destructive choice should not sit next to the ordinary one by accident.
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PillButton("Delete lens", Red, Color.White, onDelete)
                    }
                }
            }
        }
    }
}
