// A stand-in for an app's native networking library: resolves through libc and libandroid imports.
#include <android/multinetwork.h>
#include <netdb.h>

extern "C" __attribute__((visibility("default"))) int target_getaddrinfo(const char *host, int numeric) {
    addrinfo hints{};
    hints.ai_socktype = SOCK_STREAM;
    if (numeric != 0) hints.ai_flags = AI_NUMERICHOST;
    addrinfo *res = nullptr;
    const int result = getaddrinfo(host, "443", &hints, &res);
    if (res != nullptr) freeaddrinfo(res);
    return result;
}

extern "C" __attribute__((visibility("default"))) int target_gethostbyname(const char *host) {
    h_errno = 0;
    return gethostbyname(host) != nullptr ? 0 : h_errno;
}

extern "C" __attribute__((visibility("default"))) int target_gethostbyname_r(const char *host) {
    hostent entry{};
    char buffer[2048];
    hostent *result = nullptr;
    int error = 0;
    const int status = gethostbyname_r(host, &entry, buffer, sizeof(buffer), &result, &error);
    return status != 0 ? -status : (result != nullptr ? 0 : error);
}

extern "C" __attribute__((visibility("default"))) int target_for_network(const char *host) {
    addrinfo *res = nullptr;
    const int result = android_getaddrinfofornetwork(NETWORK_UNSPECIFIED, host, "443", nullptr, &res);
    if (res != nullptr) freeaddrinfo(res);
    return result;
}
