# All-Meyn Player

An Android music player that hands every decoded audio buffer to your own plugin code before it
reaches the output, with bit-perfect playback to USB DACs on Android 14+.

## Stack
- Kotlin, Jetpack Compose (Material 3)
- AndroidX Media3 1.9 (ExoPlayer + MediaSession, background playback)
- FFmpeg audio decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`, **GPL-3.0**)
- minSdk 26, targetSdk 36

## Layout
| Path | What it does |
|---|---|
| `plugin/AudioCapturePlugin.kt` | The plugin interface: `onConfigure`, `onBuffer`, `onFlush`, `onRelease` |
| `plugin/PluginHost.kt` | Registers plugins and fans audio out to them (no per-buffer allocation) |
| `plugin/LevelMeterPlugin.kt` | Example plugin: peak meter |
| `audio/CaptureAudioSink.kt` | Wraps Media3's AudioSink and taps each PCM buffer |
| `audio/AllMeynRenderersFactory.kt` | FFmpeg-preferred decoding, float output for high-res, installs the tap |
| `usb/UsbDacManager.kt` | Detects USB DACs; requests bit-perfect mixer per track on Android 14+ |
| `playback/PlaybackService.kt` | MediaSessionService that owns the player |
| `ui/MainActivity.kt` | Minimal UI: add files, transport controls, DAC + plugin status |

## Writing a plugin
```kotlin
class MyPlugin : AudioCapturePlugin {
    override val name = "My plugin"
    override fun onConfigure(format: PcmFormat) { /* allocate ring buffer for this format */ }
    override fun onBuffer(buffer: ByteBuffer, presentationTimeUs: Long) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        // copy buffer[position..limit) into your ring buffer; process on your own thread
    }
}
```
Register it in `AllMeynApp.onCreate()` with `PluginHost.register(MyPlugin())`.

`onBuffer` runs on the playback thread: no blocking, allocation or I/O. Audio arrives in the
decoder's native format (16-bit, 24-bit packed, 32-bit or float), before resampling.

## Format support
Decoding (FFmpeg + MediaCodec): MP3, AAC, FLAC, ALAC, Opus, Vorbis, WAV/AIFF PCM, AC-3/E-AC-3,
DTS, TrueHD and more. Containers come from Media3's extractors: MP3, MP4/M4A, FLAC, Ogg, WAV,
Matroska/WebM, ADTS, AMR, FLV.

**Not yet supported** (need extra extractors): APE, WavPack, DSD (DSF/DFF), WMA, Musepack, TAK.

## USB DAC
On Android 14+ the app asks the system for a bit-perfect mixer matching each track's sample rate
and output encoding (16-bit, or 32-bit float for high-res sources). If the DAC doesn't advertise a
matching mode, playback falls back to the normal mixer and the UI says so. On Android 13 and
older, playback always uses the normal mixer.

## Build
CI builds a debug APK on every push (see the Actions tab → artifact `all-meyn-player-debug`).
Locally: open in Android Studio, or `./gradlew assembleDebug`.

## License note
The FFmpeg decoder dependency is GPL-3.0, so distributing this app means releasing it under a
GPL-compatible license.
