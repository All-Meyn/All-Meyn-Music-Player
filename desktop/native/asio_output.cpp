#include "asio_output.h"

#include <cstdio>
#include <future>
#include <mutex>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <objbase.h>

#include "core/source.h"
#include "iasiodrv.h"

namespace allmeyn {

struct AsioOpenResult {
    std::promise<std::string> done;  // "" = opened, else the error
};

namespace {

// ASIO callbacks carry no context: the one open output.
std::mutex g_activeLock;
std::atomic<AsioOutput*> g_active{nullptr};

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

std::string khz(int rate) {
    char buf[32];
    if (rate % 1000 == 0) snprintf(buf, sizeof buf, "%d kHz", rate / 1000);
    else snprintf(buf, sizeof buf, "%.1f kHz", rate / 1000.0);
    return buf;
}

const wchar_t* kAsioKey = L"SOFTWARE\\ASIO";

bool driverClsid(const std::string& name, CLSID& clsid) {
    wchar_t value[64];
    DWORD size = sizeof value;
    const std::wstring key = std::wstring(kAsioKey) + L"\\" + wide(name).c_str();
    if (RegGetValueW(HKEY_LOCAL_MACHINE, key.c_str(), L"CLSID", RRF_RT_REG_SZ, nullptr, value, &size) != ERROR_SUCCESS) return false;
    return SUCCEEDED(CLSIDFromString(value, &clsid));
}

/** ASIO PCM sample types we convert to (all little-endian, the x64 types). */
bool pcmFormat(ASIOSampleType t, SampleFormat& f) {
    switch (t) {
        case ASIOSTInt16LSB: f = SampleFormat::Int16; return true;
        case ASIOSTInt24LSB: f = SampleFormat::Int24Packed; return true;
        case ASIOSTInt32LSB: f = SampleFormat::Int32; return true;
        case ASIOSTFloat32LSB: f = SampleFormat::Float32; return true;
        default: return false;
    }
}

const char* formatName(SampleFormat f) {
    switch (f) {
        case SampleFormat::Int16: return "16-bit";
        case SampleFormat::Int24Packed: return "24-bit";
        case SampleFormat::Int32: return "32-bit";
        default: return "32-bit float";
    }
}

void cbBufferSwitch(long index, ASIOBool) {
    if (AsioOutput* o = g_active.load()) o->onBufferSwitch(index);
}
ASIOTime* cbBufferSwitchTimeInfo(ASIOTime* params, long index, ASIOBool direct) {
    cbBufferSwitch(index, direct);
    return params;
}
void cbSampleRateDidChange(ASIOSampleRate) {}
long cbAsioMessage(long selector, long value, void*, double*) {
    switch (selector) {
        case kAsioSelectorSupported:
            return value == kAsioEngineVersion || value == kAsioSupportsTimeInfo ? 1 : 0;
        case kAsioEngineVersion: return 2;
        case kAsioSupportsTimeInfo: return 0;  // plain bufferSwitch is all we need
        default: return 0;  // reset/resync requests: the next song reopens the driver
    }
}
ASIOCallbacks g_callbacks{cbBufferSwitch, cbSampleRateDidChange, cbAsioMessage, cbBufferSwitchTimeInfo};

}  // namespace

std::vector<std::string> listAsioDrivers() {
    std::vector<std::string> names;
    HKEY root;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, kAsioKey, 0, KEY_READ, &root) != ERROR_SUCCESS) return names;
    wchar_t name[256];
    for (DWORD i = 0;; i++) {
        DWORD len = 256;
        if (RegEnumKeyExW(root, i, name, &len, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        CLSID clsid;
        const std::string n = utf8(name);
        if (!n.empty() && driverClsid(n, clsid)) names.push_back(n);
    }
    RegCloseKey(root);
    return names;
}

AsioOutput::~AsioOutput() {
    quit_ = true;
    if (thread_.joinable()) thread_.join();
}

bool AsioOutput::open(const StreamFormat& format, PullFn pull, std::string& error) {
    format_ = format;
    pull_ = std::move(pull);
    auto result = std::make_shared<AsioOpenResult>();
    auto future = result->done.get_future();
    thread_ = std::thread(&AsioOutput::run, this, result);
    error = future.get();
    if (!error.empty()) {
        if (thread_.joinable()) thread_.join();
        return false;
    }
    return true;
}

void AsioOutput::onBufferSwitch(long index) {
    uint8_t* outs[kMaxChannels];
    for (int c = 0; c < outChannels_; c++) outs[c] = static_cast<uint8_t*>(buffers_[index][(size_t) c]);
    const bool running = wantRunning_.load();
    if (format_.nativeDsd) {
        const int bytes = (int) (bufferSize_ / 8);  // DSD buffer sizes count 1-bit samples
        if (running) {
            feeder_.fill(pull_, outs, bytes, dsdLsbFirst_);
        } else {
            const uint8_t idle = dsdLsbFirst_ ? reverseBits(kDsdSilence) : kDsdSilence;
            for (int c = 0; c < outChannels_; c++) memset(outs[c], idle, (size_t) bytes);
        }
    } else {
        const int frames = (int) bufferSize_;
        const int got = running ? pull_(scratch_.data(), frames) : 0;
        const size_t bps = (size_t) bytesPerSample(sample_);
        if (got > 0) convertToPlanar(scratch_.data(), format_.channels, got, outs, outChannels_, sample_, planar_);
        for (int c = 0; c < outChannels_; c++) memset(outs[c] + (size_t) got * bps, 0, (size_t) (frames - got) * bps);
    }
    if (outputReady_) driver_->outputReady();
}

// The control thread owns the driver (COM, single-threaded apartment, as ASIO drivers expect);
// the driver calls bufferSwitch on its own thread. Pausing keeps the driver running on silence,
// so the DAC never has to relock (no clicks, no DSD pops).
void AsioOutput::run(std::shared_ptr<AsioOpenResult> result) {
    const bool comOwned = SUCCEEDED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED));
    std::string error;
    bool registered = false, buffersCreated = false, started = false;
    const bool dsd = format_.nativeDsd;
    const double rate = dsd ? (double) format_.dsdRate : (double) format_.sampleRate;
    char driverName[64] = {};

    {
        std::lock_guard<std::mutex> g(g_activeLock);
        AsioOutput* none = nullptr;
        if (g_active.compare_exchange_strong(none, this)) registered = true;
        else error = "Another ASIO stream is still open";
    }
    CLSID clsid;
    if (error.empty() && !driverClsid(driverName_, clsid)) error = "ASIO driver \"" + driverName_ + "\" isn't installed";
    if (error.empty()) {
        // ASIO convention: the CLSID is also the interface ID.
        if (FAILED(CoCreateInstance(clsid, nullptr, CLSCTX_INPROC_SERVER, clsid, (void**) &driver_)) || !driver_) {
            driver_ = nullptr;
            error = "Couldn't load the ASIO driver";
        }
    }
    if (error.empty() && !driver_->init(GetDesktopWindow())) {
        char msg[128] = {};
        driver_->getErrorMessage(msg);
        error = std::string("The ASIO driver didn't start") + (msg[0] ? std::string(": ") + msg : std::string());
    }
    if (error.empty()) {
        driver_->getDriverName(driverName);
        // DSD or PCM: switch before anything else (the driver may remember the last app's choice).
        ASIOIoFormat io = {};
        io.FormatType = dsd ? kASIODSDFormat : kASIOPCMFormat;
        const ASIOError r = driver_->future(kAsioSetIoFormat, &io);
        if (dsd && r != ASE_SUCCESS) error = "The ASIO driver doesn't do native DSD";
    }
    if (error.empty()) {
        if (driver_->canSampleRate(rate) != ASE_OK) {
            error = dsd ? "The ASIO driver doesn't take DSD" + std::to_string(format_.dsdRate / 44100) : "The ASIO driver doesn't take " + khz(format_.sampleRate);
        } else if (driver_->setSampleRate(rate) != ASE_OK) {
            error = "The ASIO driver couldn't switch rate";
        }
    }
    long inputs = 0, outputs = 0;
    if (error.empty() && (driver_->getChannels(&inputs, &outputs) != ASE_OK || outputs < 1)) error = "The ASIO driver has no outputs";
    if (error.empty()) {
        outChannels_ = format_.channels == 1 ? (outputs >= 2 ? 2 : 1) : format_.channels;
        if (outChannels_ > outputs || outChannels_ > kMaxChannels) error = "The ASIO driver has " + std::to_string(outputs) + " outputs, the song " + std::to_string(format_.channels);
    }
    if (error.empty()) {
        long minSize = 0, maxSize = 0, preferred = 0, granularity = 0;
        if (driver_->getBufferSize(&minSize, &maxSize, &preferred, &granularity) != ASE_OK || preferred <= 0) error = "The ASIO driver gave no buffer size";
        bufferSize_ = preferred;
        if (dsd && bufferSize_ % 8 != 0) error = "The ASIO driver's DSD buffer isn't whole bytes";
    }
    if (error.empty()) {
        // Sample type: the first output's (drivers use one type for all).
        ASIOChannelInfo info = {};
        info.channel = 0;
        info.isInput = ASIOFalse;
        if (driver_->getChannelInfo(&info) != ASE_OK) error = "The ASIO driver gave no sample type";
        else if (dsd) {
            if (info.type == ASIOSTDSDInt8MSB1) dsdLsbFirst_ = false;
            else if (info.type == ASIOSTDSDInt8LSB1) dsdLsbFirst_ = true;
            else error = "The ASIO driver's DSD sample type (" + std::to_string(info.type) + ") isn't supported";
        } else if (!pcmFormat(info.type, sample_)) {
            error = "The ASIO driver's sample type (" + std::to_string(info.type) + ") isn't supported";
        } else if (format_.bitExact && (sample_ == SampleFormat::Float32 || validBits(sample_) < 24)) {
            error = "The ASIO driver can't carry DoP bit-exact";
        }
    }
    if (error.empty()) {
        std::vector<ASIOBufferInfo> infos((size_t) outChannels_);
        for (int c = 0; c < outChannels_; c++) infos[(size_t) c] = ASIOBufferInfo{ASIOFalse, c, {nullptr, nullptr}};
        if (dsd) feeder_.init(outChannels_, (int) (bufferSize_ / 8));
        else scratch_.assign((size_t) bufferSize_ * format_.channels, 0.0f);
        planar_.reserve((size_t) bufferSize_);
        if (driver_->createBuffers(infos.data(), outChannels_, bufferSize_, &g_callbacks) != ASE_OK) {
            error = "The ASIO driver couldn't make its buffers";
        } else {
            buffersCreated = true;
            for (int half = 0; half < 2; half++) {
                buffers_[half].resize((size_t) outChannels_);
                for (int c = 0; c < outChannels_; c++) buffers_[half][(size_t) c] = infos[(size_t) c].buffers[half];
            }
            outputReady_ = driver_->outputReady() == ASE_OK;
        }
    }
    if (error.empty()) {
        if (driver_->start() != ASE_OK) error = "The ASIO driver didn't start playing";
        else started = true;
    }
    if (error.empty()) {
        const std::string name = driverName[0] ? driverName : driverName_;
        if (dsd) {
            buffered_ = 2 * bufferSize_ / 32;  // player frames are 32-bit words
            description_ = name + " · ASIO · DSD" + std::to_string(format_.dsdRate / 44100) + " native · bit-perfect";
        } else {
            buffered_ = 2 * bufferSize_;
            std::string d = name + " · ASIO · " + khz(format_.sampleRate) + " · " + formatName(sample_);
            if (format_.bitExact) d += " · DoP, bit-perfect";
            else if (format_.bits > 0 && sample_ != SampleFormat::Float32 && validBits(sample_) >= format_.bits) d += " · bit-perfect";
            description_ = d;
        }
    }
    result->done.set_value(error);

    // Keep the driver's window messages flowing until we're closed.
    while (error.empty() && !quit_) {
        MsgWaitForMultipleObjects(0, nullptr, FALSE, 20, QS_ALLINPUT);
        MSG msg;
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    if (started) driver_->stop();
    if (buffersCreated) driver_->disposeBuffers();
    if (driver_) { driver_->Release(); driver_ = nullptr; }
    if (registered) g_active = nullptr;
    if (comOwned) CoUninitialize();
}

}  // namespace allmeyn
