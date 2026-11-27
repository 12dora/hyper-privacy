// On-device test of the native resolver hooks (emulator or a test phone with adb shell access):
// loads stand-in app libraries from the same directory, installs the hooks, and checks that a
// blocked name gets the platform's not-found answer through every hooked import while other
// names, numeric lookups, this executable's own imports and a removed table pass through.
#include <dirent.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstring>
#include <netdb.h>

#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include "../check.h"
#include "host_table.h"
#include "resolver_hooks.h"

namespace {

using LookupFn = int (*)(const char *);
using GaiFn = int (*)(const char *, int);

std::vector<uint8_t> Encode(const std::vector<std::pair<std::string, bool>> &entries) {
    std::vector<uint8_t> out = {'H', 'P', 'T', '1'};
    const auto count = static_cast<uint32_t>(entries.size());
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<uint8_t>(count >> (8 * i)));
    for (const auto &[pattern, block] : entries) {
        out.push_back(block ? 1 : 0);
        out.push_back(static_cast<uint8_t>(pattern.size()));
        out.insert(out.end(), pattern.begin(), pattern.end());
    }
    return out;
}

struct Target {
    GaiFn gai;
    LookupFn byname;
    LookupFn byname_r;
    LookupFn network;
};

Target Load(const std::string &path) {
    void *handle = dlopen(path.c_str(), RTLD_NOW);
    if (handle == nullptr) {
        std::fprintf(stderr, "dlopen %s: %s\n", path.c_str(), dlerror());
        return {};
    }
    return Target{reinterpret_cast<GaiFn>(dlsym(handle, "target_getaddrinfo")),
                  reinterpret_cast<LookupFn>(dlsym(handle, "target_gethostbyname")),
                  reinterpret_cast<LookupFn>(dlsym(handle, "target_gethostbyname_r")),
                  reinterpret_cast<LookupFn>(dlsym(handle, "target_for_network"))};
}

void ExpectBlocked(const Target &t, const char *host) {
    CHECK_EQ(t.gai(host, 0), EAI_NODATA);
    CHECK_EQ(t.byname(host), HOST_NOT_FOUND);
    CHECK_EQ(t.byname_r(host), HOST_NOT_FOUND);
    CHECK_EQ(t.network(host), EAI_NODATA);
}

void ExpectResolves(const Target &t, const char *host) {
    CHECK_EQ(t.gai(host, 0), 0);
    CHECK_EQ(t.byname(host), 0);
    CHECK_EQ(t.byname_r(host), 0);
    CHECK_EQ(t.network(host), 0);
}

// Whether this process holds a descriptor for /proc/self/mem (it must not, outside a scan).
bool HoldsMemFd() {
    DIR *dir = opendir("/proc/self/fd");
    if (dir == nullptr) return false;
    bool found = false;
    while (dirent *entry = readdir(dir)) {
        char path[64];
        char target[256];
        std::snprintf(path, sizeof(path), "/proc/self/fd/%s", entry->d_name);
        const ssize_t length = readlink(path, target, sizeof(target) - 1);
        if (length <= 0) continue;
        target[length] = 0;
        if (std::strstr(target, "/mem") != nullptr && std::strstr(target, "/proc/") != nullptr) found = true;
    }
    closedir(dir);
    return found;
}

int DirectLookup(const char *host) {
    addrinfo *res = nullptr;
    const int result = getaddrinfo(host, "443", nullptr, &res);
    if (res != nullptr) freeaddrinfo(res);
    return result;
}

