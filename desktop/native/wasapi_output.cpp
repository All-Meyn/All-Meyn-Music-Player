#include "wasapi_output.h"

#include <future>
#include <vector>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <audioclient.h>
#include <avrt.h>
#include <functiondiscoverykeys_devpkey.h>
#include <ks.h>
#include <ksmedia.h>
#include <mmdeviceapi.h>

namespace allmeyn {

struct OpenResult {
    std::promise<std::string> done;  // "" = opened, else the error
};

namespace {

constexpr REFERENCE_TIME kSharedBuffer = 2000000;  // 200 ms (100 ns units)

DWORD channelMask(int channels) {
    switch (channels) {
        case 1: return KSAUDIO_SPEAKER_MONO;
        case 2: return KSAUDIO_SPEAKER_STEREO;
        case 4: return KSAUDIO_SPEAKER_QUAD;
        case 6: return KSAUDIO_SPEAKER_5POINT1;
        case 8: return KSAUDIO_SPEAKER_7POINT1_SURROUND;
        default: return 0;
    }
}

std::string utf8(const wchar_t* w) {
    if (!w) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s((size_t) n - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring wide(const std::string& s) {
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    std::wstring w(n > 0 ? (size_t) n : 1, L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, &w[0], n);
    return w;
}

std::string hr(const char* what, HRESULT h) {
    char buf[112];
    snprintf(buf, sizeof buf, "%s failed (0x%08lX)", what, (unsigned long) h);
    return buf;
}

std::string khz(int rate) {
    char buf[32];
    if (rate % 1000 == 0) snprintf(buf, sizeof buf, "%d kHz", rate / 1000);
    else snprintf(buf, sizeof buf, "%.1f kHz", rate / 1000.0);
    return buf;
}

template <class T> void release(T*& p) {
    if (p) { p->Release(); p = nullptr; }
}

WAVEFORMATEXTENSIBLE makeFormat(int rate, int channels, SampleFormat f) {
    WAVEFORMATEXTENSIBLE x = {};
    const int bytes = bytesPerSample(f);
    x.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    x.Format.nChannels = (WORD) channels;
    x.Format.nSamplesPerSec = (DWORD) rate;
    x.Format.wBitsPerSample = (WORD) (bytes * 8);
    x.Format.nBlockAlign = (WORD) (channels * bytes);
    x.Format.nAvgBytesPerSec = (DWORD) rate * x.Format.nBlockAlign;
    x.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    x.Samples.wValidBitsPerSample = (WORD) (f == SampleFormat::Float32 ? 32 : validBits(f));
    x.dwChannelMask = channelMask(channels);
    x.SubFormat = f == SampleFormat::Float32 ? KSDATAFORMAT_SUBTYPE_IEEE_FLOAT : KSDATAFORMAT_SUBTYPE_PCM;
    return x;
}

/** Exclusive formats to try, best first, for a source of [bits] (0 = lossy / already float). */
std::vector<SampleFormat> candidates(int bits, bool bitExact) {
    using F = SampleFormat;
    if (bitExact) return {F::Int24In32, F::Int24Packed, F::Int32};  // DoP: 24 valid bits (or 32)
    if (bits == 16) return {F::Int16, F::Int24In32, F::Int24Packed, F::Int32};
    if (bits == 24) return {F::Int24In32, F::Int24Packed, F::Int32};
    return {F::Int32, F::Int24In32, F::Int24Packed, F::Float32, F::Int16};
}

const char* formatName(SampleFormat f) {
    switch (f) {
        case SampleFormat::Int16: return "16-bit";
        case SampleFormat::Int24In32: return "24-bit";
        case SampleFormat::Int24Packed: return "24-bit";
        case SampleFormat::Int32: return "32-bit";
        default: return "32-bit float";
    }
}

}  // namespace

WasapiOutput::~WasapiOutput() {
    quit_ = true;
    if (thread_.joinable()) thread_.join();
}

bool WasapiOutput::open(const StreamFormat& format, PullFn pull, std::string& error) {
    format_ = format;
    pull_ = std::move(pull);
    auto result = std::make_shared<OpenResult>();
    auto future = result->done.get_future();
    thread_ = std::thread(&WasapiOutput::run, this, result);
    error = future.get();
    if (!error.empty()) {
        if (thread_.joinable()) thread_.join();
        return false;
    }
    return true;
}

// The render thread owns every COM object; the player only flips wantRunning_.
void WasapiOutput::run(std::shared_ptr<OpenResult> result) {
    const bool comOwned = SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
    IMMDeviceEnumerator* enumerator = nullptr;
    IMMDevice* device = nullptr;
    IAudioClient* client = nullptr;
    IAudioRenderClient* render = nullptr;
    HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    std::string error, deviceName, fallbackNote;
    UINT32 bufferFrames = 0;
    bool exclusive = false;
    SampleFormat sample = SampleFormat::Float32;
    const int rate = format_.sampleRate, channels = format_.channels;

    HRESULT h = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**) &enumerator);
    if (FAILED(h)) error = hr("Audio device list", h);
    if (error.empty()) {
        if (!config_.deviceId.empty()) enumerator->GetDevice(wide(config_.deviceId).c_str(), &device);
        if (!device && FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device))) error = "No audio output device";
    }
    if (error.empty()) {
        IPropertyStore* props = nullptr;
        if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &props))) {
            PROPVARIANT name;
            PropVariantInit(&name);
            if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &name)) && name.vt == VT_LPWSTR) deviceName = utf8(name.pwszVal);
            PropVariantClear(&name);
            release(props);
        }
        if (FAILED(h = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**) &client))) error = hr("Opening the output", h);
    }

    // ---- exclusive: the song's rate, the best integer format the device takes ----
    if (error.empty() && config_.exclusive) {
        WAVEFORMATEXTENSIBLE fmt = {};
        bool found = false;
        for (SampleFormat f : candidates(format_.bits, format_.bitExact)) {
            fmt = makeFormat(rate, channels, f);
            if (client->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, (WAVEFORMATEX*) &fmt, nullptr) == S_OK) {
                sample = f;
                found = true;
                break;
            }
        }
        if (!found) {
            fallbackNote = "the output doesn't take " + khz(rate) + " in exclusive mode";
        } else {
            REFERENCE_TIME defPeriod = 0, minPeriod = 0;
            client->GetDevicePeriod(&defPeriod, &minPeriod);
            h = client->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, defPeriod, defPeriod, (WAVEFORMATEX*) &fmt, nullptr);
            if (h == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
                // The period must match the device's buffer alignment: retry with the aligned size.
                UINT32 frames = 0;
                client->GetBufferSize(&frames);
                release(client);
                const REFERENCE_TIME aligned = (REFERENCE_TIME) (10000.0 * 1000 * frames / rate + 0.5);
                if (SUCCEEDED(h = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**) &client))) {
                    h = client->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, aligned, aligned, (WAVEFORMATEX*) &fmt, nullptr);
                }
            }
            if (SUCCEEDED(h)) {
                exclusive = true;
            } else {
                fallbackNote = h == AUDCLNT_E_DEVICE_IN_USE ? "another app is using the output exclusively"
                             : h == AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED ? "exclusive mode is turned off for this output in Windows"
                             : hr("exclusive mode", h);
                release(client);
                if (FAILED(h = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**) &client))) error = hr("Opening the output", h);
            }
        }
        if (!exclusive && format_.bitExact) error = "Can't play bit-exact: " + fallbackNote;
    } else if (error.empty() && format_.bitExact) {
        error = "Can't play bit-exact in shared mode";
    }

    // ---- shared: Windows converts to its mixer format ----
    int mixRate = 0;
    if (error.empty() && !exclusive) {
        WAVEFORMATEX* mix = nullptr;
        if (SUCCEEDED(client->GetMixFormat(&mix)) && mix) { mixRate = (int) mix->nSamplesPerSec; CoTaskMemFree(mix); }
        sample = SampleFormat::Float32;
        WAVEFORMATEXTENSIBLE fmt = makeFormat(rate, channels, sample);
        const DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        h = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, kSharedBuffer, 0, (WAVEFORMATEX*) &fmt, nullptr);
        if (FAILED(h)) error = hr("Starting the output stream", h);
    }
    if (error.empty() && FAILED(h = client->SetEventHandle(event))) error = hr("Output event", h);
    if (error.empty() && FAILED(h = client->GetBufferSize(&bufferFrames))) error = hr("Output buffer", h);
    if (error.empty() && FAILED(h = client->GetService(__uuidof(IAudioRenderClient), (void**) &render))) error = hr("Output renderer", h);

    if (error.empty()) {
        std::string d = deviceName.empty() ? std::string("Default output") : deviceName;
        if (exclusive) {
            d += " · Exclusive · " + khz(rate) + " · " + formatName(sample);
            const bool integerSource = format_.bits > 0;
            if (format_.bitExact) d += " · DoP, bit-perfect";
            else if (integerSource && sample != SampleFormat::Float32 && validBits(sample) >= format_.bits) d += " · bit-perfect";
        } else {
            d += mixRate > 0 && mixRate != rate ? " · Windows mixer converts to " + khz(mixRate) : std::string(" · Windows mixer (shared)");
            if (!fallbackNote.empty()) d += " (exclusive unavailable: " + fallbackNote + ")";
        }
        description_ = d;
    }
    result->done.set_value(error);

    if (error.empty()) {
        DWORD taskIndex = 0;
        HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
        bool running = false;
        std::vector<float> scratch((size_t) bufferFrames * channels);
        const int bytesPerFrame = bytesPerSample(sample) * channels;
        // Fills [frames] at [data]: pulled audio (converted to the device format), then silence.
        auto fill = [&](BYTE* data, UINT32 frames) -> int {
            const int got = running ? pull_(scratch.data(), (int) frames) : 0;
            if (got > 0) convertFromFloat(scratch.data(), data, (size_t) got * channels, sample);
            if (got < (int) frames) memset(data + (size_t) got * bytesPerFrame, 0, (size_t) (frames - got) * bytesPerFrame);
            return got;
        };
        while (!quit_) {
            const bool want = wantRunning_.load();
            if (want != running) {
                running = want;
                if (running) {
                    // Fill the buffer before starting, so the first period isn't silence.
                    if (exclusive) {
                        BYTE* data = nullptr;
                        if (SUCCEEDED(render->GetBuffer(bufferFrames, &data))) render->ReleaseBuffer(bufferFrames, fill(data, bufferFrames) == 0 ? AUDCLNT_BUFFERFLAGS_SILENT : 0);
                    } else {
                        SetEvent(event);
                    }
                    client->Start();
                } else {
                    client->Stop();
                }
            }
            if (WaitForSingleObject(event, 50) != WAIT_OBJECT_0) {
                if (!running) {
                    UINT32 pad = 0;
                    if (SUCCEEDED(client->GetCurrentPadding(&pad))) buffered_ = pad;
                }
                continue;
            }
            if (exclusive) {
                // Event-driven exclusive: each event wants one whole buffer.
                BYTE* data = nullptr;
                if (SUCCEEDED(render->GetBuffer(bufferFrames, &data))) {
                    const int got = fill(data, bufferFrames);
                    render->ReleaseBuffer(bufferFrames, got == 0 ? AUDCLNT_BUFFERFLAGS_SILENT : 0);
                }
                buffered_ = (int64_t) bufferFrames * 2;  // this buffer and the one being played
            } else {
                UINT32 padding = 0;
                if (FAILED(client->GetCurrentPadding(&padding))) break;  // device gone
                const UINT32 free = bufferFrames - padding;
                if (free > 0) {
                    BYTE* data = nullptr;
                    if (SUCCEEDED(render->GetBuffer(free, &data))) {
                        const int got = fill(data, free);
                        render->ReleaseBuffer(free, got == 0 ? AUDCLNT_BUFFERFLAGS_SILENT : 0);
                        padding += free;
                    }
                }
                buffered_ = padding;
            }
        }
        client->Stop();
        if (mmcss) AvRevertMmThreadCharacteristics(mmcss);
    }
    release(render);
    release(client);
    release(device);
    release(enumerator);
    CloseHandle(event);
    if (comOwned) CoUninitialize();
}

}  // namespace allmeyn
