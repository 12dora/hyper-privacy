#include "resolver_hooks.h"

#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <netdb.h>
#include <pthread.h>
#include <linux/membarrier.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cstring>

#include "elf_patch.h"
#include "host_table.h"

static_assert(sizeof(hp::elf::Phdr) == sizeof(ElfW(Phdr)) && sizeof(hp::elf::Sym) == sizeof(ElfW(Sym)) &&
                  sizeof(hp::elf::Rela) == sizeof(ElfW(Rela)) && sizeof(hp::elf::Dyn) == sizeof(ElfW(Dyn)),
              "ELF64 layouts must match the platform's");

namespace hp {
namespace {

// net_handle_t of <android/multinetwork.h>; declared here so nothing links against libandroid.
using NetHandle = uint64_t;

using GetaddrinfoFn = int (*)(const char *, const char *, const addrinfo *, addrinfo **);
using GetaddrinfoForNetFn = int (*)(const char *, const char *, const addrinfo *, unsigned, unsigned,
                                    addrinfo **);
using GetaddrinfoForNetworkFn = int (*)(NetHandle, const char *, const char *, const addrinfo *, addrinfo **);
using GethostbynameFn = hostent *(*)(const char *);
using Gethostbyname2Fn = hostent *(*)(const char *, int);
using GethostbynameRFn = int (*)(const char *, hostent *, char *, size_t, hostent **, int *);
using Gethostbyname2RFn = int (*)(const char *, int, hostent *, char *, size_t, hostent **, int *);

// Originals, written once by Install before any slot points at a hook and never changed after.
std::atomic<GetaddrinfoFn> g_getaddrinfo{nullptr};
std::atomic<GetaddrinfoForNetFn> g_getaddrinfo_for_net{nullptr};
std::atomic<GetaddrinfoForNetworkFn> g_getaddrinfo_for_network{nullptr};
std::atomic<GethostbynameFn> g_gethostbyname{nullptr};
std::atomic<Gethostbyname2Fn> g_gethostbyname2{nullptr};
std::atomic<GethostbynameRFn> g_gethostbyname_r{nullptr};
std::atomic<Gethostbyname2RFn> g_gethostbyname2_r{nullptr};

// The published table. Replaced tables are never freed: a hook may still be probing one, and
// tables change only when the user flips a switch, so the leak is bounded and small.
std::atomic<const HostTable *> g_table{nullptr};

// Hot path: one acquire load, then O(labels) probes of an immutable table; no lock, no allocation.
inline bool Blocked(const char *host) {
    const HostTable *table = g_table.load(std::memory_order_acquire);
    return table != nullptr && host != nullptr && table->Blocks(host);
}

inline bool BlockedLookup(const char *host, const addrinfo *hints) {
    // A numeric-only lookup never resolves a name: let the original answer it.
    if (hints != nullptr && (hints->ai_flags & AI_NUMERICHOST) != 0) return false;
    return Blocked(host);
}

// The answers mirror bionic's own for a name that does not exist (NXDOMAIN), as measured on
// Android 16 / 17: getaddrinfo returns EAI_NODATA with *res = NULL; gethostbyname* return NULL
// with h_errno = HOST_NOT_FOUND; the _r variants return 0 with *result = NULL and
// *h_errnop = HOST_NOT_FOUND. If an original is somehow missing the call fails as transient.

int HookGetaddrinfo(const char *node, const char *service, const addrinfo *hints, addrinfo **res) {
    const GetaddrinfoFn original = g_getaddrinfo.load(std::memory_order_relaxed);
    if (BlockedLookup(node, hints) || original == nullptr) {
        if (res != nullptr) *res = nullptr;
        return original == nullptr ? EAI_AGAIN : EAI_NODATA;
    }
    return original(node, service, hints, res);
}

int HookGetaddrinfoForNet(const char *node, const char *service, const addrinfo *hints, unsigned netid,
                          unsigned mark, addrinfo **res) {
    const GetaddrinfoForNetFn original = g_getaddrinfo_for_net.load(std::memory_order_relaxed);
    if (BlockedLookup(node, hints) || original == nullptr) {
        if (res != nullptr) *res = nullptr;
        return original == nullptr ? EAI_AGAIN : EAI_NODATA;
    }
    return original(node, service, hints, netid, mark, res);
}

int HookGetaddrinfoForNetwork(NetHandle network, const char *node, const char *service, const addrinfo *hints,
                              addrinfo **res) {
    const GetaddrinfoForNetworkFn original = g_getaddrinfo_for_network.load(std::memory_order_relaxed);
    if (BlockedLookup(node, hints) || original == nullptr) {
        if (res != nullptr) *res = nullptr;
        return original == nullptr ? EAI_AGAIN : EAI_NODATA;
    }
    return original(network, node, service, hints, res);
}

hostent *HookGethostbyname(const char *name) {
    const GethostbynameFn original = g_gethostbyname.load(std::memory_order_relaxed);
    if (Blocked(name) || original == nullptr) {
        h_errno = original == nullptr ? TRY_AGAIN : HOST_NOT_FOUND;
        return nullptr;
    }
    return original(name);
}

hostent *HookGethostbyname2(const char *name, int family) {
    const Gethostbyname2Fn original = g_gethostbyname2.load(std::memory_order_relaxed);
    if (Blocked(name) || original == nullptr) {
        h_errno = original == nullptr ? TRY_AGAIN : HOST_NOT_FOUND;
        return nullptr;
    }
    return original(name, family);
}

int HookGethostbynameR(const char *name, hostent *ret, char *buf, size_t buflen, hostent **result,
                       int *h_errnop) {
    const GethostbynameRFn original = g_gethostbyname_r.load(std::memory_order_relaxed);
    if (Blocked(name) || original == nullptr) {
        if (result != nullptr) *result = nullptr;
        if (h_errnop != nullptr) *h_errnop = original == nullptr ? TRY_AGAIN : HOST_NOT_FOUND;
        return 0;
    }
    return original(name, ret, buf, buflen, result, h_errnop);
}

int HookGethostbyname2R(const char *name, int family, hostent *ret, char *buf, size_t buflen, hostent **result,
                        int *h_errnop) {
    const Gethostbyname2RFn original = g_gethostbyname2_r.load(std::memory_order_relaxed);
    if (Blocked(name) || original == nullptr) {
        if (result != nullptr) *result = nullptr;
        if (h_errnop != nullptr) *h_errnop = original == nullptr ? TRY_AGAIN : HOST_NOT_FOUND;
        return 0;
    }
    return original(name, family, ret, buf, buflen, result, h_errnop);
}

#if defined(__aarch64__)
constexpr elf::RelocTypes kRelocs = elf::kAarch64Relocs;
#elif defined(__x86_64__)
constexpr elf::RelocTypes kRelocs = elf::kX86_64Relocs;
#else
#error "native resolver hooks support arm64-v8a and x86_64 only"
#endif

constexpr size_t kMaxTargets = 7;
constexpr size_t kMaxSeen = 2048;
// Address space reserved once for the maps snapshot; only the pages touched are ever backed.
constexpr size_t kSnapshotBytes = 8u << 20;
constexpr size_t kMaxRegions = kSnapshotBytes / sizeof(elf::Region);

// Guarded by g_mutex (Install and Rescan only; the hooks never take it).
pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
int g_state = 0;  // 0 not attempted, 1 installed, -1 failed (never retried in this process)
elf::Target g_targets[kMaxTargets];
size_t g_target_count = 0;
size_t g_page_size = 0;
bool g_mem_writes = false;  // /proc/self/mem can write through read-only pages (probed once)
int g_mem_fd = -1;          // /proc/self/mem, open only during a scan (never across one)
char g_self_path[PATH_MAX];  // this library, never scanned
std::atomic<bool> g_rescan_pending{false};
// Read buffers of TakeSnapshot: static, so no app thread needs 4 KB of spare stack (g_mutex held).
char g_maps_buffer[4096];
char g_maps_line[128];
bool g_in_place_only = false;  // tests: behave as if /proc/self/mem could not write

// Libraries already handled, by load bias and path hash; rebuilt from the live images on every
// full scan, so an unloaded library's entry disappears and a reused address is scanned again.
struct SeenKey {
    uintptr_t bias;
    uint32_t name_hash;
};
SeenKey g_seen[2][kMaxSeen];
size_t g_seen_count[2] = {0, 0};
int g_seen_current = 0;
// dl_iterate_phdr's load / unload counters at the last full scan: unchanged means nothing new.
unsigned long long g_last_adds = 0;
unsigned long long g_last_subs = 0;
bool g_scanned = false;

elf::Region *g_regions = nullptr;  // the maps snapshot of the running scan
size_t g_region_count = 0;
bool g_snapshot_ready = false;  // taken (lazily, once) in the running scan
bool g_snapshot_ok = false;

uint32_t NameHash(const char *name) { return HashBytes(name, strnlen(name, PATH_MAX)); }

template <typename Fn>
bool AddTarget(void *library, const char *name, std::atomic<Fn> &original, Fn hook) {
    if (library == nullptr || g_target_count == kMaxTargets) return false;
    void *symbol = dlsym(library, name);
    if (symbol == nullptr) return false;
    original.store(reinterpret_cast<Fn>(symbol), std::memory_order_release);
    g_targets[g_target_count++] = elf::Target{name, reinterpret_cast<uintptr_t>(symbol),
                                              reinterpret_cast<uintptr_t>(hook)};
    return true;
}

// Makes the originals stored by AddTarget visible to every thread before any slot points at a
// hook. A hook reached through a freshly written slot loads its original with no dependency on
// that slot, so without this a core could, in principle, still see the null. membarrier is used
// by ART itself and so is allowed in app processes; if it is unavailable the window is only
// theoretical, and a hook that sees a null original fails the one lookup as transient.
void PublishOriginals() {
    static bool registered = false;
    if (!registered) {
        registered = syscall(__NR_membarrier, MEMBARRIER_CMD_REGISTER_PRIVATE_EXPEDITED, 0, 0) == 0;
    }
    if (registered && syscall(__NR_membarrier, MEMBARRIER_CMD_PRIVATE_EXPEDITED, 0, 0) == 0) return;
    if (syscall(__NR_membarrier, MEMBARRIER_CMD_GLOBAL, 0, 0) == 0) return;
    std::atomic_thread_fence(std::memory_order_seq_cst);
}

// Only the app's own process: libraries under /data (the APK, its extracted libs, code it
// downloads into its data directory, and libraries of other packages it maps into itself, such
// as an updated WebView or Play services modules) or adopted storage. Platform partitions,
// root-manager files and anything not named like a shared library are never touched.
bool Eligible(const char *name) {
    if (name == nullptr) return false;
    const size_t length = strnlen(name, PATH_MAX);
    if (length < 4 || length == PATH_MAX || std::strcmp(name + length - 3, ".so") != 0) return false;
    const bool app_storage = std::strncmp(name, "/data/", 6) == 0 || std::strncmp(name, "/mnt/expand/", 12) == 0;
    return app_storage && std::strncmp(name, "/data/adb/", 10) != 0 && std::strncmp(name, "/data/misc/", 11) != 0;
}

// Reads /proc/self/maps once into the snapshot. Fixed buffers and one reserved mapping only: it
// runs under the loader's lock. Lines are cut after 127 bytes, which still holds the address range
// and permissions. A snapshot that does not fit, or is not sorted, is unusable (scan skipped).
bool TakeSnapshot() {
    if (g_regions == nullptr) {
        void *memory = mmap(nullptr, kSnapshotBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE,
                            -1, 0);
        if (memory == MAP_FAILED) return false;
        g_regions = static_cast<elf::Region *>(memory);
    }
    g_region_count = 0;
    const int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char *buffer = g_maps_buffer;
    char *line = g_maps_line;
    size_t line_length = 0;
    bool ok = true;
    const auto add = [&]() {
        elf::Region region{};
        if (line_length == 0 || !elf::ParseMapsLine(line, line_length, region)) return;
        if (g_region_count == kMaxRegions ||
            (g_region_count > 0 && region.start < g_regions[g_region_count - 1].end)) {
            ok = false;
            return;
        }
        g_regions[g_region_count++] = region;
    };
    while (ok) {
        const ssize_t count = TEMP_FAILURE_RETRY(read(fd, buffer, sizeof(g_maps_buffer)));
        if (count < 0) ok = false;
        if (count <= 0) break;
        for (ssize_t k = 0; k < count && ok; ++k) {
            if (buffer[k] == '\n') {
                add();
                line_length = 0;
            } else if (line_length < sizeof(g_maps_line)) {
                line[line_length++] = buffer[k];
            }
        }
    }
    if (ok) add();
    close(fd);
    return ok && g_region_count > 0;
}

// Memory access for the patcher during one scan. Readability comes from the snapshot. Slots are
// read and written through /proc/self/mem when it works: the kernel writes through a read-only
// (RELRO) page without changing its protection, so nothing here ever toggles page permissions
// (which could race with another in-app GOT hooker doing the same) and a slot access cannot
// fault. The descriptor is opened for one scan and closed after it (ScanLocked), so it can never
// be inherited by a forked child, reused by a buggy close elsewhere, or seen between scans.
//
// An aligned 8-byte slot never crosses a page, so the kernel copies it in one step (all or
// nothing); arm64 GKI's memcpy stores 8 aligned bytes with single 64-bit stores, so a thread
// jumping through the slot sees the old or the new pointer. Without /proc/self/mem (or if a write
// through it fails), only slots in pages that are writable and outside RELRO are written, in place.
class ScanMemory final : public elf::Memory {
public:
    bool Readable(uintptr_t address, size_t size) override {
        return elf::RegionsReadable(g_regions, g_region_count, address, size);
    }

