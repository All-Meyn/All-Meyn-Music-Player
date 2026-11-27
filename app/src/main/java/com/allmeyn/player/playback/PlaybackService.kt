package com.allmeyn.player.playback

import android.app.PendingIntent
import android.content.Intent
import com.allmeyn.player.ui.MainActivity
import android.content.SharedPreferences
import androidx.media3.common.Tracks
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
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.allmeyn.player.dsp.AirSource
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspSettings
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
                        // With the DSP on, DSD is decoded to PCM so the DSP can process it.
                        val dsp = DspSettings.live.processes
                        DsdOutput(
                            dop = driver.supportsDop && !dsp,
                            nativeMaxRate = if (dsp) 0 else driver.nativeDsdMaxRate,
                            // ReplayGain only for Android output, never through the bit-perfect driver.
                            replayGain = if (driver.isActive) ReplayGain.Mode.OFF else ReplayGain.liveMode,
                        )
                    },
                ),
            )
            .build()
        // Keeps "Play next" next while shuffle is on (Media3's default scatters added songs).
        player.setShuffleOrder(QueueShuffleOrder())

        // Volume keys / system volume slider drive the DAC's hardware volume while the driver is on.
        val sessionPlayer = DacVolumePlayer(player, driver)
        dacVolumePlayer = sessionPlayer
        session = MediaSession.Builder(this, sessionPlayer)
            // Tapping the media notification (or the lock screen player) opens the app on Now playing.
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

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
                // Songs our decoders turned into PCM with ReplayGain baked in must be decoded afresh
                // too, so the driver gets them untouched (and Android output gets the gain back).
                val gainBaked = uri != null && ReplayGain.liveMode != ReplayGain.Mode.OFF && isDecodedByUs(uri)
                if (isDsd || gainBaked) {
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

        // DSP switched on/off while DSD plays: re-open it as PCM (DSP on) or native DSD / DoP (off).
        val dspListener: (DspParams, DspParams) -> Unit = { old, new ->
            if (old.processes != new.processes) mainHandler.post {
                val uri = player.currentMediaItem?.localConfiguration?.uri
                if (uri != null && (DecodeRegistry.isDsd(uri) || looksLikeDsd(uri))) reloadInPlace(player)
                else DecodeRegistry.resetModes()
            }
        }
        DspSettings.addListener(dspListener)
        this.dspListener = dspListener

        // Each song's codec and bitrate as it starts decoding: tells Air whether it's lossy and
        // roughly where the encoder cut the treble.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) = AirSource.set(format)
        })

        // DSD only plays as DSD through the driver: turn it on when a DSD song starts.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = maybeEnableDriverForDsd(player, driver)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = maybeEnableDriverForDsd(player, driver)
            // A new song's tracks (and tags) are known: update the ReplayGain label.
            override fun onTracksChanged(tracks: Tracks) = updateReplayGainLabel(player)
        })
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ReplayGain.KEY) onReplayGainModeChanged(player, driver)
        }
        ReplayGain.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        replayGainPrefListener = prefListener
    }

    // ---- ReplayGain -------------------------------------------------------------------------
    // The gain itself is applied to the samples (CaptureAudioSink / DecodingDataSource), exactly
    // from each song's first sample and only for Android output. Here: the UI label, and
    // re-opening a song whose decoded samples must change.

    private var replayGainPrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun onReplayGainModeChanged(player: ExoPlayer, driver: UsbAudioDriver) {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        // Our decoders bake the gain into the samples: re-open the song so it changes now.
        // Media3-decoded songs pick the new mode up with their next buffer.
        if (uri != null && !driver.isActive && isDecodedByUs(uri)) reloadInPlace(player)
        updateReplayGainLabel(player)
    }

    private fun updateReplayGainLabel(player: ExoPlayer) {
        val mode = ReplayGain.liveMode
        val uri = player.currentMediaItem?.localConfiguration?.uri
        if (mode == ReplayGain.Mode.OFF || uri == null) {
            ReplayGain.applied = null
            return
        }
        val format = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val gains = ReplayGain.decodedGains[uri.toString()] ?: ReplayGain.fromMetadata(format?.metadata)
        val db = ReplayGain.pick(gains, mode)
        ReplayGain.applied = if (db == null) "no ReplayGain tag" else
            "%+.1f dB (%s)".format(db, mode.name.lowercase()) + if (db > 0) " · capped at 0 dB" else ""
    }

    /** APE / WavPack / DSF / DFF: decoded by us, so their PCM (and ReplayGain) comes from DecodingDataSource. */
    private fun isDecodedByUs(uri: Uri): Boolean {
        val ext = uri.toString().substringAfterLast('.', "").lowercase()
        return ext in setOf("ape", "wv", "dsf", "dff") || DecodeRegistry.isDsd(uri)
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

    private var dspListener: ((DspParams, DspParams) -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onDestroy() {
        dspListener?.let { DspSettings.removeListener(it) }
        replayGainPrefListener?.let { ReplayGain.prefs(this).unregisterOnSharedPreferenceChangeListener(it) }
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
