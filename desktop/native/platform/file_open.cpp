#include "file_open.h"

#include <fcntl.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <io.h>
#include <windows.h>

namespace allmeyn {
int openForRead(const std::string& utf8Path) {
    // Windows paths may hold any Unicode: convert to UTF-16 rather than using the ANSI code page.
    const int n = MultiByteToWideChar(CP_UTF8, 0, utf8Path.c_str(), -1, nullptr, 0);
    if (n <= 0) return -1;
    std::wstring w((size_t) n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8Path.c_str(), -1, &w[0], n);
    return _wopen(w.c_str(), _O_RDONLY | _O_BINARY);
}
}  // namespace allmeyn
#else
#include <unistd.h>

namespace allmeyn {
int openForRead(const std::string& utf8Path) { return open(utf8Path.c_str(), O_RDONLY | O_CLOEXEC); }
}  // namespace allmeyn
#endif
