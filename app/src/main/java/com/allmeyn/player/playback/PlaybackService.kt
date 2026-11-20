package com.allmeyn.player.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.allmeyn.player.AllMeynApp
import com.allmeyn.player.audio.AllMeynRenderersFactory
import com.allmeyn.player.usb.UsbAudioDriver

/** Owns the player so music keeps playing in the background with a media notification. */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var driverModeListener: UsbAudioDriver.ModeListener? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AllMeynApp
        val player = ExoPlayer.Builder(this, AllMeynRenderersFactory(this, app.usbDac, app.usbDriver))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).build()

        // Taking the DAC from Android looks like a headphone unplug ("audio becoming noisy"),
        // which would auto-pause playback. Ignore it while our driver owns the DAC.
        val listener = UsbAudioDriver.ModeListener { active -> player.setHandleAudioBecomingNoisy(!active) }
        app.usbDriver.addModeListener(listener)
        driverModeListener = listener
        if (app.usbDriver.isActive) player.setHandleAudioBecomingNoisy(false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        driverModeListener?.let { (application as AllMeynApp).usbDriver.removeModeListener(it) }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
