/*
 * Windows (x64) machine-dependent JNI types, for cross-compiling the desktop engine with MinGW-w64
 * on Linux against the host JDK's platform-independent jni.h. Matches the JDK's win32/jni_md.h:
 * LLP64, so jint is a 32-bit long and jlong is 64-bit.
 */
#ifndef ALLMEYN_JNI_MD_H
#define ALLMEYN_JNI_MD_H

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif
