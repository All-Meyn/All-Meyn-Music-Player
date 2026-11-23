#include "endpoint_volume.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <endpointvolume.h>
#include <mmdeviceapi.h>

namespace allmeyn {
namespace {

std::wstring wide(const std::string& s) {
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    std::wstring w(n > 0 ? (size_t) n : 1, L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, &w[0], n);
    return w;
}

/** COM for this call's thread (the app calls from its own worker threads). */
struct ComScope {
    bool owned;
    ComScope() { owned = SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED)); }
    ~ComScope() { if (owned) CoUninitialize(); }
};

/** The device's IAudioEndpointVolume (caller releases), or null. */
IAudioEndpointVolume* open(const std::string& deviceId) {
    IMMDeviceEnumerator* e = nullptr;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**) &e))) return nullptr;
    IMMDevice* d = nullptr;
    HRESULT hr = deviceId.empty() ? e->GetDefaultAudioEndpoint(eRender, eConsole, &d) : e->GetDevice(wide(deviceId).c_str(), &d);
    e->Release();
    if (FAILED(hr) || !d) return nullptr;
    IAudioEndpointVolume* v = nullptr;
    hr = d->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr, (void**) &v);
    d->Release();
    return SUCCEEDED(hr) ? v : nullptr;
}

}  // namespace

EndpointVolume readEndpointVolume(const std::string& deviceId) {
    EndpointVolume out;
    ComScope com;
    IAudioEndpointVolume* v = open(deviceId);
    if (!v) return out;
    float level = 0, db = 0, minDb = 0, maxDb = 0, step = 0;
    BOOL mute = FALSE;
    DWORD hw = 0;
    out.ok = SUCCEEDED(v->GetMasterVolumeLevelScalar(&level)) && SUCCEEDED(v->GetMasterVolumeLevel(&db)) &&
             SUCCEEDED(v->GetVolumeRange(&minDb, &maxDb, &step));
    v->GetMute(&mute);
    v->QueryHardwareSupport(&hw);
    v->Release();
    out.level = level;
    out.db = db;
    out.minDb = minDb;
    out.maxDb = maxDb;
    out.stepDb = step;
    out.muted = mute != FALSE;
    out.hardware = (hw & ENDPOINT_HARDWARE_SUPPORT_VOLUME) != 0;
    return out;
}

bool setEndpointVolume(const std::string& deviceId, float level) {
    ComScope com;
    IAudioEndpointVolume* v = open(deviceId);
    if (!v) return false;
    const bool ok = SUCCEEDED(v->SetMasterVolumeLevelScalar(level < 0 ? 0 : level > 1 ? 1 : level, nullptr));
    v->Release();
    return ok;
}

bool setEndpointMute(const std::string& deviceId, bool muted) {
    ComScope com;
    IAudioEndpointVolume* v = open(deviceId);
    if (!v) return false;
    const bool ok = SUCCEEDED(v->SetMute(muted ? TRUE : FALSE, nullptr));
    v->Release();
    return ok;
}

}  // namespace allmeyn
