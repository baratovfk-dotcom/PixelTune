package com.baratovfk.pixeltune

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Use Media3's notification provider explicitly. Media3 1.5.1 does not expose
        // setSmallIcon() on this builder, so keep the provider's supported defaults.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val player = getOrCreatePlayer(this).apply {
            // Keep the audio focus/noisy-output behavior consistent with a media player.
            setHandleAudioBecomingNoisy(true)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
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
            sharedPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also {
                sharedPlayer = it
            }
        }
    }
}
