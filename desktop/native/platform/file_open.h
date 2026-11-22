// Opening a file by its UTF-8 path as a read-only C file descriptor (for the DSD decoder).
#pragma once
#include <string>

namespace allmeyn {
/** -1 on failure. The caller (the decoder) closes it. */
int openForRead(const std::string& utf8Path);
}
