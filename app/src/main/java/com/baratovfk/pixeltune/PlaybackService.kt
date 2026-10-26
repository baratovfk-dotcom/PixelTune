package com.baratovfk.pixeltune

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val player = getOrCreatePlayer(this)

        val callback = object : MediaSession.Callback {
            override fun onPlay() {
                player.play()
            }

            override fun onPause() {
                player.pause()
            }

            override fun onSeekTo(pos: Long) {
                player.seekTo(pos)
            }

            override fun onSkipToNext() {
                // Переключение на следующий трек, если есть очередь
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                }
            }

            override fun onSkipToPrevious() {
                // Переключение на предыдущий трек
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                }
            }

            override fun onStop() {
                player.stop()
                // Опционально: остановить сервис
                // stopSelf()
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(callback)
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
            sharedPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also { sharedPlayer = it }
        }
    }
}