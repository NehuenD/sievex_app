package com.sievex.app

import android.app.Application
import com.sievex.app.llm.LlamaBridge

/**
 * Process-wide initialization so every entry point (MainActivity, CaptureService, LensService,
 * ShareImageActivity, MediaWatcher) has the native library directory registered before ggml loads
 * dynamic CPU backend plugins.
 */
class SievexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LlamaBridge.setNativeLibDir(applicationInfo.nativeLibraryDir)
    }
}
