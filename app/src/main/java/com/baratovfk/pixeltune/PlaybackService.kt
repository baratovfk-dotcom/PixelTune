package com.baratovfk.pixeltune

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, getOrCreatePlayer(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        synchronized(LOCK) {
            sharedPlayer?.release()
            sharedPlayer = null
        }
        super.onDestroy()
    }

    companion object {
        private val LOCK = Any()
        @Volatile
        var sharedPlayer: ExoPlayer? = null
            private set

        fun getOrCreatePlayer(context: Context): ExoPlayer = synchronized(LOCK) {
            sharedPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also { sharedPlayer = it }
        }
    }
}
