// JNI surface of io.github.hyperprivacy.xposed.NativeResolver (kept by name in proguard-rules.pro).
// Every entry returns a status instead of throwing; nothing here can raise a Java exception.
#include <jni.h>

#include <cstdlib>

#include "resolver_hooks.h"

namespace {

// Far above any real table (tens of KB); a larger array is refused rather than copied.
constexpr jsize kMaxTableBytes = 4 << 20;

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_io_github_hyperprivacy_xposed_NativeResolver_nativeInstall(JNIEnv *, jclass) {
    return hp::Install();
}

JNIEXPORT jint JNICALL Java_io_github_hyperprivacy_xposed_NativeResolver_nativeRescan(JNIEnv *, jclass) {
    return hp::Rescan();
}

JNIEXPORT jint JNICALL Java_io_github_hyperprivacy_xposed_NativeResolver_nativeSetTable(JNIEnv *env, jclass,
                                                                                        jbyteArray blob) {
    if (blob == nullptr) return hp::SetTable(nullptr, 0);
    const jsize length = env->GetArrayLength(blob);
    if (length <= 0 || length > kMaxTableBytes) return hp::SetTable(nullptr, 0);
    auto *bytes = static_cast<jbyte *>(std::malloc(static_cast<size_t>(length)));
    if (bytes == nullptr) return hp::SetTable(nullptr, 0);
    env->GetByteArrayRegion(blob, 0, length, bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        std::free(bytes);
        return hp::SetTable(nullptr, 0);
    }
    const int entries = hp::SetTable(reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(length));
    std::free(bytes);
    return entries;
}

}  // extern "C"
