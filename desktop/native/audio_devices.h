// Windows audio output devices (WASAPI / MMDevice API).
#pragma once

#include <string>
#include <vector>

namespace allmeyn {

struct OutputDevice {
    std::string id;     // endpoint ID (stable across runs; used to open the device)
    std::string name;   // friendly name, e.g. "Speakers (FiiO KA17)"
    bool isDefault = false;
    int mixRate = 0, mixBits = 0, mixChannels = 0;  // shared-mode mix format
};

/** Active render endpoints. Empty if COM or the MMDevice API isn't available. */
std::vector<OutputDevice> listOutputDevices();

}  // namespace allmeyn
