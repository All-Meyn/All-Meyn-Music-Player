#include "audio_devices.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <initguid.h>  // defines the GUIDs/PROPERTYKEYs below in this file
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mmdeviceapi.h>

namespace allmeyn {
namespace {

std::string utf8(const wchar_t* w) {
    if (!w) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s((size_t) n - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    return s;
}

template <class T> void release(T*& p) {
    if (p) { p->Release(); p = nullptr; }
}

/** COM for this thread, released when it goes out of scope (if this call initialised it). */
struct ComScope {
    bool owned;
    ComScope() { owned = SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED)); }
    ~ComScope() { if (owned) CoUninitialize(); }
};

void readMixFormat(IMMDevice* device, OutputDevice& out) {
    IAudioClient* client = nullptr;
    if (FAILED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**) &client))) return;
    WAVEFORMATEX* mix = nullptr;
    if (SUCCEEDED(client->GetMixFormat(&mix)) && mix) {
        out.mixRate = (int) mix->nSamplesPerSec;
        out.mixBits = mix->wBitsPerSample;
        out.mixChannels = mix->nChannels;
        CoTaskMemFree(mix);
    }
    release(client);
}

}  // namespace

std::vector<OutputDevice> listOutputDevices() {
    std::vector<OutputDevice> devices;
    ComScope com;
    IMMDeviceEnumerator* enumerator = nullptr;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
                                (void**) &enumerator))) {
        return devices;
    }
    std::string defaultId;
    IMMDevice* def = nullptr;
    if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &def))) {
        LPWSTR id = nullptr;
        if (SUCCEEDED(def->GetId(&id))) { defaultId = utf8(id); CoTaskMemFree(id); }
        release(def);
    }
    IMMDeviceCollection* collection = nullptr;
    if (SUCCEEDED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &collection))) {
        UINT count = 0;
        collection->GetCount(&count);
        for (UINT i = 0; i < count; i++) {
            IMMDevice* device = nullptr;
            if (FAILED(collection->Item(i, &device))) continue;
            OutputDevice d;
            LPWSTR id = nullptr;
            if (SUCCEEDED(device->GetId(&id))) { d.id = utf8(id); CoTaskMemFree(id); }
            IPropertyStore* props = nullptr;
            if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &props))) {
                PROPVARIANT name;
                PropVariantInit(&name);
                if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &name)) && name.vt == VT_LPWSTR) d.name = utf8(name.pwszVal);
                PropVariantClear(&name);
                release(props);
            }
            d.isDefault = !d.id.empty() && d.id == defaultId;
            readMixFormat(device, d);
            devices.push_back(d);
            release(device);
        }
        release(collection);
    }
    release(enumerator);
    return devices;
}

}  // namespace allmeyn
