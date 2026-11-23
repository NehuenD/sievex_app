package com.sievex.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sievex.app.llm.Transform
import com.sievex.app.llm.TransformModel
import com.sievex.app.ui.Blue
import com.sievex.app.ui.BlueSoft
import com.sievex.app.ui.BlueText
import com.sievex.app.ui.CloseIconButton
import com.sievex.app.ui.EditorField
import com.sievex.app.ui.FormatChips
import com.sievex.app.ui.Ink
import com.sievex.app.ui.Ink2
import com.sievex.app.ui.Line
import com.sievex.app.ui.MediaBitmapCache
import com.sievex.app.ui.PillButton
import com.sievex.app.ui.SievexTheme
import com.sievex.app.ui.Signal
import com.sievex.app.ui.SignalFill
import com.sievex.app.ui.SignalSoft
import com.sievex.app.ui.SignalText
import com.sievex.app.ui.Surface1
import com.sievex.app.ui.Surface2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Translucent share-target sheet for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (images) and
 * `ACTION_SEND` (`text/plain` highlights & URLs).
 */
class ShareImageActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractImageUris(intent)
        val sharedText = if (uris.isEmpty()) extractSharedText(intent) else null
        val sharedSubject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()

        if (uris.isEmpty() && sharedText.isNullOrBlank()) {
            Toast.makeText(this, R.string.ui_share_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            SievexTheme(updateSystemBars = false) {
                if (uris.isNotEmpty()) {
                    ShareSheet(
                        uris = uris,
                        onDismiss = { finish() },
                        onSave = { spaceId, caption, alsoRecord, alsoShapeNote, onDone ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val ok = runCatching {
                                    saveImagesAndFinish(uris, spaceId, caption, alsoRecord, alsoShapeNote)
                                }.onFailure { e ->
                                    CaptureLog.add("share: save failed - ${e.message}")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@ShareImageActivity,
                                            R.string.ui_share_save_error,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }.getOrDefault(false)
                                withContext(Dispatchers.Main) { onDone(ok) }
                            }
                        }
                    )
                } else {
                    ShareTextSheet(
                        initialText = sharedText.orEmpty(),
                        initialSubject = sharedSubject,
                        onDismiss = { finish() },
                        onSave = { spaceId, clippedText, reaction, format, alsoRecord, onDone ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val ok = runCatching {
                                    saveTextAndFinish(spaceId, clippedText, reaction, format, alsoRecord)
                                }.onFailure { e ->
                                    CaptureLog.add("share-text: save failed - ${e.message}")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@ShareImageActivity,
                                            R.string.ui_share_save_error,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }.getOrDefault(false)
                                withContext(Dispatchers.Main) { onDone(ok) }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun saveImagesAndFinish(
        uris: List<Uri>,
        spaceId: String,
        caption: String,
        alsoRecord: Boolean,
        alsoShapeNote: Boolean
    ): Boolean {
        val imported = uris.mapNotNull { uri ->
            MediaStore.importFromUri(this, uri, spaceId, caption)
        }
        val importedIds = imported.map { it.id }
        if (importedIds.isEmpty()) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, R.string.ui_share_save_error, Toast.LENGTH_SHORT).show()
                }
            }
            return false
        }

        if (alsoShapeNote && !alsoRecord) {
            val app = applicationContext
            val format = Formats.next
            val lang = AppLocale.modelLanguage(app)
            SHAPE_EXECUTOR.execute {
                runCatching {
                    NoteStore.init(app.filesDir)
                    val ocrParts = importedIds.mapNotNull { id ->
                        MediaStore.ensureOcr(id)?.ocrText?.trim()?.takeIf { it.isNotBlank() }
                    }
                    val source = buildString {
                        if (caption.isNotBlank()) appendLine(caption.trim())
                        if (ocrParts.isNotEmpty()) {
                            if (isNotEmpty()) appendLine()
                            append(ocrParts.joinToString("\n\n"))
                        }
                    }.trim()
                    if (source.isBlank()) return@execute
                    val useLlm = format.usesLlm && TransformModel.isInstalled(app.filesDir)
                    val chosen = if (useLlm) format else NoteFormat.PLAIN
                    val (spaceName, spaceFocus) = SpaceStore.focusFor(spaceId)
                    val structured = Transform.apply(
                        app.filesDir,
                        source,
                        chosen,
                        lang,
                        spaceName = spaceName,
                        spaceFocus = spaceFocus
                    )
                    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                    val whenText = "Today $time"
                    val noteId = NoteStore.nextId()
                    val note = if (structured != null) {
                        StoredNote(
                            id = noteId,
                            title = structured.title,
                            oneLine = structured.oneLine,
                            mode = structured.mode,
                            whenText = whenText,
                            verbatim = source,
                            why = structured.why,
                            question = structured.question,
                            tags = structured.tags,
                            audioPath = null,
                            spaceId = spaceId,
                            format = chosen.id,
                            items = structured.items,
                            createdAt = System.currentTimeMillis(),
                            questionDrafts = structured.questionDrafts
                        )
                    } else {
                        StoredNote(
                            id = noteId,
                            title = source.lineSequence().firstOrNull { it.isNotBlank() }?.take(64)
                                ?: app.getString(R.string.common_untitled_note),
                            oneLine = "",
                            mode = "Reference",
                            whenText = whenText,
                            verbatim = source,
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
                    MediaStore.linkNoteToMedia(importedIds, note.id)
                    CaptureLog.add("share: shaped OCR into note ${note.id}")
                }.onFailure {
                    CaptureLog.add("share: OCR shape failed - ${it.message}")
                }
            }
        }

        var openMain = false
        var warnMic = false
        if (alsoRecord) {
            SpaceStore.currentSpaceId = spaceId
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                CaptureService.sendBurstWithMedia(this, importedIds, spaceId, "share")
                openMain = true
            } else {
                warnMic = true
            }
        }
        val partialLoss = importedIds.size < uris.size
        runOnUiThread {
            if (partialLoss) {
                Toast.makeText(
                    this,
                    getString(R.string.ui_share_partial_failed, importedIds.size, uris.size),
                    Toast.LENGTH_LONG
                ).show()
            } else if (warnMic) {
                Toast.makeText(this, R.string.ui_share_mic_needed, Toast.LENGTH_LONG).show()
            } else if (alsoShapeNote && !alsoRecord) {
                Toast.makeText(this, R.string.ui_share_shaping_background, Toast.LENGTH_SHORT).show()
            }
            if (openMain && !isFinishing && !isDestroyed) {
                val openApp = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(openApp)
            }
            if (!isFinishing && !isDestroyed) finish()
        }
        return true
    }

    private fun saveTextAndFinish(
        spaceId: String?,
        clippedText: String,
        reaction: String,
        format: NoteFormat,
        alsoRecord: Boolean
    ): Boolean {
        val cleanClip = clippedText.trim()
        val cleanReaction = reaction.trim()
        if (cleanClip.isBlank() && cleanReaction.isBlank()) return false

        NoteStore.init(filesDir)
        val combined = buildString {
            if (cleanReaction.isNotBlank()) {
                appendLine(cleanReaction)
                if (cleanClip.isNotBlank()) appendLine()
            }
            if (cleanClip.isNotBlank()) append(cleanClip)
        }.trim()

        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val whenText = "Today $time"
        val useLlm = format.usesLlm && TransformModel.isInstalled(filesDir)
        val chosenFormat = if (useLlm) format else NoteFormat.PLAIN
        val (spaceName, spaceFocus) = SpaceStore.focusFor(spaceId)
        val structured = Transform.apply(
            filesDir,
            combined,
            chosenFormat,
            AppLocale.modelLanguage(this),
            spaceName = spaceName,
            spaceFocus = spaceFocus
        )

        val noteId = NoteStore.nextId()
        val note = if (structured != null) {
            StoredNote(
                id = noteId,
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
                format = chosenFormat.id,
                items = structured.items,
                createdAt = System.currentTimeMillis(),
                questionDrafts = structured.questionDrafts
            )
        } else {
            val fallbackTitle = combined.lineSequence().firstOrNull { it.isNotBlank() }?.take(72)
                ?: getString(R.string.common_untitled_note)
            StoredNote(
                id = noteId,
                title = fallbackTitle,
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
        CaptureLog.add("share-text: filed clip as note ${note.id}")

        var openNote = false
        var warnMic = false
        if (alsoRecord) {
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                CaptureService.sendFollowUpBurst(this, note.id, "share clip")
                openNote = true
            } else {
                warnMic = true
            }
        }

        runOnUiThread {
            if (warnMic) {
                Toast.makeText(this, R.string.ui_share_mic_needed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.ui_share_text_saved, Toast.LENGTH_SHORT).show()
            }
            if (openNote && !isFinishing && !isDestroyed) {
                val openApp = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_NOTE_ID, note.id)
                startActivity(openApp)
            }
            if (!isFinishing && !isDestroyed) finish()
        }
        return true
    }

    private fun extractImageUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        if (intent.type?.startsWith("text/") == true) return emptyList()
        val fromStream = when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.filterNotNull() ?: emptyList()
            }
            else -> emptyList()
        }
        if (fromStream.isNotEmpty()) return fromStream
        val clip = intent.clipData ?: return emptyList()
        return buildList {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { add(it) }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        val extra = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!extra.isNullOrBlank()) return extra
        val clip = intent.clipData ?: return null
        for (i in 0 until clip.itemCount) {
            val txt = clip.getItemAt(i)?.coerceToText(this)?.toString()?.trim()
            if (!txt.isNullOrBlank()) return txt
        }
        return null
    }

    companion object {
        private val SHAPE_EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sievex-share-shape").apply { isDaemon = true }
        }
    }
}

