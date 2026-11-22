/*
 * pread() for Windows, force-included when building the shared DSD decoder (app/src/main/cpp/
 * decoder/dsd.c) for the desktop engine, so that file stays untouched. Reads at a 64-bit offset
 * with ReadFile + OVERLAPPED; the file pointer isn't relied on. Build with _FILE_OFFSET_BITS=64
 * so off_t is 64-bit (files over 2 GB).
 */
#ifndef ALLMEYN_PREAD_WIN_H
#define ALLMEYN_PREAD_WIN_H
#ifdef _WIN32
#include <io.h>
#include <stdint.h>
#include <sys/types.h>
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

static inline ssize_t pread(int fd, void *buf, size_t n, off_t off) {
    HANDLE h = (HANDLE) _get_osfhandle(fd);
    if (h == INVALID_HANDLE_VALUE) return -1;
    OVERLAPPED ov;
    ZeroMemory(&ov, sizeof ov);
    ov.Offset = (DWORD) ((uint64_t) off & 0xFFFFFFFFu);
    ov.OffsetHigh = (DWORD) ((uint64_t) off >> 32);
    DWORD got = 0;
    if (n > 0x7FFFFFFF) n = 0x7FFFFFFF;
    if (!ReadFile(h, buf, (DWORD) n, &got, &ov)) return GetLastError() == ERROR_HANDLE_EOF ? 0 : -1;
    return (ssize_t) got;
}
#endif
#endif
