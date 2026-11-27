#include "host_table.h"

#include <cstdlib>
#include <cstring>
#include <new>

namespace hp {
namespace {

constexpr uint32_t kFnvOffset = 2166136261u;
constexpr uint32_t kFnvPrime = 16777619u;
constexpr size_t kHeaderSize = 8;
constexpr size_t kEntryHeader = 2;

uint32_t ReadU32(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

// A canonical pattern as HostNames.normalize produces it: printable ASCII without upper case,
// whitespace or empty labels. Rejected entries are skipped.
bool IsCanonicalPattern(const uint8_t *name, size_t length) {
    if (length == 0 || length > kMaxHostLength) return false;
    if (name[0] == '.' || name[length - 1] == '.') return false;
    for (size_t i = 0; i < length; ++i) {
        const uint8_t c = name[i];
        if (c <= 0x20 || c >= 0x7F || (c >= 'A' && c <= 'Z')) return false;
        if (c == '.' && name[i + 1] == '.') return false;  // i + 1 < length: the last byte is not '.'
    }
    return true;
}

}  // namespace

uint32_t HashBytes(const char *text, size_t length) {
    uint32_t h = kFnvOffset;
    for (size_t i = 0; i < length; ++i) {
        h ^= static_cast<uint8_t>(text[i]);
        h *= kFnvPrime;
    }
    return h;
}

const HostTable *HostTable::Parse(const uint8_t *blob, size_t size) {
    if (blob == nullptr || size < kHeaderSize || ReadU32(blob) != kTableMagic) return nullptr;
    const uint32_t declared = ReadU32(blob + 4);
    if (declared > kMaxEntries) return nullptr;

    // First pass: validate the framing and count the usable entries and their bytes.
    size_t pos = kHeaderSize;
    uint32_t usable = 0;
    size_t name_bytes = 0;
    for (uint32_t i = 0; i < declared; ++i) {
        if (size - pos < kEntryHeader) return nullptr;
        const size_t length = blob[pos + 1];
        if (size - pos - kEntryHeader < length) return nullptr;
        if (IsCanonicalPattern(blob + pos + kEntryHeader, length)) {
            ++usable;
            name_bytes += length;
        }
        pos += kEntryHeader + length;
    }
    if (pos != size) return nullptr;

    uint32_t capacity = 8;
    while (capacity < usable * 2u) capacity <<= 1;  // usable <= 65535, so no overflow
    const size_t slots_bytes = sizeof(Slot) * capacity;
    const size_t total = sizeof(HostTable) + slots_bytes + name_bytes;
    static_assert(sizeof(HostTable) % alignof(Slot) == 0, "slots follow the header in one block");
    void *memory = std::calloc(1, total);
    if (memory == nullptr) return nullptr;

    auto *table = new (memory) HostTable();
    auto *slots = reinterpret_cast<Slot *>(static_cast<uint8_t *>(memory) + sizeof(HostTable));
    char *names = reinterpret_cast<char *>(slots) + slots_bytes;
    table->mask_ = capacity - 1;
    table->slots_ = slots;
    table->names_ = names;

    // Second pass: insert. On a duplicate pattern the first entry wins, as in HostMatcher.of.
    pos = kHeaderSize;
    uint32_t offset = 0;
    for (uint32_t i = 0; i < declared; ++i) {
        const uint8_t flags = blob[pos];
        const size_t length = blob[pos + 1];
        const auto *name = reinterpret_cast<const char *>(blob + pos + kEntryHeader);
        pos += kEntryHeader + length;
        if (!IsCanonicalPattern(blob + pos - length, length)) continue;
        if (table->Find(name, length) >= 0) continue;
        const uint32_t hash = HashBytes(name, length);
        uint32_t slot = hash & table->mask_;
        while (slots[slot].length != 0) slot = (slot + 1) & table->mask_;
        std::memcpy(names + offset, name, length);
        slots[slot] = Slot{hash, offset, static_cast<uint8_t>(length),
                           static_cast<uint8_t>((flags & kFlagBlock) != 0 ? 1 : 0)};
        offset += static_cast<uint32_t>(length);
        ++table->count_;
    }
    return table;
}

void HostTable::Destroy(const HostTable *table) {
    if (table == nullptr) return;
    table->~HostTable();
    std::free(const_cast<HostTable *>(table));
}

int HostTable::Find(const char *text, size_t length) const {
    if (slots_ == nullptr) return -1;
    const uint32_t hash = HashBytes(text, length);
    uint32_t slot = hash & mask_;
    while (true) {
        const Slot &entry = slots_[slot];
        if (entry.length == 0) return -1;
        if (entry.hash == hash && entry.length == length &&
            std::memcmp(names_ + entry.offset, text, length) == 0) {
            return entry.block;
        }
        slot = (slot + 1) & mask_;
    }
}

bool HostTable::Blocks(const char *host) const {
    if (host == nullptr || count_ == 0) return false;
    // Canonical copy on the stack: lower case, one trailing dot dropped (HostNames.normalize).
    char canonical[kMaxHostLength + 2];
    size_t length = 0;
    bool named = false;  // has a character other than a digit or a dot: not a numeric IPv4 form
    while (true) {
        const auto c = static_cast<uint8_t>(host[length]);
        if (c == 0) break;
        if (length > kMaxHostLength) return false;  // a 254th byte is only allowed as the trailing dot
        if (c <= 0x20 || c >= 0x7F || c == ':') return false;  // non-ASCII (IDN), whitespace, IPv6
        if (c != '.' && (c < '0' || c > '9')) named = true;
        canonical[length++] = static_cast<char>(c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c);
    }
    if (length > 0 && canonical[length - 1] == '.') --length;
    if (length == 0 || length > kMaxHostLength || !named || canonical[0] == '.') return false;
    for (size_t i = 1; i < length; ++i) {
        if (canonical[i] == '.' && canonical[i - 1] == '.') return false;
    }
    // Most specific suffix first, one probe per label boundary.
    size_t start = 0;
    while (true) {
        const int found = Find(canonical + start, length - start);
        if (found >= 0) return found == 1;
        const void *dot = std::memchr(canonical + start, '.', length - start);
        if (dot == nullptr) return false;
        start = static_cast<size_t>(static_cast<const char *>(dot) - canonical) + 1;
    }
}

}  // namespace hp
