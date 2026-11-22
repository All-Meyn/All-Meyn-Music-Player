// The Windows folder picker (the modern Explorer-style dialog, IFileOpenDialog).
#pragma once
#include <string>

namespace allmeyn {
/** Shows the picker over [owner] (an HWND, or 0). The chosen folder (UTF-8), or "" if cancelled. */
std::string pickFolder(void* owner, const std::string& title);
}
