package com.allmeyn.player

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.allmeyn.player.audio.AllMeynRenderersFactory
import com.allmeyn.player.plugin.LevelMeterPlugin
import com.allmeyn.player.plugin.PluginHost
import com.allmeyn.player.usb.UsbAudioProbe
import com.allmeyn.player.usb.UsbDacManager

@UnstableApi
class AllMeynApp : Application() {

    lateinit var usbDac: UsbDacManager
        private set

    /** Custom USB audio driver; app-scoped so a running stream survives activity recreation. */
    val usbDriver by lazy { UsbAudioProbe(this) }

    /** Example capture plugin. Register your own plugin here too. */
    val levelMeter = LevelMeterPlugin()

    override fun onCreate() {
        super.onCreate()
        usbDac = UsbDacManager(this, AllMeynRenderersFactory.FLOAT_OUTPUT).also { it.start() }
        PluginHost.register(levelMeter)
        // PluginHost.register(YourPlugin())
    }
}
