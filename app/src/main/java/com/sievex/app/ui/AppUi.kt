package com.sievex.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sievex.app.AppLocale
import com.sievex.app.CaptureLog
import com.sievex.app.CaptureService
import com.sievex.app.Formats
import com.sievex.app.LensArtifact
import com.sievex.app.LensSpec
import com.sievex.app.LensStore
import com.sievex.app.MediaItem
import com.sievex.app.MediaStore
import com.sievex.app.NoteFollowUp
import com.sievex.app.NoteFormat
import com.sievex.app.NoteStore
import com.sievex.app.Pipeline
import com.sievex.app.PipelineStage
import com.sievex.app.R
import com.sievex.app.Reminder
import com.sievex.app.ReminderStore
import com.sievex.app.ReminderTargetType
import com.sievex.app.Space
import com.sievex.app.SpaceStore
import com.sievex.app.StoredNote
import com.sievex.app.asr.ModelInstaller
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val spaceId: String? = null,
    val editedAt: Long? = null,
    val format: String = NoteFormat.NOTE.id,
    val items: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val audioPath: String? = null,
    val completedAt: Long? = null,
    val followUps: List<NoteFollowUp> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val questionDrafts: List<String> = emptyList()
)

internal data class UiSnapshot(
    val notes: List<NoteItem>,
    val spaces: List<Space>,
    val media: List<MediaItem>,
    val noteCounts: Map<String, Int>,
    val mediaCounts: Map<String, Int>,
    val mediaNoteCounts: Map<Long, Int>,
    val insights: Map<String, LensArtifact?>,
    val allArtifacts: List<LensArtifact>,
    val reminders: List<Reminder>,
    val currentSpaceId: String?
)

internal fun readSnapshot(): UiSnapshot {
    val stored = NoteStore.all()
    val allSpaces = SpaceStore.all()
    val allMedia = MediaStore.all()
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
                createdAt = n.createdAt,
                audioPath = n.audioPath,
                completedAt = n.completedAt,
                followUps = n.followUps,
                openQuestions = n.resolvedOpenQuestions(),
                questionDrafts = n.questionDrafts
            )
        },
        spaces = allSpaces,
        media = allMedia,
        noteCounts = stored.mapNotNull { it.spaceId }.groupingBy { it }.eachCount(),
        mediaCounts = allMedia.groupingBy { it.spaceId }.eachCount(),
        mediaNoteCounts = allMedia.flatMap { it.noteIds }.groupingBy { it }.eachCount(),
        insights = SpaceStore.latestBySpace(allSpaces),
        allArtifacts = SpaceStore.allArtifacts(),
        reminders = ReminderStore.active(),
        currentSpaceId = SpaceStore.currentSpaceId
    )
}

@Composable
internal fun modeColor(mode: String): Color = when (mode) {
    "Insight" -> SignalText
    "Idea" -> Blue
    "Action" -> Mint
    "Reference" -> Ink2
    "Question" -> Amber
    else -> Ink2
}

@Composable
internal fun modeLabel(mode: String): String = when (mode) {
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
internal fun formatLabel(format: NoteFormat): String = when (format) {
    NoteFormat.NOTE -> stringResource(R.string.format_note)
    NoteFormat.LIST -> stringResource(R.string.format_list)
    NoteFormat.STEPS -> stringResource(R.string.format_steps)
    NoteFormat.PLAIN -> stringResource(R.string.format_plain)
}

@Composable
internal fun formatBlurb(format: NoteFormat): String = when (format) {
    NoteFormat.NOTE -> stringResource(R.string.format_note_blurb)
    NoteFormat.LIST -> stringResource(R.string.format_list_blurb)
    NoteFormat.STEPS -> stringResource(R.string.format_steps_blurb)
    NoteFormat.PLAIN -> stringResource(R.string.format_plain_blurb)
}

@Composable
internal fun lensName(spec: LensSpec): String = when (spec.id) {
    "insight" -> stringResource(R.string.lens_insight_name)
    "brainstorm" -> stringResource(R.string.lens_brainstorm_name)
    "reading" -> stringResource(R.string.lens_reading_name)
    "actions" -> stringResource(R.string.lens_actions_name)
    "tensions" -> stringResource(R.string.lens_tensions_name)
    "timeline" -> stringResource(R.string.lens_timeline_name)
    else -> spec.name
}

@Composable
internal fun lensDescription(spec: LensSpec): String = when (spec.id) {
    "insight" -> stringResource(R.string.lens_insight_description)
    "brainstorm" -> stringResource(R.string.lens_brainstorm_description)
    "reading" -> stringResource(R.string.lens_reading_description)
    "actions" -> stringResource(R.string.lens_actions_description)
    "tensions" -> stringResource(R.string.lens_tensions_description)
    "timeline" -> stringResource(R.string.lens_timeline_description)
    else -> spec.description
}

@Composable
internal fun whenLabel(text: String): String =
    if (text.startsWith("Today ")) {
        stringResource(R.string.common_today) + text.removePrefix("Today")
    } else {
        text
    }

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val LINK_TAG = "sievex-link"
private val URL_PATTERN = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

internal fun linkRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    for (match in URL_PATTERN.findAll(text)) {
        var end = match.range.last
        while (end > match.range.first && text[end] in ".,;:!?)]}\"'") end--
        if (end > match.range.first) ranges.add(match.range.first..end)
    }
    return ranges
}

