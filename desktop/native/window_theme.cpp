// The window's title bar in dark or light (Windows draws it; apps opt in to dark).
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dwmapi.h>

namespace allmeyn {

void setDarkTitleBar(void* hwnd, bool dark) {
    HWND w = static_cast<HWND>(hwnd);
    if (!w) return;
    const BOOL on = dark ? TRUE : FALSE;
    // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on Windows 10 20H1+ and 11, 19 on earlier Windows 10 builds.
    if (FAILED(DwmSetWindowAttribute(w, 20, &on, sizeof on))) DwmSetWindowAttribute(w, 19, &on, sizeof on);
    // Repaint the frame now (it otherwise changes on the next activation).
    SetWindowPos(w, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
}

}  // namespace allmeyn
