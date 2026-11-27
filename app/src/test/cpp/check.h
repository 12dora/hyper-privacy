// Minimal assertion helpers for the host-side native tests (no third-party test framework).
#pragma once

#include <cstdio>

inline int g_failures = 0;

#define CHECK(condition)                                                              \
    do {                                                                              \
        if (!(condition)) {                                                           \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++g_failures;                                                             \
        }                                                                             \
    } while (0)

#define CHECK_EQ(actual, expected) CHECK((actual) == (expected))

inline int Finish(const char *name) {
    if (g_failures == 0) std::printf("%s: all checks passed\n", name);
    return g_failures == 0 ? 0 : 1;
}
