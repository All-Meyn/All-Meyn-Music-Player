package com.allmeyn.player

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.allmeyn.player.audio.AllMeynRenderersFactory
import com.allmeyn.player.library.MusicLibrary
import com.allmeyn.player.library.PlaylistStore
import com.allmeyn.player.playback.ReplayGain
import com.allmeyn.player.plugin.LevelMeterPlugin
import com.allmeyn.player.plugin.PluginHost
import com.allmeyn.player.usb.UsbAudioDriver
import com.allmeyn.player.usb.UsbDacManager

@UnstableApi
class AllMeynApp : Application() {

    lateinit var usbDac: UsbDacManager
        private set

    /** Custom USB audio driver; app-scoped so a running stream survives activity recreation. */
    val usbDriver by lazy { UsbAudioDriver(this) }

    /** Music library: folders the user picked, scanned and cached. */
    val library by lazy { MusicLibrary(this) }

    /** User playlists (playlists.json). */
    val playlists by lazy { PlaylistStore(this) }

    /** Example capture plugin. Register your own plugin here too. */
    val levelMeter = LevelMeterPlugin()

    override fun onCreate() {
        super.onCreate()
        ReplayGain.init(this)
        usbDac = UsbDacManager(this, AllMeynRenderersFactory.FLOAT_OUTPUT).also { it.start() }
        PluginHost.register(levelMeter)
        // Re-enable the USB driver now if it was left on and the DAC is already plugged in.
        usbDriver.maybeAutoEnable(UsbAudioDriver.ATTACH_SETTLE_MS)
        // PluginHost.register(YourPlugin())
    }
}
