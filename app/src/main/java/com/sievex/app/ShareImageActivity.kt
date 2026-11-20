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
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sievex.app.ui.Ink
import com.sievex.app.ui.Ink2
import com.sievex.app.ui.Line
import com.sievex.app.ui.MediaBitmapCache
import com.sievex.app.ui.SievexTheme
import com.sievex.app.ui.Signal
import com.sievex.app.ui.Surface1
import com.sievex.app.ui.Surface2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translucent share-target sheet for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (images).
 *
 * Lets the user pick a space, add an optional short caption, and either **Save** (primary)
 * or **Save & record** (starts a capture burst linked to the saved media).
 */
class ShareImageActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractImageUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.ui_share_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            SievexTheme {
                ShareSheet(
                    uris = uris,
                    onDismiss = { finish() },
                    onSave = { spaceId, caption, alsoRecord ->
                        saveAndFinish(uris, spaceId, caption, alsoRecord)
                    }
                )
            }
        }
    }

    private fun saveAndFinish(
        uris: List<Uri>,
        spaceId: String,
        caption: String,
        alsoRecord: Boolean
    ) {
        val importedIds = uris.mapNotNull { uri ->
            MediaStore.importFromUri(this, uri, spaceId, caption)?.id
        }
        if (importedIds.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, R.string.ui_share_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        if (alsoRecord) {
            SpaceStore.currentSpaceId = spaceId
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                CaptureService.sendBurstWithMedia(this, importedIds, spaceId, "share")
                val openApp = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(openApp)
            } else {
                runOnUiThread {
                    Toast.makeText(this, R.string.ui_share_mic_needed, Toast.LENGTH_LONG).show()
                }
            }
        }
        runOnUiThread { finish() }
    }

    private fun extractImageUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
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
    }
}

@Composable
private fun ShareSheet(
    uris: List<Uri>,
    onDismiss: () -> Unit,
    onSave: (spaceId: String, caption: String, alsoRecord: Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var selectedSpaceId by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }
    var newSpaceName by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            NoteStore.init(context.filesDir)
        }
        val loaded = withContext(Dispatchers.IO) { SpaceStore.all() }
        val current = SpaceStore.currentSpaceId
        spaces = loaded
        selectedSpaceId = current?.takeIf { id -> loaded.any { it.id == id } }
            ?: loaded.firstOrNull()?.id
        preview = MediaBitmapCache.loadUri(context, uris.first(), 480)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = !saving) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = Surface1
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .border(1.dp, Line, RoundedCornerShape(10.dp)),
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
                }

                // Space picker
                Text(
                    stringResource(R.string.ui_share_space_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink2
                )

                if (spaces.isEmpty()) {
                    Text(
                        stringResource(R.string.ui_share_no_spaces),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newSpaceName,
                            onValueChange = { newSpaceName = it },
                            placeholder = { Text(stringResource(R.string.ui_share_new_space_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = newSpaceName.isNotBlank() && !saving,
                            onClick = {
                                val name = newSpaceName.trim()
                                scope.launch {
                                    val created = withContext(Dispatchers.IO) {
                                        SpaceStore.create(name)
                                    }
                                    spaces = withContext(Dispatchers.IO) { SpaceStore.all() }
                                    selectedSpaceId = created.id
                                    newSpaceName = ""
                                }
                            }
                        ) {
                            Text(stringResource(R.string.ui_share_create_space))
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        spaces.forEach { sp ->
                            val selected = sp.id == selectedSpaceId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (selected) Signal else Surface2)
                                    .border(1.dp, if (selected) Signal else Line, RoundedCornerShape(999.dp))
                                    .clickable(enabled = !saving) { selectedSpaceId = sp.id }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    sp.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color.White else Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text(stringResource(R.string.ui_media_caption_label)) },
                    placeholder = { Text(stringResource(R.string.ui_media_caption_hint)) },
                    maxLines = 3,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )

                val targetSpace = selectedSpaceId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        enabled = targetSpace != null && !saving,
                        onClick = {
                            val spId = targetSpace ?: return@OutlinedButton
                            saving = true
                            scope.launch(Dispatchers.IO) {
                                onSave(spId, caption, true)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.ui_share_save_record),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        enabled = targetSpace != null && !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Color.White),
                        onClick = {
                            val spId = targetSpace ?: return@Button
                            saving = true
                            scope.launch(Dispatchers.IO) {
                                onSave(spId, caption, false)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (saving) stringResource(R.string.ui_share_saving)
                            else stringResource(R.string.ui_share_save),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
