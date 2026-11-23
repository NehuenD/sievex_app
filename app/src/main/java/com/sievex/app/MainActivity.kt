package com.sievex.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.sievex.app.llm.LlamaBridge
import com.sievex.app.ui.MediaBitmapCache
import com.sievex.app.ui.SievexApp
import com.sievex.app.ui.SievexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    /**
     * Where a completion notification asked to go.
     *
     * Kept as Compose state rather than read from the intent at composition time because the
     * activity is singleTop: tapping another report arrives through [onNewIntent] while the UI is
     * already running. Both extras are removed as they are read, so a configuration change cannot
     * replay a navigation the user already made.
     */
    private var targetSpaceId by mutableStateOf<String?>(null)
    private var targetLensId by mutableStateOf<String?>(null)
    private var targetNoteId by mutableStateOf<Long?>(null)
    private var targetMediaId by mutableStateOf<String?>(null)
    private var targetStartBurst by mutableStateOf(false)

    /** Resolve every resource in the language chosen in Settings before the UI is created. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LlamaBridge.setNativeLibDir(applicationInfo.nativeLibraryDir)
        targetSpaceId = spaceFrom(intent)
        targetLensId = lensFrom(intent)
        targetNoteId = noteFrom(intent)
        targetMediaId = mediaFrom(intent)
        targetStartBurst = startBurstFrom(intent)

        // Permissions are requested from the UI, not here: the microphone when capture is armed,
        // notifications once onboarding is done. Requesting them in onCreate would drop two system
        // dialogs on top of the intro before the user knows what the app does.
        setContent {
            SievexTheme {
                SievexApp(
                    initialSpaceId = targetSpaceId,
                    initialLensId = targetLensId,
                    initialNoteId = targetNoteId,
                    initialMediaId = targetMediaId,
                    initialStartBurst = targetStartBurst,
                    onInitialSpaceConsumed = {
                        targetSpaceId = null
                        targetLensId = null
                    },
                    onInitialTargetConsumed = {
                        targetNoteId = null
                        targetMediaId = null
                    },
                    onInitialStartBurstConsumed = {
                        targetStartBurst = false
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetSpaceId = spaceFrom(intent)
        targetLensId = lensFrom(intent)
        targetNoteId = noteFrom(intent)
        targetMediaId = mediaFrom(intent)
        if (startBurstFrom(intent)) {
            targetStartBurst = true
        }
    }

    override fun onStart() {
        super.onStart()
        // Bursts can be left in the queue by an earlier process. Only start CaptureService when
        // persisted queue entries or un-filed orphan WAVs actually exist on disk.
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            NoteStore.init(appContext.filesDir)
            JobQueue.init(appContext.filesDir)
            val hasPending = JobQueue.peek() != null
            val hasOrphanWav = !hasPending && run {
                val burstsDir = File(appContext.getExternalFilesDir(null), "bursts")
                burstsDir.isDirectory && burstsDir.listFiles { f ->
                    f.isFile &&
                        f.extension.equals("wav", ignoreCase = true) &&
                        f.length() > 0L &&
                        !NoteStore.hasAudioPath(f.absolutePath)
                }?.isNotEmpty() == true
            }
            if (hasPending || hasOrphanWav) {
                CaptureService.kick(appContext)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            MediaBitmapCache.clear()
        }
    }

    private fun spaceFrom(intent: Intent?): String? {
        if (intent == null) return null
        val id = intent.getStringExtra(LensService.EXTRA_SPACE_ID)?.takeIf { it.isNotBlank() }
        intent.removeExtra(LensService.EXTRA_SPACE_ID)
        return id
    }

    private fun lensFrom(intent: Intent?): String? {
        if (intent == null) return null
        val id = intent.getStringExtra(LensService.EXTRA_LENS_ID)?.takeIf { it.isNotBlank() }
        intent.removeExtra(LensService.EXTRA_LENS_ID)
        return id
    }

    private fun noteFrom(intent: Intent?): Long? {
        if (intent == null || !intent.hasExtra(EXTRA_NOTE_ID)) return null
        val id = intent.getLongExtra(EXTRA_NOTE_ID, 0L).takeIf { it > 0L }
        intent.removeExtra(EXTRA_NOTE_ID)
        return id
    }

    private fun mediaFrom(intent: Intent?): String? {
        if (intent == null) return null
        val id = intent.getStringExtra(EXTRA_MEDIA_ID)?.takeIf { it.isNotBlank() }
        intent.removeExtra(EXTRA_MEDIA_ID)
        return id
    }

    private fun startBurstFrom(intent: Intent?): Boolean {
        if (intent == null) return false
        val start = intent.getBooleanExtra(EXTRA_START_BURST, false)
        intent.removeExtra(EXTRA_START_BURST)
        return start
    }

    companion object {
        const val EXTRA_NOTE_ID = "sievex_note_id"
        const val EXTRA_MEDIA_ID = "sievex_media_id"
        const val EXTRA_START_BURST = "sievex_start_burst"
    }
}
