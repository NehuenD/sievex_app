package com.sievex.app.ui

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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

@Composable
internal fun ModelSetupCard() {
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
                    foreground = Color(0xFF00382F),
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

@Composable
internal fun RecordScreen(
    armed: Boolean,
    recording: Boolean,
    notifAccess: Boolean,
    modelsReady: Boolean,
    onToggle: () -> Unit,
    onCancelRecording: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onOpenNotifAccess: () -> Unit,
    onOpenNote: (Long?) -> Unit,
    spaceName: String?,
    onPickSpace: () -> Unit
) {
    val context = LocalContext.current
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

    LaunchedEffect(Pipeline.stage) {
        if (Pipeline.stage == PipelineStage.DONE) {
            delay(9000)
            Pipeline.reset()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        if (modelsReady) {
            // Subtle nocturnal cobalt atmospheric wash near the top edge.
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Blue.copy(alpha = 0.09f),
                            0.65f to Blue.copy(alpha = 0.02f),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, -size.height * 0.15f),
                        radius = size.width * 0.85f
                    )
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (!modelsReady) {
                ScreenHeader(
                    stringResource(R.string.ui_tab_record),
                    stringResource(R.string.ui_record_subtitle_setup)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
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
                                onClick = { Formats.select(context, NoteFormat.PLAIN) }
                            )
                        }
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

            EditorialRecordHeader(
                spaceName = spaceName,
                armed = armed,
                notifAccess = notifAccess,
                onPickSpace = onPickSpace,
                onToggleArm = { if (armed) onDisarm() else onArm() },
                onOpenNotifAccess = onOpenNotifAccess
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AmbientHaloRecordOrb(
                        recording = recording,
                        stage = Pipeline.stage,
                        onToggle = onToggle
                    )

                    Spacer(Modifier.height(28.dp))

                    when (Pipeline.stage) {
                        PipelineStage.TRANSCRIBING -> StatusCard(
                            title = stringResource(R.string.ui_record_stage_transcribing),
                            body = "",
                            accent = Blue
                        )
                        PipelineStage.TRANSFORMING -> StatusCard(
                            title = pipelineStepLabel(Pipeline.step).uppercase(Locale.getDefault()),
                            body = "",
                            accent = Mint
                        )
                        PipelineStage.DONE -> DoneCard(
                            title = Pipeline.lastTitle,
                            noteId = Pipeline.lastNoteId,
                            onOpenNote = onOpenNote,
                            onDismiss = { Pipeline.reset() }
                        )
                        PipelineStage.FAILED -> StatusCard(
                            title = stringResource(R.string.ui_record_stage_failed),
                            body = Pipeline.detail,
                            accent = Red,
                            showProgress = false,
                            onDismiss = { Pipeline.reset() }
                        )
                        PipelineStage.IDLE -> {
                            if (recording) {
                                Text(
                                    text = formatDuration(elapsed),
                                    style = MaterialTheme.typography.displaySmall.copy(
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = SignalText
                                )
                                Spacer(Modifier.height(14.dp))
                                Surface(
                                    onClick = onCancelRecording,
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, Line),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.ui_record_cancel),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Red
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .background(Blue.copy(alpha = 0.82f), CircleShape)
                                    )
                                    Text(
                                        text = stringResource(R.string.ui_record_tap_to_speak),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 0.1.sp
                                        ),
                                        color = Ink2
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FloatingFormatRail(
                selected = Formats.next,
                onSelect = { format -> Formats.select(context, format) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun EditorialRecordHeader(
    spaceName: String?,
    armed: Boolean,
    notifAccess: Boolean,
    onPickSpace: () -> Unit,
    onToggleArm: () -> Unit,
    onOpenNotifAccess: () -> Unit
) {
    val hasSpace = spaceName != null
    val resolvedSpace = spaceName ?: stringResource(R.string.ui_record_space_inbox)
    val spaceSelectorDesc = stringResource(R.string.ui_record_space_selector_desc, resolvedSpace)
    val triggerDesc = if (armed) {
        stringResource(R.string.ui_record_podcast_trigger_on)
    } else {
        stringResource(R.string.ui_record_podcast_trigger_off)
    }
    val notifHintFull = stringResource(R.string.ui_record_notif_hint)

    val badgeBg by animateColorAsState(
        targetValue = if (armed) BlueSoft else Surface1,
        animationSpec = tween(220),
        label = "podcastBadgeBg"
    )
    val badgeBorder by animateColorAsState(
        targetValue = if (armed) Blue.copy(alpha = 0.44f) else Line,
        animationSpec = tween(220),
        label = "podcastBadgeBorder"
    )
    val badgeInk by animateColorAsState(
        targetValue = if (armed) BlueText else Ink2,
        animationSpec = tween(220),
        label = "podcastBadgeInk"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                onClick = onPickSpace,
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = spaceSelectorDesc }
            ) {
                Column(
                    modifier = Modifier.padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(if (hasSpace) Blue else PlaceholderInk, CircleShape)
                        )
                        Text(
                            text = stringResource(R.string.ui_record_filing_into),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
                            color = if (hasSpace) Blue else Ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = resolvedSpace,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(7.dp))
                        Canvas(Modifier.size(width = 11.dp, height = 7.dp)) {
                            val strokePx = 1.6.dp.toPx()
                            val inset = strokePx / 2f
                            val path = Path().apply {
                                moveTo(inset, inset)
                                lineTo(size.width / 2f, size.height - inset)
                                lineTo(size.width - inset, inset)
                            }
                            drawPath(
                                path = path,
                                color = if (hasSpace) Blue else Ink2,
                                style = Stroke(
                                    width = strokePx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                onClick = onToggleArm,
                color = badgeBg,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, badgeBorder),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = triggerDesc }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PodcastSignalGlyph(
                        active = armed,
                        color = badgeInk,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (armed) {
                            stringResource(R.string.ui_record_podcast_armed_short)
                        } else {
                            stringResource(R.string.ui_record_podcast_short)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeInk,
                        maxLines = 1
                    )
                }
            }
        }

        if (armed && !notifAccess) {
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = onOpenNotifAccess,
                color = Amber.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = notifHintFull }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(Amber, CircleShape)
                    )
                    Text(
                        text = stringResource(R.string.ui_record_notif_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Canvas(Modifier.size(width = 6.dp, height = 10.dp)) {
                        val strokePx = 1.5.dp.toPx()
                        val inset = strokePx / 2f
                        val path = Path().apply {
                            moveTo(inset, inset)
                            lineTo(size.width - inset, size.height / 2f)
                            lineTo(inset, size.height - inset)
                        }
                        drawPath(
                            path = path,
                            color = Amber,
                            style = Stroke(
                                width = strokePx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
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

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)

        drawCircle(
            color = if (active) Blue else color,
            radius = 2.1.dp.toPx(),
            center = Offset(cx, cy)
        )

        val innerR = 4.6.dp.toPx()
        val outerR = 7.2.dp.toPx()

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
private fun AmbientHaloRecordOrb(
    recording: Boolean,
    stage: PipelineStage,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val processing = stage == PipelineStage.TRANSCRIBING || stage == PipelineStage.TRANSFORMING
    val label = when {
        processing -> stringResource(R.string.ui_record_processing)
        recording -> stringResource(R.string.ui_action_stop)
        else -> stringResource(R.string.ui_record_tap_to_record)
    }

    val orbDiameter = 148.dp
    val haloSize = 276.dp

    val targetAuraColor = when {
        recording -> Signal
        stage == PipelineStage.TRANSCRIBING -> Blue
        stage == PipelineStage.TRANSFORMING -> Mint
        stage == PipelineStage.FAILED -> Red
        else -> Signal
    }
    val auraColor by animateColorAsState(
        targetValue = targetAuraColor,
        animationSpec = tween(420),
        label = "auraColor"
    )
    val baseGlowAlpha by animateFloatAsState(
        targetValue = when {
            recording -> 0.30f
            processing -> 0.24f
            stage == PipelineStage.FAILED -> 0.16f
            else -> 0.14f
        },
        animationSpec = tween(420),
        label = "baseGlowAlpha"
    )

    val breathTransition = rememberInfiniteTransition(label = "ambientBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    val ripplePhase = if (recording || processing) {
        val rippleTransition = rememberInfiniteTransition(label = "orbRipples")
        val phase by rippleTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (recording) 2100 else 2800,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "ripplePhase"
        )
        phase
    } else {
        0f
    }

    val orbFill by animateColorAsState(
        targetValue = if (recording) Signal else Surface1,
        animationSpec = tween(260),
        label = "orbFill"
    )
    val orbBorder by animateColorAsState(
        targetValue = when {
            recording -> Signal.copy(alpha = 0.85f)
            stage == PipelineStage.TRANSCRIBING -> Blue.copy(alpha = 0.55f)
            stage == PipelineStage.TRANSFORMING -> Mint.copy(alpha = 0.55f)
            else -> Blue.copy(alpha = 0.38f)
        },
        animationSpec = tween(260),
        label = "orbBorder"
    )
    val orbAlpha by animateFloatAsState(
        targetValue = if (processing) 0.54f else 1f,
        animationSpec = tween(260),
        label = "orbAlpha"
    )
    val glyphSize by animateDpAsState(
        targetValue = if (recording) 40.dp else 44.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "glyphSize"
    )
    val glyphCorner by animateDpAsState(
        targetValue = if (recording) 10.dp else 22.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "glyphCorner"
    )
    val glyphColor by animateColorAsState(
        targetValue = when {
            recording -> Color.White
            stage == PipelineStage.TRANSCRIBING -> Blue.copy(alpha = 0.72f)
            stage == PipelineStage.TRANSFORMING -> Mint.copy(alpha = 0.72f)
            else -> Signal
        },
        animationSpec = tween(240),
        label = "glyphColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(haloSize)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val orbRadius = (orbDiameter / 2f).toPx()
            val glowAlpha = (baseGlowAlpha + (breath - 0.5f) * 0.05f).coerceIn(0f, 0.36f)
            val glowRadius = (size.minDimension * (0.45f + 0.045f * breath)).coerceAtLeast(orbRadius)

            val isIdleReady = !recording && !processing && stage != PipelineStage.FAILED
            val haloStops = if (isIdleReady) {
                arrayOf(
                    0.0f to Signal.copy(alpha = glowAlpha * 1.15f),
                    0.44f to Blue.copy(alpha = glowAlpha * 1.05f),
                    0.74f to Blue.copy(alpha = glowAlpha * 0.28f),
                    1.0f to Color.Transparent
                )
            } else {
                arrayOf(
                    0.0f to auraColor.copy(alpha = glowAlpha),
                    0.44f to auraColor.copy(alpha = glowAlpha * 0.50f),
                    0.74f to auraColor.copy(alpha = glowAlpha * 0.14f),
                    1.0f to Color.Transparent
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = haloStops,
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            if (!recording && !processing) {
                drawCircle(
                    color = Blue.copy(alpha = 0.20f + 0.10f * breath),
                    radius = orbRadius + 16.dp.toPx() + 3.dp.toPx() * breath,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            if (recording) {
                val spread = (size.minDimension / 2f - orbRadius).coerceAtLeast(24.dp.toPx())
                for (i in 0 until 3) {
                    val p = (ripplePhase + i / 3f) % 1f
                    val eased = 1f - (1f - p) * (1f - p)
                    val ringAlpha = (1f - p) * (1f - p) * 0.34f
                    drawCircle(
                        color = Signal.copy(alpha = ringAlpha),
                        radius = orbRadius + eased * spread,
                        style = Stroke(width = (1.8.dp - 0.8.dp * p).toPx())
                    )
                }
            } else if (processing) {
                val spread = (size.minDimension / 2f - orbRadius).coerceAtLeast(20.dp.toPx()) * 0.82f
                for (i in 0 until 2) {
                    val p = (ripplePhase + i / 2f) % 1f
                    val eased = 1f - (1f - p) * (1f - p)
                    val ringAlpha = (1f - p) * 0.24f
                    drawCircle(
                        color = auraColor.copy(alpha = ringAlpha),
                        radius = orbRadius + eased * spread,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
            }
        }

        Surface(
            onClick = onToggle,
            enabled = !processing,
            color = orbFill,
            shape = CircleShape,
            border = BorderStroke(1.dp, orbBorder),
            modifier = Modifier
                .size(orbDiameter)
                .alpha(orbAlpha)
                .semantics {
                    contentDescription = label
                    onClick(label = label, action = null)
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(glyphSize)
                        .clip(RoundedCornerShape(glyphCorner))
                        .background(glyphColor)
                )
            }
        }
    }
}

@Composable
private fun FloatingFormatRail(
    selected: NoteFormat,
    onSelect: (NoteFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    val formats = NoteFormat.entries
    val selectedIndex = formats.indexOf(selected).coerceAtLeast(0)

    Surface(
        color = Surface1,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Line),
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            val segmentWidth = maxWidth / formats.size
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "formatRailIndicator"
            )

            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .height(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(BlueSoft)
                    .border(1.dp, Blue.copy(alpha = 0.44f), RoundedCornerShape(999.dp))
            )

            Row(Modifier.fillMaxWidth()) {
                formats.forEach { format ->
                    val isSelected = format == selected
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) BlueText else Ink2,
                        animationSpec = tween(180),
                        label = "formatRailText"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(
                                role = Role.Tab,
                                onClick = { onSelect(format) }
                            )
                            .semantics { this.selected = isSelected }
                            .padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = formatLabel(format),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
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
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accent,
                    trackColor = Line
                )
            }
            if (onDismiss != null) {
                Spacer(Modifier.height(12.dp))
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
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .border(1.dp, Signal, RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_record_note_ready), style = MaterialTheme.typography.labelSmall, color = SignalText)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
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
internal fun SpaceChip(spaceName: String?, onClick: () -> Unit) {
    val active = spaceName != null
    Surface(
        onClick = onClick,
        color = if (active) SignalSoft else Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
                color = if (active) SignalText else Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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
