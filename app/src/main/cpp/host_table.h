// Immutable host-suffix table for the native resolver hooks.
//
// Built on the cold path from the blob the Java side encodes (core NativeHostTable), published
// with one atomic pointer store and probed lock-free on every native lookup. Semantics follow the
// Java HostMatcher exactly: a pattern matches the host itself or any host below it at a label
// boundary, and the most specific pattern wins, so a kept host below a blocked suffix stays allowed.
#pragma once

#include <cstddef>
#include <cstdint>

namespace hp {

// Longest host name the table can hold or a lookup accepts (RFC 1035 presentation form).
inline constexpr size_t kMaxHostLength = 253;

// Blob layout (little endian): "HPT1" | u32 count | count x { u8 flags, u8 length, bytes }.
inline constexpr uint32_t kTableMagic = 0x31545048u;  // "HPT1"
inline constexpr uint32_t kMaxEntries = 65535;
inline constexpr uint8_t kFlagBlock = 1;

class HostTable {
public:
    // Parses [blob]; nullptr when it is malformed (the caller then publishes no table: fail open).
    // Single entries that are not canonical host names are skipped, not fatal.
    static const HostTable *Parse(const uint8_t *blob, size_t size);

    // Whether [host] (a NUL-terminated name as passed to getaddrinfo) is blocked. Never allocates,
    // never fails: anything unusual (too long, non-ASCII, an IP literal, empty labels) is allowed.
    bool Blocks(const char *host) const;

    // Number of usable entries; for tests and the Java side's state report.
    uint32_t size() const { return count_; }

    // Frees a table built by Parse (tests only: published tables are never freed, see resolver_hooks).
    static void Destroy(const HostTable *table);

private:
    struct Slot {
        uint32_t hash;
        uint32_t offset;  // into the names area
        uint8_t length;   // 0: empty slot
        uint8_t block;    // 1: block, 0: keep
    };

    HostTable() = default;

    // 1 block, 0 keep, -1 no pattern for text[0, length).
    int Find(const char *text, size_t length) const;

    uint32_t count_ = 0;
    uint32_t mask_ = 0;
    const Slot *slots_ = nullptr;
    const char *names_ = nullptr;
};

// FNV-1a over [length] bytes; shared with the tests.
uint32_t HashBytes(const char *text, size_t length);

}  // namespace hp