// Fallback mode (as on a kernel that refuses forced /proc/self/mem writes): read-only (RELRO) GOT
// slots are skipped, slots in writable pages are patched in place.
int InPlaceOnly(const std::string &dir) {
    const Target relro = Load(dir + "/libhptarget_early.so");
    const Target writable = Load(dir + "/libhptarget_norelro.so");
    CHECK(relro.gai != nullptr && writable.gai != nullptr);
    if (relro.gai == nullptr || writable.gai == nullptr) return Finish("device_test inplace");
    const auto blob = Encode({{"localhost", true}});
    CHECK_EQ(hp::SetTable(blob.data(), blob.size()), 1);
    hp::UseInPlaceWritesOnly();
    const int patched = hp::Install();
    std::printf("in-place install patched %d slots\n", patched);
    CHECK(!hp::WritesThroughMemory());
    CHECK(patched >= 4);
    ExpectResolves(relro, "localhost");   // read-only GOT: left alone, fail open
    ExpectBlocked(writable, "localhost");  // writable GOT: patched in place
    return Finish("device_test inplace");
}

}  // namespace

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : "/data/local/tmp/hpnative";
    if (argc > 2 && std::string(argv[2]) == "inplace") return InPlaceOnly(dir);
    // Loaded before Install: patched by the initial scan.
    const Target early = Load(dir + "/libhptarget_early.so");
    CHECK(early.gai != nullptr && early.byname != nullptr && early.byname_r != nullptr && early.network != nullptr);
    if (early.gai == nullptr) return Finish("device_test");

    // "localhost" resolves without a network, so a pass-through is observable as success.
    const auto blob = Encode({{"keep.localhost", false}, {"localhost", true}});
    CHECK_EQ(hp::SetTable(blob.data(), blob.size()), 2);
    ExpectResolves(early, "localhost");  // not installed yet: nothing redirected

    const int patched = hp::Install();
    std::printf("install patched %d slots (via /proc/self/mem: %d)\n", patched, hp::WritesThroughMemory() ? 1 : 0);
    CHECK(hp::WritesThroughMemory());
    CHECK(patched >= 4);
    CHECK(!HoldsMemFd());  // opened for the scan only
    CHECK_EQ(hp::Install(), 0);  // once per process
    ExpectBlocked(early, "localhost");
    ExpectBlocked(early, "LOCALHOST.");
    CHECK_EQ(early.gai("localhost", 1), EAI_NONAME);  // numeric-only lookups reach the original
    CHECK_EQ(early.gai("127.0.0.1", 0), 0);
    CHECK_EQ(DirectLookup("localhost"), 0);  // this executable is not an app library: unpatched

    // Loaded after Install: patched by a rescan, as after System.loadLibrary.
    Target late = Load(dir + "/libhptarget_late.so");
    CHECK(late.gai != nullptr);
    if (late.gai == nullptr) return Finish("device_test");
    ExpectResolves(late, "localhost");
    const int rescanned = hp::Rescan();
    std::printf("rescan patched %d slots\n", rescanned);
    CHECK(rescanned >= 4);
    CHECK_EQ(hp::Rescan(), 0);
    ExpectBlocked(late, "localhost");

    // Unloaded and loaded again (often at the same address): the fresh copy is patched too.
    {
        void *again = dlopen((dir + "/libhptarget_late.so").c_str(), RTLD_NOW | RTLD_NOLOAD);
        CHECK(again != nullptr);
        if (again != nullptr) {
            dlclose(again);  // the reference taken just now
            dlclose(again);  // Load's reference: unloads it
        }
        late = Load(dir + "/libhptarget_late.so");  // the old pointers are gone with the old copy
        CHECK(late.gai != nullptr);
        if (late.gai == nullptr) return Finish("device_test");
        ExpectResolves(late, "localhost");
        CHECK(hp::Rescan() >= 4);
        ExpectBlocked(late, "localhost");
        CHECK(!HoldsMemFd());
    }

    // Switched off (or paused): an empty table lets everything through at once.
    CHECK_EQ(hp::SetTable(nullptr, 0), 0);
    ExpectResolves(early, "localhost");
    ExpectResolves(late, "localhost");
    // A malformed table publishes none: fail open.
    const uint8_t junk[] = {'X', 'X', 'X', 'X', 0, 0, 0, 0};
    CHECK_EQ(hp::SetTable(junk, sizeof(junk)), 0);
    ExpectResolves(early, "localhost");

    // Cost of the hot path with a realistic table (2,000 patterns), host not blocked.
    std::vector<std::pair<std::string, bool>> many;
    for (int i = 0; i < 2000; ++i) many.emplace_back("tracker" + std::to_string(i) + ".example", true);
    const auto big = Encode(many);
    CHECK_EQ(hp::SetTable(big.data(), big.size()), 2000);
    constexpr int kRounds = 1000000;
    int hits = 0;
    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < kRounds; ++i) hits += hp::IsBlocked("api.cdn.service.example.com") ? 1 : 0;
    const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start);
    CHECK_EQ(hits, 0);
    CHECK(hp::IsBlocked("x.tracker1999.example"));
    std::printf("lookup: %.1f ns per call (5 labels, 2000 patterns)\n", static_cast<double>(ns.count()) / kRounds);
    return Finish("device_test");
}