    bool ReadSlot(uintptr_t slot, uintptr_t &value) override {
        if (g_mem_fd >= 0 && ReadThrough(slot, value)) return true;
        if (!Readable(slot, sizeof(value))) return false;
        value = __atomic_load_n(reinterpret_cast<const uintptr_t *>(slot), __ATOMIC_RELAXED);
        return true;
    }

    bool WriteSlot(uintptr_t slot, uintptr_t expected, uintptr_t value, bool relro) override {
        const elf::Region *region = elf::FindRegion(g_regions, g_region_count, slot);
        if (region == nullptr || (region->prot & elf::kProtPrivate) == 0 || (region->prot & elf::kProtExec) != 0 ||
            (region->prot & elf::kProtRead) == 0) {
            return false;  // never a shared, executable or unreadable mapping
        }
        if (g_mem_fd >= 0) {
            // Compare, write, confirm: a slot another hooker changed in between is left alone.
            uintptr_t now = 0;
            if (ReadThrough(slot, now)) {
                if (now != expected) return false;
                if (TEMP_FAILURE_RETRY(pwrite64(g_mem_fd, &value, sizeof(value), static_cast<off64_t>(slot))) ==
                    static_cast<ssize_t>(sizeof(value))) {
                    return ReadThrough(slot, now) && now == value;
                }
            }
            // This mapping refused forced access: fall back to an in-place write where that is possible.
        }
        if (relro || (region->prot & elf::kProtWrite) == 0) return false;
        return __atomic_compare_exchange_n(reinterpret_cast<uintptr_t *>(slot), &expected, value, false,
                                           __ATOMIC_RELEASE, __ATOMIC_RELAXED);
    }

private:
    static bool ReadThrough(uintptr_t slot, uintptr_t &value) {
        return TEMP_FAILURE_RETRY(pread64(g_mem_fd, &value, sizeof(value), static_cast<off64_t>(slot))) ==
               static_cast<ssize_t>(sizeof(value));
    }
};

// Whether /proc/self/mem can write through a read-only private page here (kernel and SELinux
// policy allow it on Android 15-17 app processes, but a kernel may restrict forced writes).
// Probed once on a private scratch page; the descriptor is closed right away.
bool ProbeMemoryWrites(size_t page_size) {
    const int fd = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
    if (fd < 0) return false;
    void *page = mmap(nullptr, page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    bool works = false;
    if (page != MAP_FAILED) {
        auto *word = static_cast<uintptr_t *>(page);
        __atomic_store_n(word, static_cast<uintptr_t>(1), __ATOMIC_RELAXED);
        const uintptr_t probe = 0x5a5a5a5a5a5a5a5aull;
        if (mprotect(page, page_size, PROT_READ) == 0 &&
            TEMP_FAILURE_RETRY(pwrite64(fd, &probe, sizeof(probe), static_cast<off64_t>(reinterpret_cast<uintptr_t>(word)))) ==
                static_cast<ssize_t>(sizeof(probe))) {
            works = __atomic_load_n(word, __ATOMIC_RELAXED) == probe;
        }
        munmap(page, page_size);
    }
    close(fd);
    return works;
}

// Everything a scan needs besides the loader's lock: the maps snapshot and, if usable, a
// /proc/self/mem descriptor for this scan only.
bool PrepareScan() {
    if (!TakeSnapshot()) return false;
    if (g_mem_writes && g_mem_fd < 0) g_mem_fd = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
    return true;
}

struct ScanState {
    int patched;
    bool full;        // force a full pass (targets changed)
    bool first;       // the first callback of this pass
    bool complete;    // every eligible image was handled (the seen set may replace the old one)
};

bool InOldSeen(const SeenKey &key) {
    const SeenKey *old = g_seen[g_seen_current];
    for (size_t i = 0; i < g_seen_count[g_seen_current]; ++i) {
        if (old[i].bias == key.bias && old[i].name_hash == key.name_hash) return true;
    }
    return false;
}

void RememberSeen(const SeenKey &key) {
    const int next = 1 - g_seen_current;
    if (g_seen_count[next] < kMaxSeen) g_seen[next][g_seen_count[next]++] = key;
    // Full: the image is simply scanned again next time (patching is idempotent).
}

// Runs under the loader's lock (dl_iterate_phdr), so no library can be unloaded while it is
// inspected or patched. Allocates nothing on the heap and calls nothing that re-enters the loader.
int ScanOne(dl_phdr_info *info, size_t size, void *data) {
    auto *state = static_cast<ScanState *>(data);
    if (size < offsetof(dl_phdr_info, dlpi_phnum) + sizeof(info->dlpi_phnum)) return 0;
    if (state->first) {
        state->first = false;
        if (size >= offsetof(dl_phdr_info, dlpi_subs) + sizeof(info->dlpi_subs)) {
            const bool unchanged = g_scanned && info->dlpi_adds == g_last_adds && info->dlpi_subs == g_last_subs;
            // Something was unloaded: a library reloaded at the same address and path must not
            // pass for the old one, so every image is looked at again (patching is idempotent).
            if (g_scanned && info->dlpi_subs != g_last_subs) state->full = true;
            if (unchanged && !state->full) {
                state->complete = false;  // keep the current seen set as it is
                return 1;                 // nothing loaded or unloaded since the last scan
            }
            g_last_adds = info->dlpi_adds;
            g_last_subs = info->dlpi_subs;
        }
    }
    if (!Eligible(info->dlpi_name)) return 0;
    // This library, by path: nothing of an image is read before the snapshot says it is readable.
    if (std::strcmp(info->dlpi_name, g_self_path) == 0) return 0;
    const elf::Image image{static_cast<uintptr_t>(info->dlpi_addr),
                           reinterpret_cast<const elf::Phdr *>(info->dlpi_phdr), info->dlpi_phnum};
    const SeenKey key{image.bias, NameHash(info->dlpi_name)};
    if (!state->full && InOldSeen(key)) {
        RememberSeen(key);
        return 0;
    }
    if (!g_snapshot_ready) {
        g_snapshot_ready = true;
        g_snapshot_ok = PrepareScan();
    }
    if (!g_snapshot_ok) {
        state->complete = false;
        return 1;  // no way to check readability: patch nothing in this pass
    }
    ScanMemory memory;
    const elf::PatchResult result = elf::PatchImage(image, g_targets, g_target_count, kRelocs, g_page_size, memory);
    state->patched += result.patched;
    RememberSeen(key);
    return 0;
}

int ScanLocked(bool full) {
    ScanState state{0, full, true, true};
    g_seen_count[1 - g_seen_current] = 0;
    g_snapshot_ready = false;
    g_snapshot_ok = false;
    dl_iterate_phdr(ScanOne, &state);
    if (g_mem_fd >= 0) {
        close(g_mem_fd);
        g_mem_fd = -1;
    }
    // Give the snapshot's pages back; the reservation stays for the next scan.
    if (g_regions != nullptr && g_region_count > 0) {
        madvise(g_regions, g_region_count * sizeof(elf::Region), MADV_DONTNEED);
        g_region_count = 0;
    }
    if (state.complete) {
        g_seen_current = 1 - g_seen_current;  // only the images alive in this pass remain
        g_scanned = true;
    } else if (!g_snapshot_ready || g_snapshot_ok) {
        // Short-circuited: nothing changed, the old set stays.
    } else {
        g_scanned = false;  // the snapshot failed: retry everything next time
    }
    return state.patched;
}

class Lock {
public:
    Lock() { pthread_mutex_lock(&g_mutex); }
    ~Lock() { pthread_mutex_unlock(&g_mutex); }
    Lock(const Lock &) = delete;
    Lock &operator=(const Lock &) = delete;
};

// Keeps this library mapped for the life of the process (patched slots point into it) and
// records its path, so scans skip it without reading its headers.
void PinSelf() {
    Dl_info self{};
    if (dladdr(reinterpret_cast<void *>(&PinSelf), &self) == 0 || self.dli_fname == nullptr) return;
    std::strncpy(g_self_path, self.dli_fname, sizeof(g_self_path) - 1);
    dlopen(self.dli_fname, RTLD_NOW | RTLD_NOLOAD | RTLD_NODELETE);
}

// The NDK multinetwork API lives in libandroid (which resolves through libc internally, unpatched:
// a platform library); apps calling it directly import it from there. libandroid may be mapped
// only later, so it is looked up again on every rescan until found. The lookup (dlopen NOLOAD,
// dlsym: the loader's lock) happens before g_mutex is taken, never under it.
void *FindNetworkSymbol() {
    if (g_getaddrinfo_for_network.load(std::memory_order_acquire) != nullptr) return nullptr;
    void *library = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    return library == nullptr ? nullptr : dlsym(library, "android_getaddrinfofornetwork");
}

bool AddNetworkTarget(void *symbol) {
    if (symbol == nullptr || g_getaddrinfo_for_network.load(std::memory_order_relaxed) != nullptr ||
        g_target_count == kMaxTargets) {
        return false;
    }
    g_getaddrinfo_for_network.store(reinterpret_cast<GetaddrinfoForNetworkFn>(symbol), std::memory_order_release);
    g_targets[g_target_count++] = elf::Target{"android_getaddrinfofornetwork", reinterpret_cast<uintptr_t>(symbol),
                                              reinterpret_cast<uintptr_t>(&HookGetaddrinfoForNetwork)};
    return true;
}

// Scans while holding g_mutex; scans requested meanwhile by other threads (which never wait for
// the lock, see Rescan) are run here before the lock is released.
int ScanAndDrainLocked(bool full) {
    int patched = ScanLocked(full);
    while (g_rescan_pending.exchange(false, std::memory_order_acq_rel)) patched += ScanLocked(false);
    return patched;
}

}  // namespace

int Install() {
    // Loader calls (dlopen, dlsym, dladdr) happen before g_mutex is taken: g_mutex is never held
    // while waiting for the loader's lock except inside dl_iterate_phdr itself.
    const long page = sysconf(_SC_PAGESIZE);
    void *libc = dlopen("libc.so", RTLD_NOW | RTLD_NOLOAD);
    void *network = FindNetworkSymbol();
    Lock lock;
    if (g_state != 0) return g_state > 0 ? 0 : kErrorDisabled;
    g_state = -1;  // pessimistic until everything below succeeded
    if (page <= 0 || (page & (page - 1)) != 0) return kErrorNoOriginal;
    g_page_size = static_cast<size_t>(page);
    if (!AddTarget(libc, "getaddrinfo", g_getaddrinfo, &HookGetaddrinfo)) return kErrorNoOriginal;
    AddTarget(libc, "android_getaddrinfofornet", g_getaddrinfo_for_net, &HookGetaddrinfoForNet);
    AddTarget(libc, "gethostbyname", g_gethostbyname, &HookGethostbyname);
    AddTarget(libc, "gethostbyname2", g_gethostbyname2, &HookGethostbyname2);
    AddTarget(libc, "gethostbyname_r", g_gethostbyname_r, &HookGethostbynameR);
    AddTarget(libc, "gethostbyname2_r", g_gethostbyname2_r, &HookGethostbyname2R);
    AddNetworkTarget(network);
    g_mem_writes = !g_in_place_only && ProbeMemoryWrites(g_page_size);
    PinSelf();
    PublishOriginals();
    g_state = 1;
    return ScanAndDrainLocked(true);
}

int Rescan() {
    void *network = FindNetworkSymbol();
    // Never wait for g_mutex: this can run inside a library's constructor (Java loadLibrary from
    // JNI while the loader's lock is held), and the holder may be waiting for that lock in
    // dl_iterate_phdr. A busy lock leaves the request to its holder, which scans again.
    if (pthread_mutex_trylock(&g_mutex) != 0) {
        g_rescan_pending.store(true, std::memory_order_release);
        return 0;
    }
    int result = kErrorDisabled;
    if (g_state > 0) {
        const bool added = AddNetworkTarget(network);
        if (added) PublishOriginals();
        result = ScanAndDrainLocked(added);  // a new target: every library is looked at again
    }
    pthread_mutex_unlock(&g_mutex);
    // A request that arrived after the holder's last check but before it unlocked.
    if (result >= 0 && g_rescan_pending.load(std::memory_order_acquire)) {
        const int more = Rescan();
        if (more > 0) result += more;
    }
    return result;
}

int SetTable(const uint8_t *blob, size_t size) {
    const HostTable *table = size == 0 ? nullptr : HostTable::Parse(blob, size);
    // The previous table is deliberately leaked (see g_table).
    g_table.store(table, std::memory_order_release);
    return table == nullptr ? 0 : static_cast<int>(table->size());
}

bool IsBlocked(const char *host) { return Blocked(host); }

void UseInPlaceWritesOnly() {
    Lock lock;
    g_in_place_only = true;
}

bool WritesThroughMemory() {
    Lock lock;
    return g_mem_writes;
}

}  // namespace hp
