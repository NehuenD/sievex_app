package com.sievex.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sievex.app.CaptureService
import com.sievex.app.Formats
import com.sievex.app.NoteFormat
import com.sievex.app.Pipeline
import com.sievex.app.PipelineStage
import com.sievex.app.PipelineStep
import com.sievex.app.R
import com.sievex.app.asr.ModelInstaller
import com.sievex.app.llm.TransformModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ModelSetupCard() {
    val context = LocalContext.current
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Blue.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_speech_model), style = MaterialTheme.typography.labelSmall, color = BlueText)
            Spacer(Modifier.height(8.dp))
            Text(
                "${stringResource(R.string.ui_speech_model_blurb)} ${stringResource(R.string.ui_record_wifi_hint)}",
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
                Spacer(Modifier.height(10.dp))
                PillButton(
                    label = stringResource(R.string.ui_action_cancel),
                    background = Surface2,
                    foreground = Ink,
                    onClick = { ModelInstaller.cancel() }
                )
            } else {
                Spacer(Modifier.height(12.dp))
                PillButton(
                    label = stringResource(R.string.ui_speech_model_download),
                    background = SignalFill,
                    foreground = Color.White,
                    onClick = { ModelInstaller.download(context) }
                )
                if (ModelInstaller.failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(ModelInstaller.status, style = MaterialTheme.typography.bodySmall, color = Red)
                }
            }
        }
    }
}

@Composable
internal fun TransformSetupCard() {
    val context = LocalContext.current
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Mint.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_transform_model), style = MaterialTheme.typography.labelSmall, color = Mint)
            Spacer(Modifier.height(8.dp))
            Text(
                "${stringResource(R.string.ui_transform_model_blurb)} ${stringResource(R.string.ui_record_wifi_hint)}",
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
                Spacer(Modifier.height(10.dp))
                PillButton(
                    label = stringResource(R.string.ui_action_cancel),
                    background = Surface2,
                    foreground = Ink,
                    onClick = { TransformModel.cancel() }
                )
            } else {
                Spacer(Modifier.height(12.dp))
                PillButton(
                    label = stringResource(R.string.ui_transform_model_download),
                    background = Mint,
                    foreground = OnMint,
                    onClick = { TransformModel.download(context) }
                )
                if (TransformModel.failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(TransformModel.status, style = MaterialTheme.typography.bodySmall, color = Red)
                }
            }
        }
    }
}

/**
 * Slim editorial Capture Strip docked directly above the 3-tab bottom spine.
 */
