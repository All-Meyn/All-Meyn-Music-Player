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

constexpr REFERENCE_TIME kBufferDuration = 2000000;  // 200 ms (100 ns units)

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

std::string hr(const char* what, HRESULT h) {
    char buf[96];
    snprintf(buf, sizeof buf, "%s failed (0x%08lX)", what, (unsigned long) h);
    return buf;
}

template <class T> void release(T*& p) {
    if (p) { p->Release(); p = nullptr; }
}

}  // namespace

WasapiOutput::~WasapiOutput() {
    quit_ = true;
    if (thread_.joinable()) thread_.join();
}

bool WasapiOutput::open(int sampleRate, int channels, PullFn pull, std::string& error) {
    pull_ = std::move(pull);
    rate_ = sampleRate;
    channels_ = channels;
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

std::string WasapiOutput::description() const {
    const int mix = mixRate_.load();
    std::string s = deviceName_.empty() ? std::string("Default output") : deviceName_;
    if (mix > 0 && mix != rate_) {
        char buf[64];
        snprintf(buf, sizeof buf, " · Windows mixer converts to %.1f kHz", mix / 1000.0);
        s += buf;
    } else {
        s += " · Windows mixer (shared)";
    }
    return s;
}

// The render thread owns every COM object; the player only flips wantRunning_.
void WasapiOutput::run(std::shared_ptr<OpenResult> result) {
    const bool comOwned = SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
    IMMDeviceEnumerator* enumerator = nullptr;
    IMMDevice* device = nullptr;
    IAudioClient* client = nullptr;
    IAudioRenderClient* render = nullptr;
    HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    std::string error;
    UINT32 bufferFrames = 0;

    HRESULT h = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**) &enumerator);
    if (FAILED(h)) error = hr("Audio device list", h);
    if (error.empty() && FAILED(h = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device))) error = "No audio output device";
    if (error.empty()) {
        IPropertyStore* props = nullptr;
        if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &props))) {
            PROPVARIANT name;
            PropVariantInit(&name);
            if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &name)) && name.vt == VT_LPWSTR) deviceName_ = utf8(name.pwszVal);
            PropVariantClear(&name);
            release(props);
        }
        if (FAILED(h = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**) &client))) error = hr("Opening the output", h);
    }
    if (error.empty()) {
        WAVEFORMATEX* mix = nullptr;
        if (SUCCEEDED(client->GetMixFormat(&mix)) && mix) { mixRate_ = (int) mix->nSamplesPerSec; CoTaskMemFree(mix); }
        WAVEFORMATEXTENSIBLE fmt = {};
        fmt.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
        fmt.Format.nChannels = (WORD) channels_;
        fmt.Format.nSamplesPerSec = (DWORD) rate_;
        fmt.Format.wBitsPerSample = 32;
        fmt.Format.nBlockAlign = (WORD) (channels_ * 4);
        fmt.Format.nAvgBytesPerSec = (DWORD) rate_ * fmt.Format.nBlockAlign;
        fmt.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
        fmt.Samples.wValidBitsPerSample = 32;
        fmt.dwChannelMask = channelMask(channels_);
        fmt.SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
        // Shared mode; Windows converts our rate/channels to the mixer's (W4 adds exclusive mode).
        const DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        h = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, kBufferDuration, 0, (WAVEFORMATEX*) &fmt, nullptr);
        if (FAILED(h)) error = hr("Starting the output stream", h);
    }
    if (error.empty() && FAILED(h = client->SetEventHandle(event))) error = hr("Output event", h);
    if (error.empty() && FAILED(h = client->GetBufferSize(&bufferFrames))) error = hr("Output buffer", h);
    if (error.empty() && FAILED(h = client->GetService(__uuidof(IAudioRenderClient), (void**) &render))) error = hr("Output renderer", h);

    result->done.set_value(error);
    if (error.empty()) {
        DWORD taskIndex = 0;
        HANDLE mmcss = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
        bool running = false;
        std::vector<float> scratch;
        while (!quit_) {
            const bool want = wantRunning_.load();
            if (want != running) {
                if (want) {
                    // Fill the buffer before starting so the first period isn't silence.
                    SetEvent(event);
                    client->Start();
                } else {
                    client->Stop();
                }
                running = want;
            }
            if (WaitForSingleObject(event, 50) != WAIT_OBJECT_0 && !running) {
                UINT32 pad = 0;
                if (SUCCEEDED(client->GetCurrentPadding(&pad))) buffered_ = pad;
                continue;
            }
            UINT32 padding = 0;
            if (FAILED(client->GetCurrentPadding(&padding))) break;  // device gone
            const UINT32 free = bufferFrames - padding;
            if (free > 0) {
                BYTE* data = nullptr;
                if (SUCCEEDED(render->GetBuffer(free, &data))) {
                    float* out = reinterpret_cast<float*>(data);
                    const int got = running ? pull_(out, (int) free) : 0;
                    if (got < (int) free) {
                        memset(out + (size_t) got * channels_, 0, (size_t) (free - got) * channels_ * sizeof(float));
                    }
                    render->ReleaseBuffer(free, got == 0 ? AUDCLNT_BUFFERFLAGS_SILENT : 0);
                    padding += free;
                }
            }
            buffered_ = padding;
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