internal fun openableUrl(raw: String): String =
    if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw

@Composable
internal fun LinkifiedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    precomputedRanges: List<IntRange>? = null
) {
    val ranges = remember(text, precomputedRanges) { precomputedRanges ?: linkRanges(text) }
    if (ranges.isEmpty()) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val openFailed = stringResource(R.string.common_link_open_failed)
    val linkColor = SignalText

    val annotated = remember(text, ranges, linkColor) {
        buildAnnotatedString {
            append(text)
            ranges.forEach { range ->
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
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
                        clipboard.setText(AnnotatedString(url))
                        Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                    }
                },
                onLongPress = { position ->
                    val link = linkAt(position) ?: return@detectTapGestures
                    clipboard.setText(AnnotatedString(openableUrl(link)))
                    Toast.makeText(context, R.string.ui_copied_toast, Toast.LENGTH_SHORT).show()
                }
            )
        },
        onTextLayout = { layout = it }
    )
}

@Composable
internal fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)) {
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
internal fun ModeChip(mode: String) {
    val c = modeColor(mode)
    Box(
        Modifier
            .border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(modeLabel(mode).uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
    }
}

@Composable
internal fun EditedBadge() {
    Box(
        Modifier
            .border(1.dp, Line, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(stringResource(R.string.ui_note_edited), style = MaterialTheme.typography.labelSmall, color = Ink2)
    }
}

@Composable
internal fun SpacePickerDialog(
    title: String,
    spaces: List<Space>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialogSurface(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
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
                Text(stringResource(R.string.ui_space_picker_empty), style = MaterialTheme.typography.bodySmall, color = Ink2)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
        }
    }
}

@Composable
internal fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SignalSoft else Surface2)
            .border(1.dp, if (selected) Signal.copy(alpha = 0.45f) else Line, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) SignalText else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = SignalText, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun SpaceNameDialog(
    title: String,
    confirmLabel: String,
    existingSpaces: List<Space>,
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    val clean = name.trim().take(60)
    val isDuplicate = clean.isNotEmpty() && existingSpaces.any { it.name.equals(clean, ignoreCase = true) }

    AppDialogSurface(onDismissRequest = onDismiss, modifier = Modifier.imePadding()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
        Spacer(Modifier.height(14.dp))
        EditorField(
            label = "",
            value = name,
            placeholder = stringResource(R.string.ui_space_name_placeholder),
            onChange = { name = it.take(60) },
            singleLine = true,
            focusRequester = focus,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = {
                if (clean.isNotEmpty()) onConfirm(clean)
            })
        )
        if (isDuplicate) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ui_space_duplicate_warning),
                style = MaterialTheme.typography.bodySmall,
                color = Amber
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
            Spacer(Modifier.width(10.dp))
            PillButton(
                confirmLabel,
                if (clean.isEmpty()) Surface2 else SignalFill,
                if (clean.isEmpty()) Ink2 else Color.White,
                { if (clean.isNotEmpty()) onConfirm(clean) },
                enabled = clean.isNotEmpty()
            )
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
fun SievexApp(
    initialSpaceId: String? = null,
    initialLensId: String? = null,
    initialNoteId: Long? = null,
    initialMediaId: String? = null,
    initialStartBurst: Boolean = false,
    onInitialSpaceConsumed: () -> Unit = {},
    onInitialTargetConsumed: () -> Unit = {},
    onInitialStartBurstConsumed: () -> Unit = {}
) {
    // 3-Tab Editorial Spine: 0 = Desk, 1 = Spaces, 2 = Triage
    var tab by rememberSaveable { mutableStateOf(0) }
    var openNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var openSpaceLensId by rememberSaveable { mutableStateOf<String?>(null) }
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var showCaptureSheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    var onboarded by remember { mutableStateOf(OnboardingPrefs.done(context)) }
    if (!onboarded) {
        OnboardingScreen(onDone = {
            OnboardingPrefs.markDone(context)
            onboarded = true
        })
        return
    }

    val notifyPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val armed by CaptureService.armedFlow.collectAsState()
    val recording by CaptureService.recordingFlow.collectAsState()
    val recordingAppendNoteId by CaptureService.appendNoteIdFlow.collectAsState()
    var notes by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var noteCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var mediaCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var mediaNoteCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var insights by remember { mutableStateOf<Map<String, LensArtifact?>>(emptyMap()) }
    var allArtifacts by remember { mutableStateOf<List<LensArtifact>>(emptyList()) }
    var reminders by remember { mutableStateOf<List<Reminder>>(emptyList()) }
    var currentSpaceId by remember { mutableStateOf<String?>(null) }
    var openMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var notifAccess by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(false) }
    var postNotifsGranted by remember { mutableStateOf(true) }
    var pickingCurrentSpace by remember { mutableStateOf(false) }
    var namingNewSpace by remember { mutableStateOf(false) }
    var editingSpaceTarget by remember { mutableStateOf<Space?>(null) }
    var deletingSpace by remember { mutableStateOf<Space?>(null) }
    var editingNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var composingNote by rememberSaveable { mutableStateOf(false) }
    var notesTagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var savingQuickJot by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val openAppSettings: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        }
    }

    val micDeniedLabel = stringResource(R.string.ui_mic_permission_denied)
    val settingsActionLabel = stringResource(R.string.ui_open_settings)
    var pendingAudioAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFollowUpNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingMediaVoiceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMediaVoiceSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val audioPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        val action = pendingAudioAction
        val followId = pendingFollowUpNoteId
        val mediaId = pendingMediaVoiceId
        val mediaSpaceId = pendingMediaVoiceSpaceId
        pendingAudioAction = null
        pendingFollowUpNoteId = null
        pendingMediaVoiceId = null
        pendingMediaVoiceSpaceId = null
        if (granted) {
            when {
                followId != null -> CaptureService.sendFollowUpBurst(context, followId, "followup grant")
                mediaId != null -> {
                    CaptureService.sendBurstWithMedia(context, listOf(mediaId), mediaSpaceId, "media grant")
                    tab = 0
                    showCaptureSheet = true
                }
                action != null -> {
                    CaptureService.send(context, action, "after grant")
                    if (action == CaptureService.ACTION_START_BURST) showCaptureSheet = true
                }
            }
        } else {
            CaptureLog.add("microphone permission denied")
            scope.launch {
                val res = snackbarHostState.showSnackbar(
                    message = micDeniedLabel,
                    actionLabel = settingsActionLabel,
                    duration = SnackbarDuration.Long
                )
                if (res == SnackbarResult.ActionPerformed) openAppSettings()
            }
        }
    }

    fun applySnapshot(snapshot: UiSnapshot) {
        notes = snapshot.notes
        spaces = snapshot.spaces
        media = snapshot.media
        noteCounts = snapshot.noteCounts
        mediaCounts = snapshot.mediaCounts
        mediaNoteCounts = snapshot.mediaNoteCounts
        insights = snapshot.insights
        allArtifacts = snapshot.allArtifacts
        reminders = snapshot.reminders
        currentSpaceId = snapshot.currentSpaceId
    }

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) {
            NoteStore.init(context.filesDir)
            ModelInstaller.refresh(context)
            TransformModel.refresh(context)
            Formats.restore(context)
            readSnapshot()
        }
        applySnapshot(initial)
    }

    val bothInstalled = ModelInstaller.installed && TransformModel.installed
    LaunchedEffect(bothInstalled, Pipeline.stage, Pipeline.blocked) {
        if (bothInstalled && Pipeline.stage == PipelineStage.FAILED && Pipeline.blocked) {
            Pipeline.reset()
        }
    }

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
        micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        postNotifsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        notifAccess = withContext(Dispatchers.IO) {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }
        combine(
            NoteStore.version,
            SpaceStore.version,
            MediaStore.version,
            LensStore.version,
            ReminderStore.version
        ) { _, _, _, _, _ -> Unit }.collect {
            applySnapshot(withContext(Dispatchers.IO) { readSnapshot() })
        }
    }

    var pendingSpaceId by remember { mutableStateOf(initialSpaceId) }
    var pendingLensId by remember { mutableStateOf(initialLensId) }
    var pendingNoteId by remember { mutableStateOf(initialNoteId) }
    var pendingMediaId by remember { mutableStateOf(initialMediaId) }
    LaunchedEffect(initialSpaceId, initialLensId) {
        if (initialSpaceId != null) {
            pendingSpaceId = initialSpaceId
            pendingLensId = initialLensId
        }
    }
    LaunchedEffect(initialNoteId, initialMediaId) {
        if (initialNoteId != null) pendingNoteId = initialNoteId
        if (initialMediaId != null) pendingMediaId = initialMediaId
    }
    LaunchedEffect(initialStartBurst) {
        if (!initialStartBurst) return@LaunchedEffect
        composingNote = false
        editingNoteId = null
        openMediaId = null
        openNoteId = null
        openSpaceId = null
        openSpaceLensId = null
        openSettings = false
        tab = 0
        showCaptureSheet = true
        val busy = Pipeline.stage == PipelineStage.TRANSCRIBING ||
            Pipeline.stage == PipelineStage.TRANSFORMING
        if (!busy && !CaptureService.isRecording) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Pipeline.reset()
                CaptureService.send(context, CaptureService.ACTION_START_BURST, "quick entry")
            } else {
                Pipeline.reset()
                pendingAudioAction = CaptureService.ACTION_START_BURST
                audioPerm.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        onInitialStartBurstConsumed()
    }
    LaunchedEffect(notes, pendingNoteId) {
        val wanted = pendingNoteId ?: return@LaunchedEffect
        if (notes.isEmpty()) return@LaunchedEffect
        if (notes.any { it.id == wanted }) {
            composingNote = false
            editingNoteId = null
            openMediaId = null
            openSpaceId = null
            openSettings = false
            openNoteId = wanted
            tab = 0
        }
        pendingNoteId = null
        onInitialTargetConsumed()
    }
    LaunchedEffect(media, pendingMediaId) {
        val wanted = pendingMediaId ?: return@LaunchedEffect
        if (media.isEmpty()) return@LaunchedEffect
        if (media.any { it.id == wanted }) {
            composingNote = false
            editingNoteId = null
            openNoteId = null
            openSettings = false
            openMediaId = wanted
            tab = 1
        }
        pendingMediaId = null
        onInitialTargetConsumed()
    }
    LaunchedEffect(spaces, pendingSpaceId) {
        val wanted = pendingSpaceId ?: return@LaunchedEffect
        if (spaces.isEmpty()) return@LaunchedEffect
        if (spaces.any { it.id == wanted }) {
            composingNote = false
            openMediaId = null
            editingNoteId = null
            openNoteId = null
            openSettings = false
            openSpaceLensId = pendingLensId
            openSpaceId = wanted
            tab = 1
        }
        pendingSpaceId = null
        pendingLensId = null
        onInitialSpaceConsumed()
    }

    val editingNote = notes.firstOrNull { it.id == editingNoteId }
    val openNote = notes.firstOrNull { it.id == openNoteId }
    val openSpace = spaces.firstOrNull { it.id == openSpaceId }
    val appendNoteTitle = recordingAppendNoteId?.let { id -> notes.firstOrNull { it.id == id }?.title }

    val hasDetailOverlay = openSettings || openSpace != null || openNote != null || composingNote || editingNote != null
    BackHandler(enabled = hasDetailOverlay) {
        when {
            editingNote != null -> editingNoteId = null
            composingNote -> composingNote = false
            openNote != null -> openNoteId = null
            openSpace != null -> {
                openSpaceId = null
                openSpaceLensId = null
            }
            openSettings -> openSettings = false
        }
    }

    val openNotifAccessSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    val modelsReady = ModelInstaller.installed &&
        (TransformModel.installed || Formats.next == NoteFormat.PLAIN)

    val toggleRecording: () -> Unit = {
        val busy = Pipeline.stage == PipelineStage.TRANSCRIBING ||
            Pipeline.stage == PipelineStage.TRANSFORMING
        if (!busy) {
            if (recording) {
                CaptureService.trigger(false, "record screen stop")
            } else if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Pipeline.reset()
                CaptureService.send(context, CaptureService.ACTION_START_BURST, "record screen")
                showCaptureSheet = true
            } else {
                Pipeline.reset()
                pendingAudioAction = CaptureService.ACTION_START_BURST
                audioPerm.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val toggleArm: (Boolean) -> Unit = { wantArm ->
        if (wantArm) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                CaptureService.send(context, CaptureService.ACTION_ARM)
            } else {
                pendingAudioAction = CaptureService.ACTION_ARM
                audioPerm.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            CaptureService.send(context, CaptureService.ACTION_DISARM)
        }
    }

    val actionCompletedToast = stringResource(R.string.ui_actions_completed_toast)
    val actionReopenedToast = stringResource(R.string.ui_actions_reopened_toast)
    val undoLabel = stringResource(R.string.ui_action_undo)
    val untitledLabel = stringResource(R.string.common_untitled_note)

    Box(Modifier.fillMaxSize()) {
        // Scaffold stays mounted at all times so tab scroll states survive opening notes, spaces, or sheets.
        Scaffold(
            containerColor = Canvas,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column(Modifier.fillMaxWidth()) {
                    CaptureStrip(
                        spaceName = spaces.firstOrNull { it.id == currentSpaceId }?.name,
                        armed = armed,
                        recording = recording,
                        notifAccess = notifAccess,
                        modelsReady = modelsReady,
                        onPickSpace = { pickingCurrentSpace = true },
                        onOpenSheet = { showCaptureSheet = true },
                        onQuickRecord = toggleRecording
                    )
                    HorizontalDivider(thickness = 1.dp, color = Line)
                    NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
                        NavItem(0, stringResource(R.string.ui_tab_desk), tab, { tab = it }) {
                            DeskTabIcon(selected = tab == 0)
                        }
                        NavItem(1, stringResource(R.string.ui_tab_spaces), tab, { tab = it }) {
                            SpacesTabIcon(selected = tab == 1)
                        }
                        NavItem(2, stringResource(R.string.ui_tab_triage), tab, { tab = it }) {
                            TriageTabIcon(selected = tab == 2)
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> NotesScreen(
                        notes = notes,
                        spaces = spaces,
                        currentSpaceId = currentSpaceId,
                        noteCounts = noteCounts,
                        mediaCounts = mediaCounts,
                        allMedia = media,
                        allArtifacts = allArtifacts,
                        mediaNoteCounts = mediaNoteCounts,
                        activeTagFilter = notesTagFilter,
                        onClearTagFilter = { notesTagFilter = null },
                        onNewNote = { composingNote = true },
                        onNewSpace = { namingNewSpace = true },
                        onOpenSpace = {
                            openSpaceLensId = null
                            openSpaceId = it.id
                        },
                        onOpenSettings = { openSettings = true },
                        onOpen = { openNoteId = it.id },
                        onOpenMedia = { openMediaId = it.id },
                        onOpenSpaceLens = { spaceId, lensId ->
                            openSpaceLensId = lensId
                            openSpaceId = spaceId
                        },
                        onDeleteNote = { target ->
                            scope.launch {
                                val snapshot = withContext(Dispatchers.IO) {
                                    NoteStore.delete(target.id)
                                    ReminderStore.cancelForTarget(context, ReminderTargetType.NOTE, target.id.toString())
                                    readSnapshot()
                                }
                                applySnapshot(snapshot)
                                if (openNoteId == target.id) openNoteId = null
                            }
                        }
                    )
                    1 -> SpacesScreen(
                        spaces = spaces,
                        noteCounts = noteCounts,
                        mediaCounts = mediaCounts,
                        insights = insights,
                        onNewSpace = { namingNewSpace = true },
                        onOpen = {
                            openSpaceLensId = null
                            openSpaceId = it.id
                        },
                        onEditRequest = { editingSpaceTarget = it },
                        onDeleteRequest = { deletingSpace = it }
                    )
                    2 -> {
                        val actionNotes = remember(notes) { notes.filter { it.mode == "Action" } }
                        val questionNotes = remember(notes) {
                            notes.filter {
                                it.openQuestions.isNotEmpty() ||
                                    it.question.isNotBlank() ||
                                    it.questionDrafts.isNotEmpty()
                            }
                        }
                        ActionsScreen(
                            actions = actionNotes,
                            questionNotes = questionNotes,
                            reminders = reminders,
                            spaces = spaces,
                            onOpen = { openNoteId = it.id },
                            onOpenNoteId = { openNoteId = it },
                            onOpenMediaId = { openMediaId = it },
                            onAcceptDraft = { noteId, draft ->
                                scope.launch {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        NoteStore.acceptQuestionDraft(noteId, draft)
                                        readSnapshot()
                                    }
                                    applySnapshot(snapshot)
                                }
                            },
                            onDiscardDraft = { noteId, draft ->
                                scope.launch {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        NoteStore.discardQuestionDraft(noteId, draft)
                                        readSnapshot()
                                    }
                                    applySnapshot(snapshot)
                                }
                            },
                            onDiscardAllDrafts = { noteId ->
                                scope.launch {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        NoteStore.discardAllQuestionDrafts(noteId)
                                        readSnapshot()
                                    }
                                    applySnapshot(snapshot)
                                }
                            },
                            onResolveQuestion = { noteId, qText ->
                                scope.launch {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        NoteStore.resolveOpenQuestion(noteId, qText)
                                        readSnapshot()
                                    }
                                    applySnapshot(snapshot)
                                }
                            },
                            onToggleComplete = { target, completed ->
                                scope.launch {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        NoteStore.setCompleted(target.id, completed)
                                        readSnapshot()
                                    }
                                    applySnapshot(snapshot)
                                    val res = snackbarHostState.showSnackbar(
                                        message = if (completed) actionCompletedToast else actionReopenedToast,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short
                                    )
                                    if (res == SnackbarResult.ActionPerformed) {
                                        val undoSnapshot = withContext(Dispatchers.IO) {
                                            NoteStore.setCompleted(target.id, !completed)
                                            readSnapshot()
                                        }
                                        applySnapshot(undoSnapshot)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // Expandable Full-Focus Editorial Capture Sheet
        AnimatedVisibility(
            visible = showCaptureSheet,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 3 }
        ) {
            FullFocusCaptureSheet(
                armed = armed,
                recording = recording,
                notifAccess = notifAccess,
                modelsReady = modelsReady,
                spaceName = spaces.firstOrNull { it.id == currentSpaceId }?.name,
                appendNoteTitle = appendNoteTitle,
                savingQuickNote = savingQuickJot,
                onPickSpace = { pickingCurrentSpace = true },
                onToggleRecord = toggleRecording,
                onCancelRecording = { CaptureService.cancelCurrentBurst() },
                onArm = { toggleArm(true) },
                onDisarm = { toggleArm(false) },
                onOpenNotifAccess = openNotifAccessSettings,
                onOpenNote = { noteId ->
                    Pipeline.reset()
                    if (noteId != null) openNoteId = noteId else tab = 0
                },
                onQuickJotSave = { text, chosenFormat ->
                    if (savingQuickJot) return@FullFocusCaptureSheet
                    savingQuickJot = true
                    scope.launch {
                        val (newId, snapshot) = withContext(Dispatchers.IO) {
                            val shape = chosenFormat.usesLlm && TransformModel.isInstalled(context.filesDir)
                            val (spaceNameForPrompt, spaceFocus) = SpaceStore.focusFor(currentSpaceId)
                            val fields = if (shape) {
                                Transform.apply(
                                    context.filesDir,
                                    text,
                                    chosenFormat,
                                    AppLocale.modelLanguage(context),
                                    spaceName = spaceNameForPrompt,
                                    spaceFocus = spaceFocus
                                )
                            } else {
                                null
                            }
                            val id = NoteStore.nextId()
                            NoteStore.add(
                                StoredNote(
                                    id = id,
                                    title = fields?.title?.takeIf { it.isNotBlank() }
                                        ?: derivedNoteTitle(text, untitledLabel),
                                    oneLine = fields?.oneLine ?: "",
                                    mode = fields?.mode ?: "Note",
                                    whenText = "Today " + SimpleDateFormat("HH:mm", Locale.US).format(Date()),
                                    verbatim = text.trim(),
                                    why = fields?.why ?: "",
                                    question = fields?.question ?: "",
                                    tags = fields?.tags ?: emptyList(),
                                    audioPath = null,
                                    spaceId = currentSpaceId,
                                    format = if (chosenFormat.usesLlm && fields == null) NoteFormat.PLAIN.id else chosenFormat.id,
                                    items = fields?.items ?: emptyList(),
                                    createdAt = System.currentTimeMillis(),
                                    questionDrafts = fields?.questionDrafts ?: emptyList()
                                )
                            )
                            id to readSnapshot()
                        }
                        applySnapshot(snapshot)
                        savingQuickJot = false
                        showCaptureSheet = false
                        tab = 0
                        openNoteId = newId
                    }
                },
                onOpenFullEditor = { composingNote = true },
                onDismiss = { showCaptureSheet = false }
            )
        }

        // Stacked navigation layers over mounted Scaffold
        AnimatedVisibility(
            visible = openSpace != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            openSpace?.let { space ->
                SpaceScreen(
                    space = space,
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
            }
        }

        AnimatedVisibility(
            visible = openNote != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            openNote?.let { note ->
                val noteReminder = remember(reminders, note.id) {
                    reminders.firstOrNull {
                        it.targetType == ReminderTargetType.NOTE && it.targetId == note.id.toString()
                    }
                }
                NoteDetailScreen(
                    note = note,
                    spaces = spaces,
                    allMedia = media,
                    reminder = noteReminder,
                    recordingFollowUp = recording && recordingAppendNoteId == note.id,
                    recordingBusyElsewhere = recording && recordingAppendNoteId != note.id,
                    onStartFollowUp = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            Pipeline.reset()
                            CaptureService.sendFollowUpBurst(context, note.id, "note detail")
                        } else {
                            pendingFollowUpNoteId = note.id
                            audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopFollowUp = {
                        CaptureService.trigger(false, "note detail stop")
                    },
                    onCancelFollowUp = {
                        CaptureService.cancelCurrentBurst()
                    },
                    onResolveQuestion = { qText ->
                        scope.launch {
                            val snapshot = withContext(Dispatchers.IO) {
                                NoteStore.resolveOpenQuestion(note.id, qText)
                                readSnapshot()
                            }
                            applySnapshot(snapshot)
                        }
                    },
                    onOpenMedia = { openMediaId = it.id },
                    onTagClick = { tag ->
                        openNoteId = null
                        openSpaceId = null
                        notesTagFilter = tag
                        tab = 0
                    },
                    onBack = { openNoteId = null },
                    onEdit = { editingNoteId = note.id },
                    onDelete = { noteId ->
                        openNoteId = null
                        scope.launch {
                            val snapshot = withContext(Dispatchers.IO) {
                                NoteStore.delete(noteId)
                                ReminderStore.cancelForTarget(context, ReminderTargetType.NOTE, noteId.toString())
                                readSnapshot()
                            }
                            applySnapshot(snapshot)
                        }
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = openSettings,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            SettingsScreen(
                armed = armed,
                recording = recording,
                notifAccess = notifAccess,
                micGranted = micGranted,
                postNotifsGranted = postNotifsGranted,
                onBack = { openSettings = false },
                onOpenNotifAccess = openNotifAccessSettings,
                onRequestMic = {
                    pendingAudioAction = null
                    audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                },
                onRequestPostNotifs = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppSettings()
                    }
                }
            )
        }

        AnimatedContent(
            targetState = when {
                editingNote != null -> editingNote
                composingNote -> "compose"
                else -> null
            },
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "modal editor overlay"
        ) { layer ->
            when (layer) {
                is NoteItem -> NoteEditorScreen(
                    note = layer,
                    onCancel = { editingNoteId = null },
                    onSave = { edited ->
                        editingNoteId = null
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
                "compose" -> NewNoteScreen(
                    spaces = spaces,
                    initialSpaceId = currentSpaceId,
                    onCancel = { composingNote = false },
                    onSaved = { newId ->
                        composingNote = false
                        tab = 0
                        scope.launch {
                            applySnapshot(withContext(Dispatchers.IO) { readSnapshot() })
                            openNoteId = newId
                        }
                    }
                )
            }
        }
    }

    val openMedia = media.firstOrNull { it.id == openMediaId }
    if (openMedia != null) {
        val openMediaSpaceNotes = remember(notes, openMedia.spaceId) {
            notes.filter { it.spaceId == openMedia.spaceId }
        }
        val mediaReminder = remember(reminders, openMedia.id) {
            reminders.firstOrNull {
                it.targetType == ReminderTargetType.MEDIA && it.targetId == openMedia.id
            }
        }
        MediaViewerDialog(
            media = openMedia,
            spaceNotes = openMediaSpaceNotes,
            reminder = mediaReminder,
            onOpenNote = { noteId ->
                openMediaId = null
                openNoteId = noteId
            },
            onStartVoiceNote = { item ->
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    Pipeline.reset()
                    CaptureService.sendBurstWithMedia(context, listOf(item.id), item.spaceId, "media viewer")
                    openSpaceId = null
                    openNoteId = null
                    tab = 0
                    showCaptureSheet = true
                } else {
                    pendingMediaVoiceId = item.id
                    pendingMediaVoiceSpaceId = item.spaceId
                    audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onDismiss = { openMediaId = null },
            onMediaChanged = {
                scope.launch { applySnapshot(withContext(Dispatchers.IO) { readSnapshot() }) }
            }
        )
    }

    if (pickingCurrentSpace) {
        SpacePickerDialog(
            title = stringResource(R.string.ui_space_picker_record_title),
            spaces = spaces,
            selectedId = currentSpaceId,
            onSelect = { spaceId ->
                pickingCurrentSpace = false
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
            existingSpaces = spaces,
            onDismiss = { namingNewSpace = false },
            onConfirm = { name ->
                namingNewSpace = false
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

    val spaceToEdit = editingSpaceTarget
    if (spaceToEdit != null) {
        SpaceEditorDialog(
            name = spaceToEdit.name,
            description = spaceToEdit.description,
            focusPrompt = spaceToEdit.focusPrompt,
            onDismiss = { editingSpaceTarget = null },
            onSave = { cleanName, cleanDesc, cleanFocus ->
                editingSpaceTarget = null
                scope.launch {
                    val snapshot = withContext(Dispatchers.IO) {
                        val fresh = SpaceStore.all().firstOrNull { it.id == spaceToEdit.id }
                        if (fresh != null) {
                            SpaceStore.update(
                                fresh.copy(
                                    name = cleanName,
                                    description = cleanDesc,
                                    focusPrompt = cleanFocus
                                )
                            )
                        }
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
            destructive = true,
            onConfirm = {
                deletingSpace = null
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
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialSerif),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = SignalText,
            selectedTextColor = SignalText,
            indicatorColor = SignalSoft,
            unselectedIconColor = Ink2,
            unselectedTextColor = Ink2
        )
    )
}

@Composable
private fun DeskTabIcon(selected: Boolean) {
    val tint = if (selected) SignalText else Ink2
    ComposeCanvas(Modifier.size(22.dp)) {
        val padX = 3.dp.toPx()
        val padY = 2.5.dp.toPx()
        val w = size.width - padX * 2f
        val h = size.height - padY * 2f
        val stroke = 1.8.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(padX, padY),
            size = Size(w, h),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        val lineLeft = padX + 3.dp.toPx()
        val lineRight = padX + w - 3.dp.toPx()
        drawLine(tint, Offset(lineLeft, padY + h * 0.32f), Offset(lineRight, padY + h * 0.32f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(lineLeft, padY + h * 0.56f), Offset(lineRight, padY + h * 0.56f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(lineLeft, padY + h * 0.78f), Offset(lineLeft + w * 0.45f, padY + h * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun SpacesTabIcon(selected: Boolean) {
    val tint = if (selected) SignalText else Ink2
    ComposeCanvas(Modifier.size(22.dp)) {
        val padY = 3.dp.toPx()
        val h = size.height - padY * 2f
        val stroke = 1.8.dp.toPx()
        val bookW = 4.2.dp.toPx()
        val gap = 2.2.dp.toPx()
        val startX = (size.width - (bookW * 3f + gap * 2f)) / 2f
        val r = CornerRadius(1.2.dp.toPx())
        for (i in 0 until 3) {
            val x = startX + i * (bookW + gap)
            val top = if (i == 1) padY + 2.dp.toPx() else padY
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, top),
                size = Size(bookW, size.height - padY - top),
                cornerRadius = r,
                style = Stroke(width = stroke)
            )
        }
    }
}

@Composable
private fun TriageTabIcon(selected: Boolean) {
    val tint = if (selected) SignalText else Ink2
    ComposeCanvas(Modifier.size(22.dp)) {
        val padX = 2.5.dp.toPx()
        val padY = 4.dp.toPx()
        val w = size.width - padX * 2f
        val h = size.height - padY * 2f
        val stroke = 1.8.dp.toPx()
        // Index card deck: back tab + front card
        drawLine(
            color = tint,
            start = Offset(padX + 2.dp.toPx(), padY - 1.5.dp.toPx()),
            end = Offset(padX + w * 0.55f, padY - 1.5.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(padX, padY + 1.dp.toPx()),
            size = Size(w, h - 1.dp.toPx()),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = tint,
            start = Offset(padX + 3.dp.toPx(), padY + h * 0.52f),
            end = Offset(padX + w - 3.dp.toPx(), padY + h * 0.52f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
