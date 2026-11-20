package com.allmeyn.player.playback

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.allmeyn.player.AllMeynApp
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.allmeyn.player.audio.AllMeynRenderersFactory
import com.allmeyn.player.decode.DecodeRegistry
import com.allmeyn.player.decode.DecodingDataSource
import com.allmeyn.player.decode.DsdOutput
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
            // DSF/DFF/APE/WavPack are decoded natively and fed to Media3 as WAV; DSD goes out as
            // native DSD or DoP while the USB driver is on, otherwise as PCM.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DecodingDataSource.Factory(this, DefaultDataSource.Factory(this)) {
                        DsdOutput(dop = driver.supportsDop, nativeMaxRate = driver.nativeDsdMaxRate)
                    },
                ),
            )
            .build()
        // Keeps "Play next" next while shuffle is on (Media3's default scatters added songs).
        player.setShuffleOrder(QueueShuffleOrder())

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
                // The user switched the driver off: don't grab the DAC back for this song.
                if (reason == UsbAudioDriver.Reason.DISABLED) autoEnabledFor = player.currentMediaItem?.localConfiguration?.uri
                // Unplugged mid-song: pause like a headphone unplug instead of jumping to the speaker.
                if (reason == UsbAudioDriver.Reason.DISCONNECTED) player.pause()
                if (player.mediaItemCount == 0 || reason == UsbAudioDriver.Reason.FAILED) return
                val uri = player.currentMediaItem?.localConfiguration?.uri
                val isDsd = uri != null && (DecodeRegistry.isDsd(uri) || looksLikeDsd(uri))
                // Native DSD on/off only matters for DSD; leave other songs alone.
                if (reason == UsbAudioDriver.Reason.DSD_MODE_CHANGED && !isDsd) {
                    DecodeRegistry.resetModes()
                    return
                }
                if (isDsd) {
                    // DSD must switch between native / DoP / PCM: reload so the file is decoded afresh.
                    reloadInPlace(player)
                } else {
                    // Seeking in place rebuilds the audio output, which then picks the new route.
                    player.seekTo(player.currentPosition)
                }
            }
        }
        driver.addListener(listener)
        driverListener = listener

        // DSD only plays as DSD through the driver: turn it on when a DSD song starts.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = maybeEnableDriverForDsd(player, driver)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = maybeEnableDriverForDsd(player, driver)
        })
    }

    /** The song the driver was last turned on for (or tried): don't retry the same one. */
    private var autoEnabledFor: Uri? = null

    /**
     * When a DSD song is about to play and the driver is off, turns it on (only if the DAC is
     * attached and permission was already given: never prompts). Playback pauses while the DAC is
     * taken over; the driver listener then re-opens the song as native DSD / DoP, and it resumes.
     */
    private fun maybeEnableDriverForDsd(player: ExoPlayer, driver: UsbAudioDriver) {
        if (!player.playWhenReady || !driver.autoEnableForDsd || driver.isActive) return
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        if (!(DecodeRegistry.isDsd(uri) || looksLikeDsd(uri)) || uri == autoEnabledFor) return
        if (!driver.canEnableWithoutPrompt()) return
        autoEnabledFor = uri
        player.pause()
        driver.enable { player.play() } // resumes on success, or as PCM if the driver failed
    }

    /** DSF/DFF by name (SAF document URIs end in the encoded file name). */
    private fun looksLikeDsd(uri: Uri): Boolean {
        val s = uri.toString().lowercase()
        return s.endsWith(".dsf") || s.endsWith(".dff")
    }

    /** Re-prepares the playlist at the same item and position (keeps play/pause state). */
    private fun reloadInPlace(player: ExoPlayer) {
        DecodeRegistry.resetModes()
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        player.setMediaItems(items, index, position)
        player.prepare()
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
