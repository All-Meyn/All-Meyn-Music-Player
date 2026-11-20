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
    private var dacVolumePlayer: DacVolumePlayer? = null
    private var driverListener: UsbAudioDriver.Listener? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AllMeynApp
        val driver = app.usbDriver
        val player = ExoPlayer.Builder(this, AllMeynRenderersFactory(this, app.usbDac, driver))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(!driver.isActive)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Volume keys / system volume slider drive the DAC's hardware volume while the driver is on.
        val sessionPlayer = DacVolumePlayer(player, driver)
        dacVolumePlayer = sessionPlayer
        session = MediaSession.Builder(this, sessionPlayer).build()

        val listener = object : UsbAudioDriver.Listener {
            // Taking the DAC from Android looks like a headphone unplug ("audio becoming noisy"),
            // which would auto-pause playback. Ignore it while our driver owns the DAC.
            override fun beforeTakeover() = player.setHandleAudioBecomingNoisy(false)

            override fun onDriverStateChanged(active: Boolean, reason: UsbAudioDriver.Reason) {
                player.setHandleAudioBecomingNoisy(!active)
                // Unplugged mid-song: pause like a headphone unplug instead of jumping to the speaker.
                if (reason == UsbAudioDriver.Reason.DISCONNECTED) player.pause()
                // Seeking in place rebuilds the audio output, which then picks the new route.
                if (player.mediaItemCount > 0 && reason != UsbAudioDriver.Reason.FAILED) {
                    player.seekTo(player.currentPosition)
                }
            }
        }
        driver.addListener(listener)
        driverListener = listener
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val driver = (application as AllMeynApp).usbDriver
        driverListener?.let { driver.removeListener(it) }
        dacVolumePlayer?.detachFromDriver()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