private enum class ShareSaveMode { SAVE, SAVE_AND_RECORD }

@Composable
private fun ShareSheet(
    uris: List<Uri>,
    onDismiss: () -> Unit,
    onSave: (spaceId: String, caption: String, alsoRecord: Boolean, alsoShapeNote: Boolean, onDone: (Boolean) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var loadingSpaces by remember { mutableStateOf(true) }
    var selectedSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingInlineSpace by rememberSaveable { mutableStateOf(false) }
    var creatingSpaceBusy by remember { mutableStateOf(false) }
    var newSpaceName by rememberSaveable { mutableStateOf("") }
    var caption by rememberSaveable { mutableStateOf("") }
    var alsoShapeNote by rememberSaveable { mutableStateOf(false) }
    var llmAvailable by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewChecked by remember { mutableStateOf(false) }
    var savingMode by remember { mutableStateOf<ShareSaveMode?>(null) }
    val saving = savingMode != null

    val submitSave: (String, Boolean) -> Unit = { spId, alsoRecord ->
        savingMode = if (alsoRecord) ShareSaveMode.SAVE_AND_RECORD else ShareSaveMode.SAVE
        onSave(spId, caption, alsoRecord, alsoShapeNote) { ok ->
            if (!ok) savingMode = null
        }
    }

    var pendingRecordSpaceId by remember { mutableStateOf<String?>(null) }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val spId = pendingRecordSpaceId
        pendingRecordSpaceId = null
        if (spId != null) {
            submitSave(spId, granted)
        }
    }

    BackHandler(enabled = saving) {}

    LaunchedEffect(Unit) {
        val previewDeferred = async { MediaBitmapCache.loadUri(context, uris.first(), 480) }
        val loaded = withContext(Dispatchers.IO) {
            NoteStore.init(context.filesDir)
            Formats.restore(context)
            llmAvailable = TransformModel.isInstalled(context.filesDir)
            SpaceStore.all()
        }
        val current = SpaceStore.currentSpaceId
        spaces = loaded
        loadingSpaces = false
        creatingInlineSpace = loaded.isEmpty()
        selectedSpaceId = selectedSpaceId?.takeIf { id -> loaded.any { it.id == id } }
            ?: current?.takeIf { id -> loaded.any { it.id == id } }
            ?: loaded.firstOrNull()?.id
        preview = previewDeferred.await()
        previewChecked = true
    }

    val createInlineSpace: () -> Unit = createInlineSpace@{
        val name = newSpaceName.trim()
        if (name.isEmpty() || creatingSpaceBusy || saving) return@createInlineSpace
        creatingSpaceBusy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val created = SpaceStore.create(name)
                    created to SpaceStore.all()
                }
            }
            creatingSpaceBusy = false
            result.onSuccess { (created, allSpaces) ->
                spaces = allSpaces
                selectedSpaceId = created.id
                newSpaceName = ""
                creatingInlineSpace = false
            }.onFailure {
                Toast.makeText(context, R.string.ui_media_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f))
            .clickable(enabled = !saving) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Surface1
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface2)
                            .border(1.dp, Line, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = preview
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (previewChecked) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = stringResource(R.string.ui_media_missing),
                                tint = Ink2,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.ui_share_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink
                        )
                        if (uris.size > 1) {
                            Text(
                                stringResource(R.string.ui_share_count_many, uris.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink2
                            )
                        }
                    }
                    CloseIconButton(
                        onClick = onDismiss,
                        enabled = !saving
                    )
                }

                ShareSpaceSelector(
                    spaces = spaces,
                    loadingSpaces = loadingSpaces,
                    selectedSpaceId = selectedSpaceId,
                    allowNoSpace = false,
                    creatingInlineSpace = creatingInlineSpace,
                    creatingSpaceBusy = creatingSpaceBusy,
                    newSpaceName = newSpaceName,
                    saving = saving,
                    onSelectSpace = { selectedSpaceId = it },
                    onToggleCreate = { creatingInlineSpace = !creatingInlineSpace },
                    onNewSpaceNameChange = { newSpaceName = it },
                    onCreateSpace = createInlineSpace
                )

                EditorField(
                    label = stringResource(R.string.ui_media_caption_label).uppercase(),
                    value = caption,
                    placeholder = stringResource(R.string.ui_media_caption_hint),
                    onChange = { caption = it },
                    minHeight = 48.dp,
                    enabled = !saving
                )

                if (llmAvailable) {
                    Surface(
                        onClick = { if (!saving) alsoShapeNote = !alsoShapeNote },
                        color = if (alsoShapeNote) BlueSoft else Surface2,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (alsoShapeNote) Blue.copy(alpha = 0.5f) else Line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (alsoShapeNote) Blue else Ink2)
                            )
                            Text(
                                stringResource(R.string.ui_share_also_shape_ocr),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (alsoShapeNote) BlueText else Ink,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                val targetSpace = selectedSpaceId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PillButton(
                        label = if (savingMode == ShareSaveMode.SAVE_AND_RECORD) {
                            stringResource(R.string.ui_share_saving)
                        } else {
                            stringResource(R.string.ui_share_save_record)
                        },
                        background = Surface2,
                        foreground = Ink,
                        border = BorderStroke(1.dp, Line),
                        enabled = targetSpace != null && !saving,
                        onClick = {
                            val spId = targetSpace ?: return@PillButton
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                submitSave(spId, true)
                            } else {
                                pendingRecordSpaceId = spId
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    PillButton(
                        label = if (savingMode == ShareSaveMode.SAVE) {
                            stringResource(R.string.ui_share_saving)
                        } else {
                            stringResource(R.string.ui_share_save)
                        },
                        background = if (targetSpace != null && !saving) SignalFill else Surface2,
                        foreground = if (targetSpace != null && !saving) Color.White else Ink2,
                        enabled = targetSpace != null && !saving,
                        onClick = {
                            val spId = targetSpace ?: return@PillButton
                            submitSave(spId, false)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ShareTextSheet(
    initialText: String,
    initialSubject: String,
    onDismiss: () -> Unit,
    onSave: (
        spaceId: String?,
        clippedText: String,
        reaction: String,
        format: NoteFormat,
        alsoRecord: Boolean,
        onDone: (Boolean) -> Unit
    ) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var loadingSpaces by remember { mutableStateOf(true) }
    var selectedSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingInlineSpace by rememberSaveable { mutableStateOf(false) }
    var creatingSpaceBusy by remember { mutableStateOf(false) }
    var newSpaceName by rememberSaveable { mutableStateOf("") }
    var clippedText by rememberSaveable {
        mutableStateOf(
            if (initialSubject.isNotBlank() && !initialText.contains(initialSubject, ignoreCase = true)) {
                "$initialSubject\n\n$initialText"
            } else {
                initialText
            }
        )
    }
    var reaction by rememberSaveable { mutableStateOf("") }
    var chosenFormat by remember { mutableStateOf(NoteFormat.NOTE) }
    var llmAvailable by remember { mutableStateOf(false) }
    var savingMode by remember { mutableStateOf<ShareSaveMode?>(null) }
    val saving = savingMode != null

    val submitSave: (Boolean) -> Unit = { alsoRecord ->
        savingMode = if (alsoRecord) ShareSaveMode.SAVE_AND_RECORD else ShareSaveMode.SAVE
        val fmt = if (llmAvailable) chosenFormat else NoteFormat.PLAIN
        onSave(selectedSpaceId, clippedText, reaction, fmt, alsoRecord) { ok ->
            if (!ok) savingMode = null
        }
    }

    var pendingRecord by remember { mutableStateOf(false) }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingRecord) {
            pendingRecord = false
            submitSave(granted)
        }
    }

    BackHandler(enabled = saving) {}

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            NoteStore.init(context.filesDir)
            Formats.restore(context)
            llmAvailable = TransformModel.isInstalled(context.filesDir)
            chosenFormat = if (llmAvailable) Formats.next else NoteFormat.PLAIN
            SpaceStore.all()
        }
        val current = SpaceStore.currentSpaceId
        spaces = loaded
        loadingSpaces = false
        selectedSpaceId = selectedSpaceId?.takeIf { id -> loaded.any { it.id == id } }
            ?: current?.takeIf { id -> loaded.any { it.id == id } }
    }

    val createInlineSpace: () -> Unit = createInlineSpace@{
        val name = newSpaceName.trim()
        if (name.isEmpty() || creatingSpaceBusy || saving) return@createInlineSpace
        creatingSpaceBusy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val created = SpaceStore.create(name)
                    created to SpaceStore.all()
                }
            }
            creatingSpaceBusy = false
            result.onSuccess { (created, allSpaces) ->
                spaces = allSpaces
                selectedSpaceId = created.id
                newSpaceName = ""
                creatingInlineSpace = false
            }.onFailure {
                Toast.makeText(context, R.string.ui_media_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f))
            .clickable(enabled = !saving) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Surface1
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.ui_share_text_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink
                        )
                        Text(
                            stringResource(R.string.ui_share_text_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2
                        )
                    }
                    CloseIconButton(onClick = onDismiss, enabled = !saving)
                }

                EditorField(
                    label = stringResource(R.string.ui_share_text_clip_label).uppercase(),
                    value = clippedText,
                    placeholder = stringResource(R.string.ui_share_text_clip_hint),
                    onChange = { clippedText = it },
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

                ShareSpaceSelector(
                    spaces = spaces,
                    loadingSpaces = loadingSpaces,
                    selectedSpaceId = selectedSpaceId,
                    allowNoSpace = true,
                    creatingInlineSpace = creatingInlineSpace,
                    creatingSpaceBusy = creatingSpaceBusy,
                    newSpaceName = newSpaceName,
                    saving = saving,
                    onSelectSpace = { selectedSpaceId = it },
                    onToggleCreate = { creatingInlineSpace = !creatingInlineSpace },
                    onNewSpaceNameChange = { newSpaceName = it },
                    onCreateSpace = createInlineSpace
                )

                if (llmAvailable) {
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

                if (saving && llmAvailable && chosenFormat.usesLlm) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Blue,
                        trackColor = Line
                    )
                }

                val canSave = (clippedText.isNotBlank() || reaction.isNotBlank()) && !saving
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PillButton(
                        label = if (savingMode == ShareSaveMode.SAVE_AND_RECORD) {
                            stringResource(R.string.ui_share_saving)
                        } else {
                            stringResource(R.string.ui_share_save_record)
                        },
                        background = Surface2,
                        foreground = Ink,
                        border = BorderStroke(1.dp, Line),
                        enabled = canSave,
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                submitSave(true)
                            } else {
                                pendingRecord = true
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    PillButton(
                        label = if (savingMode == ShareSaveMode.SAVE) {
                            stringResource(R.string.ui_share_saving)
                        } else {
                            stringResource(R.string.ui_share_save)
                        },
                        background = if (canSave) SignalFill else Surface2,
                        foreground = if (canSave) Color.White else Ink2,
                        enabled = canSave,
                        onClick = { submitSave(false) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ShareSpaceSelector(
    spaces: List<Space>,
    loadingSpaces: Boolean,
    selectedSpaceId: String?,
    allowNoSpace: Boolean,
    creatingInlineSpace: Boolean,
    creatingSpaceBusy: Boolean,
    newSpaceName: String,
    saving: Boolean,
    onSelectSpace: (String?) -> Unit,
    onToggleCreate: () -> Unit,
    onNewSpaceNameChange: (String) -> Unit,
    onCreateSpace: () -> Unit
) {
    Text(
        stringResource(R.string.ui_share_space_label).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Ink2
    )

    if (loadingSpaces) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = Signal,
            trackColor = Line
        )
        return
    }

    if (spaces.isNotEmpty() || allowNoSpace) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (allowNoSpace) {
                val selected = selectedSpaceId == null
                Surface(
                    onClick = { if (!saving) onSelectSpace(null) },
                    color = if (selected) BlueSoft else Surface2,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, if (selected) Blue else Line),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            stringResource(R.string.ui_space_none),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) BlueText else Ink,
                            maxLines = 1
                        )
                    }
                }
            }
            spaces.forEach { sp ->
                val selected = sp.id == selectedSpaceId
                Surface(
                    onClick = { if (!saving) onSelectSpace(sp.id) },
                    color = if (selected) BlueSoft else Surface2,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, if (selected) Blue else Line),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(max = 200.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            sp.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) BlueText else Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Surface(
                onClick = { if (!saving) onToggleCreate() },
                color = if (creatingInlineSpace) SignalSoft else Surface2,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, if (creatingInlineSpace) Signal else Line),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.ui_share_new_space_chip),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (creatingInlineSpace) SignalText else Ink2,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (spaces.isEmpty() || creatingInlineSpace) {
        if (spaces.isEmpty() && !allowNoSpace) {
            Text(
                stringResource(R.string.ui_share_no_spaces),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditorField(
                label = "",
                value = newSpaceName,
                placeholder = stringResource(R.string.ui_share_new_space_hint),
                onChange = onNewSpaceNameChange,
                singleLine = true,
                enabled = !saving && !creatingSpaceBusy,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onCreateSpace() }),
                modifier = Modifier.weight(1f)
            )
            PillButton(
                label = stringResource(R.string.ui_share_create_space),
                background = if (newSpaceName.isNotBlank()) SignalFill else Surface2,
                foreground = if (newSpaceName.isNotBlank()) Color.White else Ink2,
                enabled = newSpaceName.isNotBlank() && !saving && !creatingSpaceBusy,
                onClick = onCreateSpace
            )
        }
    }
}
