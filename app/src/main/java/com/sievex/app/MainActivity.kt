package com.sievex.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import com.sievex.app.ui.SievexApp
import com.sievex.app.ui.SievexTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wants = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        val missing = wants.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }

        setContent {
            SievexTheme {
                SievexApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bursts can be left in the queue by an earlier process. Ask for a drain whenever the
        // app comes forward: this is the one moment a foreground service start is never refused.
        CaptureService.kick(this)
    }
}
