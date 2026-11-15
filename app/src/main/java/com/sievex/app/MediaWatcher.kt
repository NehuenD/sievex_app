package com.sievex.app

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Watches third-party media sessions. A PLAYING -> PAUSED transition is our capture trigger:
 * the user presses pause on their earbuds and the podcast itself tells us to start.
 */
class MediaWatcher : NotificationListenerService() {

    private var msm: MediaSessionManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lastState = HashMap<String, Int>()
    private val attached = HashSet<String>()

    private val activeListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list) }

    override fun onListenerConnected() {
        CaptureLog.add("media listener connected")
        val manager = getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            CaptureLog.add("no MediaSessionManager")
            return
        }
        msm = manager
        val me = ComponentName(this, MediaWatcher::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(activeListener, me)
            attach(manager.getActiveSessions(me))
        } catch (e: Exception) {
            CaptureLog.add("media access failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        CaptureLog.add("media listener disconnected")
        try {
            msm?.removeOnActiveSessionsChangedListener(activeListener)
        } catch (_: Exception) {
        }
        attached.clear()
    }

    private fun attach(list: List<MediaController>?) {
        if (list == null) return
        CaptureLog.add("sessions: ${list.size}")
        for (controller in list) {
            val pkg = controller.packageName
            if (!attached.add(pkg)) continue
            CaptureLog.add("attach $pkg")
            controller.registerCallback(object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handle(pkg, state)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    CaptureLog.add("meta $pkg: $title — $artist")
                }
            }, handler)
            handle(pkg, controller.playbackState)
        }
    }

    private fun handle(pkg: String, state: PlaybackState?) {
        if (state == null) return
        val s = state.state
        val prev = lastState[pkg]
        if (prev == s) return
        lastState[pkg] = s
        CaptureLog.add("state $pkg -> ${name(s)} pos=${state.position}")

        if (prev == PlaybackState.STATE_PLAYING && s == PlaybackState.STATE_PAUSED) {
            CaptureService.trigger(true, "pause in $pkg")
        } else if (prev == PlaybackState.STATE_PAUSED && s == PlaybackState.STATE_PLAYING) {
            CaptureService.trigger(false, "resume in $pkg")
        }
    }

    private fun name(state: Int): String = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_FAST_FORWARDING -> "FF"
        PlaybackState.STATE_REWINDING -> "REW"
        else -> "OTHER($state)"
    }
}
