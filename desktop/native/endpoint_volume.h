// The output's volume (Windows endpoint volume). For a DAC with its own volume control (USB Audio
// feature unit, e.g. the FiiO KA17) Windows moves the DAC's hardware volume, so the samples stay
// untouched (bit-perfect paths stay bit-perfect). Without one, Windows scales the audio in its mixer
// (shared mode only; exclusive mode then ignores it).
#pragma once

#include <string>

namespace allmeyn {

struct EndpointVolume {
    bool ok = false;
    float level = 0;            // 0..1, Windows' volume slider position
    float db = 0, minDb = 0, maxDb = 0, stepDb = 0;
    bool muted = false;
    bool hardware = false;      // the device does the volume itself
};

/** [deviceId] "" = Windows' default output. */
EndpointVolume readEndpointVolume(const std::string& deviceId);
bool setEndpointVolume(const std::string& deviceId, float level);
bool setEndpointMute(const std::string& deviceId, bool muted);

}  // namespace allmeyn
