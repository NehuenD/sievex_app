package com.sievex.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sievex.app.ui.SievexApp
import com.sievex.app.ui.SievexTheme

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

    /** Resolve every resource in the language chosen in Settings before the UI is created. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetSpaceId = spaceFrom(intent)
        targetLensId = lensFrom(intent)

        // Permissions are requested from the UI, not here: the microphone when capture is armed,
        // notifications once onboarding is done. Requesting them in onCreate would drop two system
        // dialogs on top of the intro before the user knows what the app does.
        setContent {
            SievexTheme {
                SievexApp(
                    initialSpaceId = targetSpaceId,
                    initialLensId = targetLensId,
                    onInitialSpaceConsumed = {
                        targetSpaceId = null
                        targetLensId = null
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
    }

    override fun onStart() {
        super.onStart()
        // Bursts can be left in the queue by an earlier process. Ask for a drain whenever the
        // app comes forward: this is the one moment a foreground service start is never refused.
        CaptureService.kick(this)
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
}
