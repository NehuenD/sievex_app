package com.sievex.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.sievex.app.R
import com.sievex.app.Reminder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared accessible pill button enforcing a 48dp minimum touch height and proper Surface ripple clipping.
 */
@Composable
internal fun PillButton(
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: BorderStroke? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = background,
        shape = RoundedCornerShape(999.dp),
        border = border,
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Shared toggleable pill chip with AA contrast (SignalText on SignalSoft) and 48dp min hit height.
 */
@Composable
internal fun SubTab(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (selected) SignalSoft else Surface2,
        shape = RoundedCornerShape(999.dp),
        border = if (selected) BorderStroke(1.dp, Signal.copy(alpha = 0.4f)) else BorderStroke(1.dp, Line),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) SignalText else Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 48x48dp accessible circular back icon button. */
@Composable
internal fun BackIconButton(
    onClick: () -> Unit,
    contentDescription: String = stringResource(R.string.ui_action_back),
    enabled: Boolean = true,
    tint: Color = Ink
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = CircleShape,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescription,
                tint = if (enabled) tint else Ink2,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 48x48dp accessible circular close icon button. */
@Composable
internal fun CloseIconButton(
    onClick: () -> Unit,
    contentDescription: String = stringResource(R.string.ui_action_close),
    enabled: Boolean = true,
    tint: Color = Ink
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = CircleShape,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Close,
                contentDescription = contentDescription,
                tint = if (enabled) tint else Ink2,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Labelled folio-well text field shared across editors, space/lens dialogs, and Share sheet.
 */
@Composable
internal fun EditorField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minHeight: Dp = 24.dp,
    enabled: Boolean = true,
    required: Boolean = false,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(
                text = if (required) "$label *" else label,
                style = MaterialTheme.typography.labelSmall,
                color = Ink2
            )
            Spacer(Modifier.height(8.dp))
        }
        val fieldModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2)
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .alpha(if (enabled) 1f else 0.6f)
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlaceholderInk
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                singleLine = singleLine,
                cursorBrush = SolidColor(Signal),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = keyboardActions,
                modifier = fieldModifier
            )
        }
    }
}

/** Shared modal folio container so all dialogs in AppUi, MediaUi, and ShareImageActivity match. */
@Composable
internal fun AppDialogSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Line),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.ui_action_cancel),
    destructive: Boolean = true
) {
    AppDialogSurface(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Ink2)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(dismissLabel, Surface2, Ink, onDismiss, border = BorderStroke(1.dp, Line))
            Spacer(Modifier.width(10.dp))
            PillButton(
                label = confirmLabel,
                background = if (destructive) RedFill else SignalFill,
                foreground = Color.White,
                onClick = onConfirm
            )
        }
    }
}

@Composable
internal fun DiscardChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit
) {
    ConfirmDialog(
        title = stringResource(R.string.ui_discard_title),
        body = stringResource(R.string.ui_discard_body),
        confirmLabel = stringResource(R.string.ui_discard_confirm),
        onConfirm = onDiscard,
        onDismiss = onKeepEditing,
        dismissLabel = stringResource(R.string.ui_keep_editing),
        destructive = true
    )
}

private val REMINDER_FMT_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")
private val REMINDER_FMT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun formatReminderWhen(triggerAtMs: Long): String {
    val zone = ZoneId.systemDefault()
    val target = Instant.ofEpochMilli(triggerAtMs).atZone(zone)
    val today = LocalDate.now(zone)
    val date = target.toLocalDate()
    val timeStr = target.format(REMINDER_FMT_TIME)
    return when (date) {
        today -> "${stringResource(R.string.common_today)} $timeStr"
        today.plusDays(1) -> "${stringResource(R.string.ui_reminder_tomorrow)} $timeStr"
        else -> target.format(REMINDER_FMT_SHORT.withLocale(Locale.getDefault()))
    }
}

@Composable
internal fun ReminderPickerDialog(
    title: String,
    existing: Reminder?,
    onSchedule: (Long) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val nowMs = System.currentTimeMillis()
    val today = LocalDate.now(zone)

    val inTwoHours = nowMs + 2L * 60L * 60L * 1000L
    val tonightTarget = today.atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
    val tonight = if (tonightTarget > nowMs + 15L * 60L * 1000L) {
        tonightTarget
    } else {
        today.plusDays(1).atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
    }
    val tomorrowMorning = today.plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
    val nextWeek = today.plusDays(7).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()

    val presets = listOf(
        stringResource(R.string.ui_reminder_preset_2h) to inTwoHours,
        stringResource(R.string.ui_reminder_preset_tonight) to tonight,
        stringResource(R.string.ui_reminder_preset_tomorrow) to tomorrowMorning,
        stringResource(R.string.ui_reminder_preset_week) to nextWeek
    )

    AppDialogSurface(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_reminder_dialog_title), style = MaterialTheme.typography.headlineSmall, color = Ink)
        if (title.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (existing != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.ui_reminder_scheduled_for, formatReminderWhen(existing.triggerAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = BlueText
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, ms) ->
                Surface(
                    onClick = { onSchedule(ms) },
                    color = Surface2,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                        Text(
                            formatReminderWhen(ms),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = EditorialMono),
                            color = BlueText
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (existing != null && onClear != null) {
                PillButton(
                    label = stringResource(R.string.ui_reminder_clear),
                    background = Surface2,
                    foreground = Red,
                    onClick = onClear
                )
                Spacer(Modifier.width(10.dp))
            }
            PillButton(stringResource(R.string.ui_action_cancel), Surface2, Ink, onDismiss)
        }
    }
}
