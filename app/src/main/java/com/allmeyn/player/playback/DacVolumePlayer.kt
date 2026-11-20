package com.allmeyn.player.playback

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.allmeyn.player.usb.UsbAudioDriver
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wraps the player so that, while the custom USB driver is on and the DAC has hardware volume,
 * the MediaSession reports "remote" volume. Android then sends the phone's volume keys and the
 * system volume slider here, and they move the DAC's own volume. The samples are untouched, so
 * playback stays bit-perfect. When the driver is off, everything falls through to the player.
 *
 * All methods run on the main (application) looper.
 */
@UnstableApi
class DacVolumePlayer(player: Player, private val driver: UsbAudioDriver) : ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var volume: UsbAudioDriver.VolumeState? = driver.state.value.volume

    private val driverListener = object : UsbAudioDriver.Listener {
        override fun onVolumeChanged(volume: UsbAudioDriver.VolumeState?) {
            val infoChanged = (this@DacVolumePlayer.volume == null) != (volume == null) ||
                this@DacVolumePlayer.volume?.maxStep != volume?.maxStep
            this@DacVolumePlayer.volume = volume
            notifyVolume(infoChanged)
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        driver.addListener(driverListener)
    }

    fun release() {
        driver.removeListener(driverListener)
    }

    private val usesDacVolume get() = volume != null

    override fun getDeviceInfo(): DeviceInfo {
        val v = volume ?: return super.getDeviceInfo()
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(v.maxStep)
            .build()
    }

    override fun getDeviceVolume(): Int = volume?.step ?: super.getDeviceVolume()
    override fun isDeviceMuted(): Boolean = volume?.muted ?: super.isDeviceMuted()

    @Deprecated("Deprecated in Player")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (usesDacVolume) driver.setVolumeStep(volume) else super.setDeviceVolume(volume, flags)
    }

    @Deprecated("Deprecated in Player")
    override fun increaseDeviceVolume() = increaseDeviceVolume(0)

    override fun increaseDeviceVolume(flags: Int) {
        if (usesDacVolume) driver.adjustVolume(+1) else super.increaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Player")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    override fun decreaseDeviceVolume(flags: Int) {
        if (usesDacVolume) driver.adjustVolume(-1) else super.decreaseDeviceVolume(flags)
    }

    @Deprecated("Deprecated in Player")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, 0)

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (usesDacVolume) driver.setMuted(muted) else super.setDeviceMuted(muted, flags)
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (!usesDacVolume) return base
        return base.buildUpon().addAll(*DAC_VOLUME_COMMANDS).build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        (usesDacVolume && command in DAC_VOLUME_COMMANDS) || super.isCommandAvailable(command)

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }

    private fun notifyVolume(infoChanged: Boolean) {
        val flags = FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED)
        if (infoChanged) flags.addAll(Player.EVENT_DEVICE_INFO_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
        val events = Player.Events(flags.build())
        val info = deviceInfo
        val commands = availableCommands
        for (l in listeners) {
            if (infoChanged) {
                l.onDeviceInfoChanged(info)
                l.onAvailableCommandsChanged(commands)
            }
            l.onDeviceVolumeChanged(deviceVolume, isDeviceMuted)
            l.onEvents(this, events)
        }
    }

    private companion object {
        val DAC_VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}
