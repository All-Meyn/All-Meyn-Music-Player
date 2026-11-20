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
Through Media3 (+ FFmpeg extension): MP3, AAC, FLAC, ALAC, Opus, Vorbis, WAV/AIFF, AC-3/E-AC-3,
DTS, TrueHD in MP3, MP4/M4A, FLAC, Ogg, WAV, Matroska/WebM, ADTS containers.

Through All-Meyn's own decoders (`app/src/main/cpp/decoder`, served to Media3 as RF64 WAV):
- **DSF / DFF (DSD64–DSD512):** with the USB driver on and a DSD-capable DAC, DSD goes out as
  **DoP** (bit-exact; the DAC plays native DSD, up to DoP256). Otherwise it's converted to
  176.4 kHz (or 192 kHz) PCM. DST-compressed DFF isn't supported.
- **APE (Monkey's Audio) and WavPack:** lossless, 16/24-bit and float, via a minimal FFmpeg build
  (LGPL) — see *Build*.

**Not yet supported:** WMA, Musepack, TAK, DST-compressed DFF.

## USB DAC
On Android 14+ the app asks the system for a bit-perfect mixer matching each track's sample rate
and output encoding (16-bit, or 32-bit float for high-res sources). If the DAC doesn't advertise a
matching mode, playback falls back to the normal mixer and the UI says so. On Android 13 and
older, playback always uses the normal mixer.

## Build
CI builds a debug APK on every push (see the Actions tab → artifact `all-meyn-player-debug`),
and runs the native host tests (`native-tests/run.sh`) and JVM unit tests.

Locally: open in Android Studio, or `./gradlew assembleDebug`. For APE/WavPack, first build the
minimal FFmpeg once (Linux/macOS, needs the NDK and `make`):

    scripts/build-ffmpeg.sh $ANDROID_HOME/ndk/27.0.12077973

Without it the app still builds and plays everything else.

## License note
The FFmpeg decoder dependency is GPL-3.0, so distributing this app means releasing it under a
GPL-compatible license.