@Composable
internal fun CaptureStrip(
    spaceName: String?,
    armed: Boolean,
    recording: Boolean,
    notifAccess: Boolean,
    modelsReady: Boolean,
    onPickSpace: () -> Unit,
    onOpenSheet: () -> Unit,
    onQuickRecord: () -> Unit
) {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        if (!recording) {
            elapsed = 0
            return@LaunchedEffect
        }
        val base = CaptureService.recordingStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        while (true) {
            val spentMs = (System.currentTimeMillis() - base).coerceAtLeast(0L)
            val sec = (spentMs / 1000L).toInt()
            elapsed = sec
            val nextSecMs = (sec + 1) * 1000L
            delay((nextSecMs - spentMs).coerceAtLeast(16L))
        }
    }

    val stage = Pipeline.stage
    val resolvedSpace = spaceName ?: stringResource(R.string.ui_record_space_inbox)
    val processing = stage == PipelineStage.TRANSCRIBING || stage == PipelineStage.TRANSFORMING

    Surface(
        color = Surface1,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = Line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onOpenSheet() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Active Space serif pill
                Surface(
                    onClick = onPickSpace,
                    color = if (spaceName != null) BlueSoft else Surface2,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, if (spaceName != null) Blue.copy(alpha = 0.42f) else Line),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .widthIn(max = 112.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = resolvedSpace,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = EditorialSerif),
                            color = if (spaceName != null) BlueText else Ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Quick tap area showing live status or prompt
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    val promptText = when {
                        recording -> stringResource(R.string.ui_capture_strip_recording, formatDuration(elapsed))
                        stage == PipelineStage.TRANSCRIBING -> stringResource(R.string.ui_record_stage_transcribing)
                        stage == PipelineStage.TRANSFORMING -> pipelineStepLabel(Pipeline.step)
                        stage == PipelineStage.DONE -> Pipeline.lastTitle.ifBlank { stringResource(R.string.ui_record_note_ready) }
                        !modelsReady -> stringResource(R.string.ui_record_subtitle_setup)
                        else -> stringResource(R.string.ui_capture_strip_prompt)
                    }
                    val promptColor = when {
                        recording -> SignalText
                        processing -> BlueText
                        stage == PipelineStage.DONE -> Mint
                        else -> Ink2
                    }
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (recording) EditorialMono else MaterialTheme.typography.bodySmall.fontFamily
                        ),
                        color = promptColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Inline Podcast arm badge when armed
                if (armed) {
                    Surface(
                        onClick = onOpenSheet,
                        color = if (notifAccess) BlueSoft else Amber.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, if (notifAccess) Blue.copy(alpha = 0.42f) else Amber.copy(alpha = 0.45f)),
                        modifier = Modifier.heightIn(min = 34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PodcastSignalGlyph(
                                active = true,
                                color = if (notifAccess) BlueText else Amber,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(R.string.ui_record_podcast_armed_short),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (notifAccess) BlueText else Amber,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Terracotta REC / STOP pill
                Surface(
                    onClick = {
                        if (!modelsReady) onOpenSheet() else onQuickRecord()
                    },
                    enabled = !processing,
                    color = if (recording) RedFill else SignalFill,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .alpha(if (processing) 0.5f else 1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(if (recording) RoundedCornerShape(2.dp) else CircleShape)
                                .background(Color.White)
                        )
                        Text(
                            text = if (recording) stringResource(R.string.ui_action_stop).uppercase()
                            else stringResource(R.string.ui_capture_rec_pill),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = EditorialMono,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-Focus Editorial Capture Sheet modal overlay.
 */
@Composable
internal fun FullFocusCaptureSheet(
    armed: Boolean,
    recording: Boolean,
    notifAccess: Boolean,
    modelsReady: Boolean,
    spaceName: String?,
    appendNoteTitle: String?,
    savingQuickNote: Boolean,
    onPickSpace: () -> Unit,
    onToggleRecord: () -> Unit,
    onCancelRecording: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onOpenNotifAccess: () -> Unit,
    onOpenNote: (Long?) -> Unit,
    onQuickJotSave: (text: String, format: NoteFormat) -> Unit,
    onOpenFullEditor: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var elapsed by remember { mutableIntStateOf(0) }
    var jotText by rememberSaveable { mutableStateOf("") }
    var jotExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler { onDismiss() }

    LaunchedEffect(recording) {
        if (!recording) {
            elapsed = 0
            return@LaunchedEffect
        }
        val base = CaptureService.recordingStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        while (true) {
            val spentMs = (System.currentTimeMillis() - base).coerceAtLeast(0L)
            val sec = (spentMs / 1000L).toInt()
            elapsed = sec
            val nextSecMs = (sec + 1) * 1000L
            delay((nextSecMs - spentMs).coerceAtLeast(16L))
        }
    }

    LaunchedEffect(Pipeline.stage) {
        if (Pipeline.stage == PipelineStage.DONE) {
            delay(9000)
            Pipeline.reset()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = ScrimAlpha))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = if (IsDarkTheme) Surface1 else Canvas,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, LineStrong),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp)
            ) {
                // Sheet handle + top editorial header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Line)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ui_capture_sheet_kicker),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = EditorialMono,
                                letterSpacing = 1.2.sp
                            ),
                            color = SignalText
                        )
                        Text(
                            text = stringResource(R.string.ui_capture_sheet_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Ink
                        )
                    }
                    // Podcast arm toggle pill
                    Surface(
                        onClick = { if (armed) onDisarm() else onArm() },
                        color = if (armed) BlueSoft else Surface1,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, if (armed) Blue.copy(alpha = 0.44f) else Line),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PodcastSignalGlyph(
                                active = armed,
                                color = if (armed) BlueText else Ink2,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (armed) {
                                    stringResource(R.string.ui_record_podcast_armed_short)
                                } else {
                                    stringResource(R.string.ui_record_podcast_short)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (armed) BlueText else Ink2
                            )
                        }
                    }
                    CloseIconButton(onClick = onDismiss)
                }
                HorizontalDivider(thickness = 1.dp, color = Line)

                if (armed && !notifAccess) {
                    Surface(
                        onClick = onOpenNotifAccess,
                        color = Amber.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Amber.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ui_record_notif_short),
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                if (!modelsReady) {
                    Spacer(Modifier.height(14.dp))
                    if (!ModelInstaller.installed) ModelSetupCard()
                    if (!TransformModel.installed) {
                        if (!ModelInstaller.installed) Spacer(Modifier.height(12.dp))
                        TransformSetupCard()
                    }
                    if (ModelInstaller.installed && !TransformModel.installed) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            PillButton(
                                label = stringResource(R.string.ui_record_use_plain),
                                background = Surface2,
                                foreground = Ink,
                                border = BorderStroke(1.dp, Line),
                                onClick = { Formats.select(context, NoteFormat.PLAIN) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.ui_record_setup_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    FormatPicker()
                    Spacer(Modifier.height(16.dp))
                    // Still allow quick text margin note even before speech model download
                    QuickJotComposer(
                        jotText = jotText,
                        onJotChange = { jotText = it },
                        expanded = true,
                        onExpandToggle = {},
                        saving = savingQuickNote,
                        onSave = {
                            val clean = jotText.trim()
                            if (clean.isNotEmpty()) {
                                onQuickJotSave(clean, Formats.next)
                                jotText = ""
                            }
                        },
                        onOpenFullEditor = {
                            onDismiss()
                            onOpenFullEditor()
                        }
                    )
                    return@Column
                }

                // Append Follow-up banner when recording a follow-up to an existing note
                if (recording && appendNoteTitle != null) {
                    Surface(
                        color = SignalSoft,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Signal.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = stringResource(R.string.ui_followup_recording).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalText
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = appendNoteTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialSerif),
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Space selector row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ui_record_filing_into),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink2
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = spaceName ?: stringResource(R.string.ui_record_space_inbox),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialSerif),
                            color = if (spaceName != null) BlueText else Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    PillButton(
                        label = stringResource(R.string.ui_action_change),
                        background = Surface1,
                        foreground = Ink,
                        border = BorderStroke(1.dp, Line),
                        onClick = onPickSpace
                    )
                }

                // Hero plate: editorial artwork (when idle) + waveform + timer + record control
                Surface(
                    color = if (IsDarkTheme) Surface2 else Surface1,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (recording) Signal.copy(alpha = 0.60f) else Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!recording && Pipeline.stage == PipelineStage.IDLE) {
                            EditorialArtwork(
                                resId = R.drawable.art_onboard_1,
                                contentDescription = stringResource(R.string.ui_capture_sheet_title),
                                modifier = Modifier
                                    .size(96.dp)
                                    .padding(bottom = 8.dp)
                            )
                        }

                        if (recording) {
                            Text(
                                text = formatDuration(elapsed),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = EditorialMono,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = SignalText
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        EditorialInkWaveform(
                            recording = recording,
                            stage = Pipeline.stage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        )

                        Spacer(Modifier.height(14.dp))

                        val processing = Pipeline.stage == PipelineStage.TRANSCRIBING ||
                            Pipeline.stage == PipelineStage.TRANSFORMING

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (recording) {
                                PillButton(
                                    label = stringResource(R.string.ui_record_cancel),
                                    background = Surface2,
                                    foreground = Red,
                                    border = BorderStroke(1.dp, Line),
                                    onClick = onCancelRecording
                                )
                            }
                            Surface(
                                onClick = onToggleRecord,
                                enabled = !processing,
                                color = if (recording) RedFill else SignalFill,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier
                                    .heightIn(min = 50.dp)
                                    .alpha(if (processing) 0.55f else 1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .clip(if (recording) RoundedCornerShape(3.dp) else CircleShape)
                                            .background(Color.White)
                                    )
                                    Text(
                                        text = when {
                                            processing -> stringResource(R.string.ui_record_processing)
                                            recording -> stringResource(R.string.ui_action_stop)
                                            else -> stringResource(R.string.ui_record_tap_to_record)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Pipeline feedback inside sheet
                when (Pipeline.stage) {
                    PipelineStage.TRANSCRIBING -> {
                        Spacer(Modifier.height(12.dp))
                        StatusCard(
                            title = stringResource(R.string.ui_record_stage_transcribing),
                            body = "",
                            accent = Blue
                        )
                    }
                    PipelineStage.TRANSFORMING -> {
                        Spacer(Modifier.height(12.dp))
                        StatusCard(
                            title = pipelineStepLabel(Pipeline.step).uppercase(Locale.getDefault()),
                            body = "",
                            accent = Mint
                        )
                    }
                    PipelineStage.DONE -> {
                        Spacer(Modifier.height(12.dp))
                        DoneCard(
                            title = Pipeline.lastTitle,
                            noteId = Pipeline.lastNoteId,
                            onOpenNote = { id ->
                                onDismiss()
                                onOpenNote(id)
                            },
                            onDismiss = { Pipeline.reset() }
                        )
                    }
                    PipelineStage.FAILED -> {
                        Spacer(Modifier.height(12.dp))
                        StatusCard(
                            title = stringResource(R.string.ui_record_stage_failed),
                            body = Pipeline.detail,
                            accent = Red,
                            showProgress = false,
                            onDismiss = { Pipeline.reset() }
                        )
                    }
                    PipelineStage.IDLE -> Unit
                }

                // Inline serif Format chips
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ui_section_format),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink2,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NoteFormat.entries.forEach { format ->
                        val selected = format == Formats.next
                        Surface(
                            onClick = { Formats.select(context, format) },
                            color = if (selected) SignalSoft else Surface1,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, if (selected) Signal.copy(alpha = 0.5f) else Line),
                            modifier = Modifier.heightIn(min = 42.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = formatLabel(format),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = EditorialSerif,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (selected) SignalText else Ink2
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatBlurb(Formats.next),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                // Instant "Jot a margin note" quick composer
                Spacer(Modifier.height(16.dp))
                QuickJotComposer(
                    jotText = jotText,
                    onJotChange = { jotText = it },
                    expanded = jotExpanded,
                    onExpandToggle = { jotExpanded = !jotExpanded },
                    saving = savingQuickNote,
                    onSave = {
                        val clean = jotText.trim()
                        if (clean.isNotEmpty()) {
                            onQuickJotSave(clean, Formats.next)
                            jotText = ""
                            jotExpanded = false
                        }
                    },
                    onOpenFullEditor = {
                        onDismiss()
                        onOpenFullEditor()
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickJotComposer(
    jotText: String,
    onJotChange: (String) -> Unit,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
    onOpenFullEditor: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Surface(
            onClick = onExpandToggle,
            color = Surface1,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Line),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ui_capture_jot_title),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = EditorialSerif),
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.ui_capture_full_editor),
                        style = MaterialTheme.typography.labelSmall,
                        color = BlueText,
                        modifier = Modifier
                            .clickable(enabled = !saving) { onOpenFullEditor() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                AnimatedVisibility(visible = expanded || jotText.isNotBlank()) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        EditorField(
                            label = "",
                            value = jotText,
                            placeholder = stringResource(R.string.ui_capture_jot_hint),
                            onChange = onJotChange,
                            minHeight = 76.dp,
                            enabled = !saving
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            val canSave = jotText.isNotBlank() && !saving
                            PillButton(
                                label = if (saving) stringResource(R.string.ui_new_note_saving)
                                else stringResource(R.string.ui_action_save),
                                background = if (canSave) SignalFill else Surface2,
                                foreground = if (canSave) Color.White else Ink2,
                                enabled = canSave,
                                onClick = onSave
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Animated editorial ink waveform drawn with Compose Canvas (no @Composable palette reads inside DrawScope).
 */
@Composable
private fun EditorialInkWaveform(
    recording: Boolean,
    stage: PipelineStage,
    modifier: Modifier = Modifier
) {
    val active = recording || stage == PipelineStage.TRANSCRIBING || stage == PipelineStage.TRANSFORMING
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "inkWaveform")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (recording) 1400 else 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wavePhase"
        )
        p
    } else {
        0f
    }

    val barColor = when {
        recording -> Signal
        stage == PipelineStage.TRANSCRIBING -> Blue
        stage == PipelineStage.TRANSFORMING -> Mint
        else -> Ink2.copy(alpha = if (IsDarkTheme) 0.55f else 0.45f)
    }
    val ruleColor = Line

    Canvas(modifier) {
        val midY = size.height / 2f
        drawLine(
            color = ruleColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx()
        )
        val count = 34
        val step = size.width / count
        val barWidth = (step * 0.46f).coerceAtLeast(2.dp.toPx())
        for (i in 0 until count) {
            val norm = i.toFloat() / count.toFloat()
            val envelope = sin(norm * PI).toFloat().coerceAtLeast(0.15f)
            val amp = if (active) {
                val wave = 0.5f + 0.5f * sin(phase + i * 0.55f)
                (0.18f + 0.78f * wave) * envelope
            } else {
                0.14f + 0.16f * envelope
            }
            val barH = (size.height * amp).coerceAtLeast(3.dp.toPx())
            val x = i * step + (step - barWidth) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun PodcastSignalGlyph(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val outerAlpha = if (active) {
        val pulse = rememberInfiniteTransition(label = "podcastPulse")
        val value by pulse.animateFloat(
            initialValue = 0.40f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "podcastOuterAlpha"
        )
        value
    } else {
        0.55f
    }

    val dotColor = if (active) Blue else color
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)

        drawCircle(
            color = dotColor,
            radius = 2.dp.toPx(),
            center = Offset(cx, cy)
        )

        val innerR = 4.2.dp.toPx()
        val outerR = 6.6.dp.toPx()

        for (startAngle in floatArrayOf(-42f, 138f)) {
            drawArc(
                color = color.copy(alpha = if (active) 0.9f else 0.75f),
                startAngle = startAngle,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(cx - innerR, cy - innerR),
                size = Size(innerR * 2f, innerR * 2f),
                style = stroke
            )
            drawArc(
                color = color.copy(alpha = outerAlpha),
                startAngle = startAngle,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(cx - outerR, cy - outerR),
                size = Size(outerR * 2f, outerR * 2f),
                style = stroke
            )
        }
    }
}

@Composable
internal fun pipelineStepLabel(step: PipelineStep?): String = when (step) {
    PipelineStep.DICTATION -> stringResource(R.string.step_dictation)
    PipelineStep.TITLE -> stringResource(R.string.step_title)
    PipelineStep.SUMMARY -> stringResource(R.string.step_summary)
    PipelineStep.ITEMS -> stringResource(R.string.step_items)
    PipelineStep.STEPS -> stringResource(R.string.step_steps)
    PipelineStep.INSIGHTS -> stringResource(R.string.step_insights)
    PipelineStep.QUESTIONS -> stringResource(R.string.step_questions)
    PipelineStep.TAGS -> stringResource(R.string.step_tags)
    PipelineStep.SAVING -> stringResource(R.string.step_saving)
    PipelineStep.INTENT, null -> stringResource(R.string.step_intent)
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    accent: Color,
    showProgress: Boolean = true,
    onDismiss: (() -> Unit)? = null
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = Ink2)
            }
            if (showProgress) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accent,
                    trackColor = Line
                )
            }
            if (onDismiss != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton(
                        label = stringResource(R.string.ui_action_dismiss),
                        background = Surface2,
                        foreground = Ink,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneCard(
    title: String,
    noteId: Long?,
    onOpenNote: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = SignalSoft,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Signal.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.ui_record_note_ready), style = MaterialTheme.typography.labelSmall, color = SignalText)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialSerif),
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = stringResource(R.string.ui_record_view_in_notes),
                    background = SignalFill,
                    foreground = Color.White,
                    onClick = { onOpenNote(noteId) }
                )
                PillButton(
                    label = stringResource(R.string.ui_action_dismiss),
                    background = Surface2,
                    foreground = Ink,
                    onClick = onDismiss
                )
            }
        }
    }
}

internal fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

@Composable
internal fun FormatChips(
    selected: NoteFormat,
    onSelect: (NoteFormat) -> Unit,
    horizontalPadding: Dp = 0.dp,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NoteFormat.entries.forEach { format ->
            SubTab(
                label = formatLabel(format),
                selected = format == selected,
                enabled = enabled
            ) { onSelect(format) }
        }
    }
}

@Composable
internal fun FormatPicker() {
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
        Spacer(Modifier.height(6.dp))
        FormatChips(
            Formats.next,
            { format -> Formats.select(context, format) },
            horizontalPadding = 20.dp
        )
    }
}
