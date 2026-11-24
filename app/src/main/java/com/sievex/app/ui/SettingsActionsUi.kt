package com.sievex.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sievex.app.AppLocale
import com.sievex.app.CaptureLog
import com.sievex.app.R
import com.sievex.app.Reminder
import com.sievex.app.ReminderStore
import com.sievex.app.ReminderTargetType
import com.sievex.app.Space
import com.sievex.app.asr.ModelInstaller
import com.sievex.app.llm.TransformModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class ActionsSubTab { QUESTIONS, ACTIONS, REMINDERS }

/**
 * Triage Tab: Archival Index-Card Deck for Question Drafts, Open Questions, Actions, and Reminders.
 */
@Composable
internal fun ActionsScreen(
    actions: List<NoteItem>,
    questionNotes: List<NoteItem> = emptyList(),
    reminders: List<Reminder> = emptyList(),
    spaces: List<Space>,
    onOpen: (NoteItem) -> Unit,
    onOpenNoteId: (Long) -> Unit = {},
    onOpenMediaId: (String) -> Unit = {},
    onAcceptDraft: (Long, String) -> Unit = { _, _ -> },
    onDiscardDraft: (Long, String) -> Unit = { _, _ -> },
    onDiscardAllDrafts: (Long) -> Unit = {},
    onResolveQuestion: (Long, String) -> Unit = { _, _ -> },
    onToggleComplete: (NoteItem, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember(actions) { actions.filter { it.completedAt == null } }
    val completed = remember(actions) { actions.filter { it.completedAt != null } }
    val draftNotes = remember(questionNotes) { questionNotes.filter { it.questionDrafts.isNotEmpty() } }
    val totalDrafts = remember(draftNotes) { draftNotes.sumOf { it.questionDrafts.size } }
    val openQuestionEntries = remember(questionNotes) {
        questionNotes.flatMap { note ->
            val qs = note.openQuestions.ifEmpty {
                listOfNotNull(note.question.trim().takeIf { it.isNotEmpty() })
            }
            qs.map { q -> note to q }
        }
    }
    val totalQuestionItems = openQuestionEntries.size + totalDrafts
    val spaceNames = remember(spaces) { spaces.associate { it.id to it.name } }
    val noteReminders = remember(reminders) {
        reminders.filter { it.targetType == ReminderTargetType.NOTE }.associateBy { it.targetId }
    }
    var subTab by rememberSaveable { mutableStateOf(ActionsSubTab.QUESTIONS) }
    var showCompleted by remember { mutableStateOf(false) }
    var reminderTarget by remember { mutableStateOf<NoteItem?>(null) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(contentType = "header") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ui_tab_triage),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Ink
                        )
                        Spacer(Modifier.height(4.dp))
                        val subtitle = when (subTab) {
                            ActionsSubTab.QUESTIONS -> when {
                                openQuestionEntries.isNotEmpty() && totalDrafts > 0 ->
                                    stringResource(R.string.ui_questions_subtitle_mixed, openQuestionEntries.size, totalDrafts)
                                totalDrafts > 0 -> if (totalDrafts == 1) {
                                    stringResource(R.string.ui_questions_subtitle_draft_one)
                                } else {
                                    stringResource(R.string.ui_questions_subtitle_drafts_only, totalDrafts)
                                }
                                openQuestionEntries.size == 1 -> stringResource(R.string.ui_questions_count_one)
                                else -> stringResource(R.string.ui_questions_count_many, openQuestionEntries.size)
                            }
                            ActionsSubTab.ACTIONS -> if (pending.size == 1) stringResource(R.string.ui_actions_pending_one)
                                else stringResource(R.string.ui_actions_pending, pending.size)
                            ActionsSubTab.REMINDERS -> if (reminders.size == 1) stringResource(R.string.ui_reminders_count_one)
                                else stringResource(R.string.ui_reminders_count_many, reminders.size)
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink2)
                    }
                    Spacer(Modifier.width(12.dp))
                    EditorialArtwork(
                        resId = R.drawable.art_onboard_3,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubTab(
                        label = "${stringResource(R.string.ui_actions_tab_questions)} ($totalQuestionItems)",
                        selected = subTab == ActionsSubTab.QUESTIONS
                    ) { subTab = ActionsSubTab.QUESTIONS }
                    SubTab(
                        label = "${stringResource(R.string.ui_tab_actions)} (${pending.size})",
                        selected = subTab == ActionsSubTab.ACTIONS
                    ) { subTab = ActionsSubTab.ACTIONS }
                    SubTab(
                        label = "${stringResource(R.string.ui_actions_tab_reminders)} (${reminders.size})",
                        selected = subTab == ActionsSubTab.REMINDERS
                    ) { subTab = ActionsSubTab.REMINDERS }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = Line)
            }
        }

        when (subTab) {
            ActionsSubTab.QUESTIONS -> {
                if (draftNotes.isEmpty() && openQuestionEntries.isEmpty()) {
                    item(contentType = "questions_empty") {
                        TriageEmptyPlate(stringResource(R.string.ui_questions_empty))
                    }
                }
                if (draftNotes.isNotEmpty()) {
                    item(contentType = "question_drafts_header") {
                        Text(
                            stringResource(R.string.ui_questions_drafts_section).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                            color = BlueText,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp)
                        )
                    }
                    items(draftNotes, key = { "qd_${it.id}" }, contentType = { "question_draft_group" }) { note ->
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            ArchivalIndexCard(
                                tabColor = Blue,
                                stampLabel = stringResource(R.string.ui_triage_stamp_draft),
                                onClick = { onOpen(note) }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            note.title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialSerif),
                                            color = Ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val sp = note.spaceId?.let { spaceNames[it] }
                                        Text(
                                            if (sp != null) "${whenLabel(note.whenText)} · $sp" else whenLabel(note.whenText),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                                            color = Ink2,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (note.questionDrafts.size > 1) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            onClick = { onDiscardAllDrafts(note.id) },
                                            color = Surface2,
                                            shape = RoundedCornerShape(999.dp),
                                            border = BorderStroke(1.dp, Line),
                                            modifier = Modifier.heightIn(min = 36.dp)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    stringResource(R.string.ui_question_discard_all),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Ink2
                                                )
                                            }
                                        }
                                    }
                                }
                                note.questionDrafts.forEach { draft ->
                                    Surface(
                                        color = Surface2,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, Line),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                draft,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Ink
                                            )
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                Surface(
                                                    onClick = { onDiscardDraft(note.id, draft) },
                                                    color = Surface1,
                                                    shape = RoundedCornerShape(999.dp),
                                                    border = BorderStroke(1.dp, Line),
                                                    modifier = Modifier.heightIn(min = 40.dp)
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                    ) {
                                                        Text(
                                                            stringResource(R.string.ui_question_discard),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Ink2
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    onClick = { onAcceptDraft(note.id, draft) },
                                                    color = MintSoft,
                                                    shape = RoundedCornerShape(999.dp),
                                                    border = BorderStroke(1.dp, Mint.copy(alpha = 0.50f)),
                                                    modifier = Modifier.heightIn(min = 40.dp)
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                    ) {
                                                        Text(
                                                            stringResource(R.string.ui_question_keep),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Mint
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (openQuestionEntries.isNotEmpty()) {
                    if (draftNotes.isNotEmpty()) {
                        item(contentType = "question_open_header") {
                            Text(
                                stringResource(R.string.ui_questions_open_section).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                                color = Amber,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
                            )
                        }
                    }
                    items(
                        openQuestionEntries,
                        key = { (note, q) -> "q_${note.id}_${q.hashCode()}" },
                        contentType = { "question_row" }
                    ) { (note, qText) ->
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            ArchivalIndexCard(
                                tabColor = Amber,
                                stampLabel = stringResource(R.string.ui_triage_stamp_open),
                                onClick = { onOpen(note) }
                            ) {
                                Text(
                                    qText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Ink
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            note.title,
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = EditorialSerif),
                                            color = Ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val sp = note.spaceId?.let { spaceNames[it] }
                                        Text(
                                            if (sp != null) "${whenLabel(note.whenText)} · $sp" else whenLabel(note.whenText),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                                            color = Ink2,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    PillButton(
                                        label = stringResource(R.string.ui_question_resolve),
                                        background = MintSoft,
                                        foreground = Mint,
                                        border = BorderStroke(1.dp, Mint.copy(alpha = 0.45f)),
                                        onClick = { onResolveQuestion(note.id, qText) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ActionsSubTab.ACTIONS -> {
                if (pending.isEmpty()) {
                    item(contentType = "empty") {
                        TriageEmptyPlate(stringResource(R.string.ui_actions_empty))
                    }
                }
                items(pending, key = { it.id }, contentType = { "action_pending" }) { note ->
                    ActionIndexCard(
                        note = note,
                        spaceName = note.spaceId?.let { spaceNames[it] },
                        reminder = noteReminders[note.id.toString()],
                        completed = false,
                        onOpen = { onOpen(note) },
                        onToggle = { onToggleComplete(note, true) },
                        onRemind = { reminderTarget = note }
                    )
                }
                if (completed.isNotEmpty()) {
                    item(contentType = "completed_toggle") {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            Surface(
                                onClick = { showCompleted = !showCompleted },
                                color = Surface2,
                                shape = RoundedCornerShape(8.dp),
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
                                        stringResource(R.string.ui_actions_completed_header, completed.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Ink2,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (showCompleted) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Ink2
                                    )
                                }
                            }
                        }
                    }
                    if (showCompleted) {
                        items(completed, key = { it.id }, contentType = { "action_done" }) { note ->
                            ActionIndexCard(
                                note = note,
                                spaceName = note.spaceId?.let { spaceNames[it] },
                                reminder = null,
                                completed = true,
                                onOpen = { onOpen(note) },
                                onToggle = { onToggleComplete(note, false) },
                                onRemind = null
                            )
                        }
                    }
                }
            }

            ActionsSubTab.REMINDERS -> {
                if (reminders.isEmpty()) {
                    item(contentType = "reminders_empty") {
                        TriageEmptyPlate(stringResource(R.string.ui_reminders_empty))
                    }
                }
                items(reminders, key = { it.id }, contentType = { "reminder_row" }) { r ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        ArchivalIndexCard(
                            tabColor = Signal,
                            stampLabel = stringResource(R.string.ui_triage_stamp_reminder),
                            onClick = {
                                when (r.targetType) {
                                    ReminderTargetType.NOTE -> r.targetId.toLongOrNull()?.let { onOpenNoteId(it) }
                                    ReminderTargetType.MEDIA -> onOpenMediaId(r.targetId)
                                }
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            formatReminderWhen(r.triggerAtMs),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                                            color = BlueText
                                        )
                                        Text(
                                            "· " + stringResource(
                                                if (r.targetType == ReminderTargetType.NOTE) R.string.ui_note_label
                                                else R.string.ui_search_screenshot_badge
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Ink2
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        r.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialSerif),
                                        color = Ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                PillButton(
                                    label = stringResource(R.string.ui_reminder_clear),
                                    background = Surface2,
                                    foreground = Red,
                                    border = BorderStroke(1.dp, Line),
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            ReminderStore.cancelById(context, r.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val target = reminderTarget
    if (target != null) {
        val existing = noteReminders[target.id.toString()]
        ReminderPickerDialog(
            title = target.title,
            existing = existing,
            onSchedule = { triggerAtMs ->
                reminderTarget = null
                scope.launch(Dispatchers.IO) {
                    ReminderStore.schedule(
                        context = context,
                        type = ReminderTargetType.NOTE,
                        targetId = target.id.toString(),
                        title = target.title,
                        triggerAtMs = triggerAtMs
                    )
                }
                Toast.makeText(context, R.string.ui_reminder_saved_toast, Toast.LENGTH_SHORT).show()
            },
            onClear = if (existing != null) {
                {
                    reminderTarget = null
                    scope.launch(Dispatchers.IO) {
                        ReminderStore.cancelForTarget(context, ReminderTargetType.NOTE, target.id.toString())
                    }
                    Toast.makeText(context, R.string.ui_reminder_cleared_toast, Toast.LENGTH_SHORT).show()
                }
            } else null,
            onDismiss = { reminderTarget = null }
        )
    }
}

@Composable
private fun TriageEmptyPlate(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EditorialArtwork(
            resId = R.drawable.art_onboard_3,
            contentDescription = null,
            modifier = Modifier.size(116.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink2
        )
    }
}

/**
 * Archival Index Card with crisp 8dp border, top index tab line, and stamp-style badge.
 */
@Composable
private fun ArchivalIndexCard(
    tabColor: Color,
    stampLabel: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Surface1,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Top index tab line
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(tabColor)
            )
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(tabColor.copy(alpha = if (IsDarkTheme) 0.14f else 0.07f))
                            .border(1.dp, tabColor.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stampLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = EditorialMono,
                                fontSize = 10.sp,
                                letterSpacing = 1.1.sp
                            ),
                            color = tabColor
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                content()
            }
        }
    }
}

@Composable
private fun ActionIndexCard(
    note: NoteItem,
    spaceName: String?,
    reminder: Reminder?,
    completed: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRemind: (() -> Unit)?
) {
    Box(Modifier.padding(horizontal = 20.dp)) {
        ArchivalIndexCard(
            tabColor = if (completed) Ink2 else Mint,
            stampLabel = stringResource(R.string.ui_triage_stamp_action),
            onClick = onOpen
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = completed,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Mint,
                            uncheckedColor = Mint,
                            checkmarkColor = OnMint
                        )
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = EditorialSerif,
                            textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        color = if (completed) Ink2 else Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (note.oneLine.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            note.oneLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            whenLabel(note.whenText),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                            color = Ink2
                        )
                        if (spaceName != null) {
                            Text(
                                "· $spaceName",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (reminder != null) {
                            Text(
                                "· ${formatReminderWhen(reminder.triggerAtMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BlueText,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (onRemind != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = onRemind,
                        color = if (reminder != null) BlueSoft else Surface2,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, if (reminder != null) Blue.copy(alpha = 0.44f) else Line),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                stringResource(if (reminder != null) R.string.ui_reminder_action_set else R.string.ui_reminder_short),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (reminder != null) BlueText else Ink2
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSetting() {
    val context = LocalContext.current
    val currentMode = ThemePrefs.mode
    Column {
        Text(
            stringResource(R.string.ui_settings_appearance),
            style = MaterialTheme.typography.labelSmall,
            color = Ink2
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppearanceChip(
                label = stringResource(R.string.ui_settings_theme_system),
                selected = currentMode == ThemeMode.SYSTEM,
                onClick = { ThemePrefs.set(context, ThemeMode.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
            AppearanceChip(
                label = stringResource(R.string.ui_settings_theme_light),
                selected = currentMode == ThemeMode.LIGHT,
                onClick = { ThemePrefs.set(context, ThemeMode.LIGHT) },
                modifier = Modifier.weight(1f)
            )
            AppearanceChip(
                label = stringResource(R.string.ui_settings_theme_dark),
                selected = currentMode == ThemeMode.DARK,
                onClick = { ThemePrefs.set(context, ThemeMode.DARK) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppearanceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (selected) SignalSoft else Surface2,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) Signal.copy(alpha = 0.5f) else Line),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) SignalText else Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

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
                color = Ink,
                modifier = Modifier.weight(1f)
            )
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

private enum class ModelDeleteTarget { SPEECH, LLM }

/**
 * Full-screen overlay Settings sheet opened from the Desk Masthead gear button.
 */
@Composable
internal fun SettingsScreen(
    armed: Boolean,
    recording: Boolean,
    notifAccess: Boolean,
    micGranted: Boolean,
    postNotifsGranted: Boolean,
    onBack: () -> Unit,
    onOpenNotifAccess: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestPostNotifs: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logVersion by CaptureLog.version.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ModelDeleteTarget?>(null) }

    BackHandler { onBack() }

    val logTail = remember(logVersion, showDiagnostics) {
        if (!showDiagnostics) ""
        else CaptureLog.dump().split("\n").takeLast(14).reversed().joinToString("\n")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackIconButton(onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ui_tab_settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
                Text(
                    stringResource(R.string.ui_settings_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Line)

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Appearance (System / Light / Dark)
            item(contentType = "appearance") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(Modifier.padding(16.dp)) {
                            AppearanceSetting()
                        }
                    }
                }
            }

            // Capture status & Language
            item(contentType = "status_lang") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            SettingRow(
                                stringResource(R.string.ui_settings_capture_service),
                                if (armed) stringResource(R.string.ui_settings_armed) else stringResource(R.string.ui_settings_disarmed),
                                if (armed) SignalText else Ink2
                            )
                            Spacer(Modifier.height(10.dp))
                            SettingRow(
                                stringResource(R.string.ui_settings_recording),
                                if (recording) stringResource(R.string.ui_settings_live) else stringResource(R.string.ui_settings_idle),
                                if (recording) SignalText else Ink2
                            )
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(thickness = 1.dp, color = Line)
                            Spacer(Modifier.height(14.dp))
                            LanguageSetting()
                        }
                    }
                }
            }

            // Permissions
            item(contentType = "permissions") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.ui_settings_permissions), style = MaterialTheme.typography.labelSmall, color = Ink2)
                            PermissionRow(
                                label = stringResource(R.string.ui_settings_notif_access),
                                granted = notifAccess,
                                onAction = onOpenNotifAccess
                            )
                            PermissionRow(
                                label = stringResource(R.string.ui_settings_mic_access),
                                granted = micGranted,
                                onAction = onRequestMic
                            )
                            PermissionRow(
                                label = stringResource(R.string.ui_settings_post_notifs),
                                granted = postNotifsGranted,
                                onAction = onRequestPostNotifs
                            )
                        }
                    }
                }
            }

            // Speech recognition model
            item(contentType = "speech_model") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.ui_speech_model), style = MaterialTheme.typography.labelSmall, color = BlueText)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                ModelInstaller.status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (ModelInstaller.failed) Red else Ink
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${stringResource(R.string.ui_settings_speech_model_body)} ${stringResource(R.string.ui_record_wifi_hint)}",
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
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                when {
                                    ModelInstaller.busy -> PillButton(
                                        label = stringResource(R.string.ui_action_cancel),
                                        background = Surface2,
                                        foreground = Ink,
                                        onClick = { ModelInstaller.cancel() }
                                    )
                                    ModelInstaller.installed -> {
                                        Surface(
                                            color = MintSoft,
                                            shape = RoundedCornerShape(999.dp),
                                            border = BorderStroke(1.dp, Mint.copy(alpha = 0.45f)),
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.ui_settings_model_ready), style = MaterialTheme.typography.labelMedium, color = Mint)
                                            }
                                        }
                                        PillButton(
                                            label = stringResource(R.string.ui_action_delete),
                                            background = Surface2,
                                            foreground = Red,
                                            border = BorderStroke(1.dp, Line),
                                            onClick = { deleteTarget = ModelDeleteTarget.SPEECH }
                                        )
                                    }
                                    else -> PillButton(
                                        label = stringResource(R.string.ui_settings_download_model),
                                        background = SignalFill,
                                        foreground = Color.White,
                                        onClick = { ModelInstaller.download(context) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Transform LLM model
            item(contentType = "transform_model") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.ui_transform_model), style = MaterialTheme.typography.labelSmall, color = Mint)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                TransformModel.status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (TransformModel.failed) Red else Ink
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${stringResource(R.string.ui_transform_model_blurb)} ${stringResource(R.string.ui_record_wifi_hint)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink2
                            )
                            if (TransformModel.busy) {
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { TransformModel.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Mint,
                                    trackColor = Line
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                when {
                                    TransformModel.busy -> PillButton(
                                        label = stringResource(R.string.ui_action_cancel),
                                        background = Surface2,
                                        foreground = Ink,
                                        onClick = { TransformModel.cancel() }
                                    )
                                    TransformModel.installed -> {
                                        Surface(
                                            color = MintSoft,
                                            shape = RoundedCornerShape(999.dp),
                                            border = BorderStroke(1.dp, Mint.copy(alpha = 0.45f)),
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.ui_settings_model_ready), style = MaterialTheme.typography.labelMedium, color = Mint)
                                            }
                                        }
                                        PillButton(
                                            label = stringResource(R.string.ui_action_delete),
                                            background = Surface2,
                                            foreground = Red,
                                            border = BorderStroke(1.dp, Line),
                                            onClick = { deleteTarget = ModelDeleteTarget.LLM }
                                        )
                                    }
                                    else -> PillButton(
                                        label = stringResource(R.string.ui_settings_download_llm),
                                        background = Mint,
                                        foreground = OnMint,
                                        onClick = { TransformModel.download(context) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Collapsible Diagnostics log
            item(contentType = "diagnostics") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showDiagnostics = !showDiagnostics },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.ui_settings_diagnostics),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialSerif),
                                    color = Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (showDiagnostics) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = Ink2
                                )
                            }
                            if (showDiagnostics) {
                                Spacer(Modifier.height(10.dp))
                                SelectionContainer {
                                    Text(
                                        if (logTail.isBlank()) stringResource(R.string.ui_settings_no_events) else logTail,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialMono),
                                        color = Ink2
                                    )
                                }
                                if (logTail.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PillButton(
                                            label = stringResource(R.string.common_link_copy),
                                            background = Surface2,
                                            foreground = Ink,
                                            onClick = {
                                                clipboard.setText(AnnotatedString(CaptureLog.dump()))
                                                Toast.makeText(context, R.string.ui_copied_toast, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        PillButton(
                                            label = stringResource(R.string.ui_action_clear),
                                            background = Surface2,
                                            foreground = Ink2,
                                            onClick = { CaptureLog.clear() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val pendingDelete = deleteTarget
    if (pendingDelete != null) {
        val modelLabel = stringResource(
            if (pendingDelete == ModelDeleteTarget.SPEECH) R.string.ui_speech_model else R.string.ui_transform_model
        )
        val mb = if (pendingDelete == ModelDeleteTarget.SPEECH) ModelInstaller.totalMb else TransformModel.totalMb
        ConfirmDialog(
            title = stringResource(R.string.ui_settings_model_delete_title, modelLabel),
            body = stringResource(R.string.ui_settings_model_delete_body, mb),
            confirmLabel = stringResource(R.string.ui_action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch(Dispatchers.IO) {
                    if (pendingDelete == ModelDeleteTarget.SPEECH) {
                        ModelInstaller.deleteInstalled(context)
                    } else {
                        TransformModel.deleteInstalled(context)
                    }
                }
            }
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Text(stringResource(R.string.ui_settings_granted), style = MaterialTheme.typography.labelMedium, color = Mint)
        } else {
            PillButton(
                label = stringResource(R.string.ui_settings_needed),
                background = Surface2,
                foreground = SignalText,
                border = BorderStroke(1.dp, Line),
                onClick = onAction
            )
        }
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
