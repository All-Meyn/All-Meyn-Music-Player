// The keyboard's media keys (play/pause, next, previous, stop) for All-Meyn, also while it's in the
// background: registered as system hotkeys on a message-only window of our own thread. Another app
// that registered them first keeps them (that key is then reported as not taken).
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <atomic>
#include <deque>
#include <mutex>
#include <thread>

namespace allmeyn {
namespace {

constexpr int kKeys = 4;
constexpr UINT kVk[kKeys] = {VK_MEDIA_PLAY_PAUSE, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PREV_TRACK, VK_MEDIA_STOP};

std::mutex g_lock;
std::deque<int> g_pressed;   // 1 play/pause, 2 next, 3 previous, 4 stop
std::thread g_thread;
std::atomic<DWORD> g_threadId{0};
std::atomic<int> g_taken{-1};  // bit i = kVk[i] registered; -1 while starting

void run() {
    g_threadId = GetCurrentThreadId();
    HWND w = CreateWindowExW(0, L"STATIC", L"All-Meyn media keys", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, nullptr, nullptr);
    int taken = 0;
    for (int i = 0; w && i < kKeys; i++) if (RegisterHotKey(w, i + 1, MOD_NOREPEAT, kVk[i])) taken |= 1 << i;
    g_taken = taken;
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        if (msg.message == WM_HOTKEY && msg.wParam >= 1 && msg.wParam <= kKeys) {
            std::lock_guard<std::mutex> g(g_lock);
            if (g_pressed.size() < 32) g_pressed.push_back((int) msg.wParam);
        }
    }
    for (int i = 0; w && i < kKeys; i++) if (taken & (1 << i)) UnregisterHotKey(w, i + 1);
    if (w) DestroyWindow(w);
}

}  // namespace

/** Starts listening (once); returns which keys were taken (bit 0 play/pause, 1 next, 2 previous, 3 stop). */
int startMediaKeys() {
    std::lock_guard<std::mutex> g(g_lock);
    if (!g_thread.joinable()) {
        g_taken = -1;
        g_thread = std::thread(run);
    }
    while (g_taken.load() < 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    return g_taken.load();
}

/** Stops listening and gives the keys back to Windows. */
void stopMediaKeys() {
    std::thread t;
    {
        std::lock_guard<std::mutex> g(g_lock);
        if (!g_thread.joinable()) return;
        t = std::move(g_thread);
        g_pressed.clear();
    }
    PostThreadMessageW(g_threadId.load(), WM_QUIT, 0, 0);
    t.join();
}

/** The next media key pressed (1 play/pause, 2 next, 3 previous, 4 stop), or 0. */
int pollMediaKey() {
    std::lock_guard<std::mutex> g(g_lock);
    if (g_pressed.empty()) return 0;
    const int k = g_pressed.front();
    g_pressed.pop_front();
    return k;
}

}  // namespace allmeyn
