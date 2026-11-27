#include "elf_patch.h"

#include <cstring>

namespace hp::elf {
namespace {

constexpr size_t kMaxPhdrs = 64;
constexpr size_t kMaxLoads = 16;
constexpr size_t kMaxDynEntries = 1024;
constexpr uint64_t kMaxTableBytes = 64ull << 20;
constexpr size_t kMaxNameLength = 64;

struct Segment {
    uintptr_t start;
    uintptr_t end;
    bool readable;
    bool writable;
    bool executable;
};

struct Layout {
    Segment loads[kMaxLoads];
    size_t load_count = 0;
    uintptr_t relro_start = 0;  // page-rounded outwards: every page the RELRO range touches
    uintptr_t relro_end = 0;
};

// [start, end) of [size] bytes at link-time address [vaddr]: the bias wraps (bionic's load bias
// is "negative" when the first segment's vaddr is above the mapping), the resulting range may not.
static_assert(sizeof(uintptr_t) == sizeof(uint64_t), "ELF64 targets only");

bool Range(uintptr_t bias, uint64_t vaddr, uint64_t size, uintptr_t &start, uintptr_t &end) {
    start = bias + static_cast<uintptr_t>(vaddr);
    if (size > UINTPTR_MAX - start) return false;
    end = start + static_cast<uintptr_t>(size);
    return true;
}

// Index of the readable PT_LOAD segment holding all of [address, address + size), or -1.
int SegmentOf(const Layout &layout, uintptr_t address, uint64_t size) {
    if (size > UINTPTR_MAX - address) return -1;
    const uintptr_t end = address + static_cast<uintptr_t>(size);
    for (size_t i = 0; i < layout.load_count; ++i) {
        const Segment &s = layout.loads[i];
        if (s.readable && address >= s.start && end <= s.end) return static_cast<int>(i);
    }
    return -1;
}

bool ReadLayout(const Image &image, size_t page_size, Memory &memory, Layout &layout, const Phdr *&dynamic) {
    if (image.phdr == nullptr || image.phnum == 0 || image.phnum > kMaxPhdrs) return false;
    if (page_size == 0 || (page_size & (page_size - 1)) != 0) return false;
    // The program headers too may have been made unreadable (packers do that to the ELF header).
    const auto phdr = reinterpret_cast<uintptr_t>(image.phdr);
    if ((phdr % alignof(Phdr)) != 0 || !memory.Readable(phdr, image.phnum * sizeof(Phdr))) return false;
    dynamic = nullptr;
    const Phdr *relro = nullptr;
    for (size_t i = 0; i < image.phnum; ++i) {
        const Phdr &ph = image.phdr[i];
        if (ph.p_type == kPtLoad) {
            if (layout.load_count == kMaxLoads) return false;
            Segment &s = layout.loads[layout.load_count];
            if (!Range(image.bias, ph.p_vaddr, ph.p_memsz, s.start, s.end)) return false;
            s.readable = (ph.p_flags & kPfR) != 0;
            s.writable = (ph.p_flags & kPfW) != 0;
            s.executable = (ph.p_flags & kPfX) != 0;
            ++layout.load_count;
        } else if (ph.p_type == kPtDynamic) {
            if (dynamic != nullptr) return false;
            dynamic = &ph;
        } else if (ph.p_type == kPtGnuRelro) {
            if (relro != nullptr) return false;
            relro = &ph;
        }
    }
    if (dynamic == nullptr || layout.load_count == 0) return false;
    if (relro != nullptr) {
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (!Range(image.bias, relro->p_vaddr, relro->p_memsz, start, end)) return false;
        // Outwards on purpose: a page the loader may have made read-only counts as read-only.
        layout.relro_start = start & ~(page_size - 1);
        if (end > UINTPTR_MAX - (page_size - 1)) return false;
        layout.relro_end = (end + page_size - 1) & ~(page_size - 1);
    }
    return true;
}

struct DynamicInfo {
    uintptr_t symtab = 0;
    uintptr_t strtab = 0;
    uint64_t strsz = 0;
    uint64_t syment = sizeof(Sym);
    uintptr_t jmprel = 0;
    uint64_t pltrelsz = 0;
    int64_t pltrel = kDtRela;
    uintptr_t rela = 0;
    uint64_t relasz = 0;
    uint64_t relaent = sizeof(Rela);
    bool packed = false;
};

bool ReadDynamic(const Image &image, const Layout &layout, const Phdr &dynamic, Memory &memory,
                 DynamicInfo &info) {
    uintptr_t start = 0;
    uintptr_t end = 0;
    if (!Range(image.bias, dynamic.p_vaddr, dynamic.p_memsz, start, end)) return false;
    size_t count = static_cast<size_t>(dynamic.p_memsz / sizeof(Dyn));
    if (count > kMaxDynEntries) count = kMaxDynEntries;
    if (count == 0 || SegmentOf(layout, start, count * sizeof(Dyn)) < 0 || (start % alignof(Dyn)) != 0) {
        return false;
    }
    if (!memory.Readable(start, count * sizeof(Dyn))) return false;
    const auto *entries = reinterpret_cast<const Dyn *>(start);
    // d_ptr values are link-time addresses: the loader never rewrites .dynamic in place. Zero is
    // never a valid table address (it is the ELF header).
    const auto address = [&](uint64_t vaddr, uintptr_t &out) {
        uintptr_t ignored = 0;
        return vaddr != 0 && Range(image.bias, vaddr, 0, out, ignored);
    };
    for (size_t i = 0; i < count; ++i) {
        const Dyn &d = entries[i];
        bool ok = true;
        switch (d.d_tag) {
            case kDtNull: i = count; break;
            case kDtSymTab: ok = address(d.d_val, info.symtab); break;
            case kDtStrTab: ok = address(d.d_val, info.strtab); break;
            case kDtStrSz: info.strsz = d.d_val; break;
            case kDtSymEnt: info.syment = d.d_val; break;
            case kDtJmpRel: ok = address(d.d_val, info.jmprel); break;
            case kDtPltRelSz: info.pltrelsz = d.d_val; break;
            case kDtPltRel: info.pltrel = static_cast<int64_t>(d.d_val); break;
            case kDtRela: ok = address(d.d_val, info.rela); break;
            case kDtRelaSz: info.relasz = d.d_val; break;
            case kDtRelaEnt: info.relaent = d.d_val; break;
            case kDtAndroidRel:
            case kDtAndroidRela: info.packed = true; break;
            default: break;
        }
        if (!ok) return false;
    }
    if (info.symtab == 0 || info.strtab == 0 || info.strsz == 0) return false;
    if (info.syment != sizeof(Sym) || info.relaent != sizeof(Rela)) return false;
    return SegmentOf(layout, info.strtab, info.strsz) >= 0 && memory.Readable(info.strtab, info.strsz) &&
           (info.symtab % alignof(Sym)) == 0;
}

// Index into [targets] of the symbol named at strtab[offset], or -1.
int MatchName(const DynamicInfo &info, uint32_t offset, const Target *targets, size_t count) {
    if (offset >= info.strsz) return -1;
    const char *name = reinterpret_cast<const char *>(info.strtab + offset);
    const uint64_t available = info.strsz - offset;
    for (size_t t = 0; t < count; ++t) {
        const size_t length = std::strlen(targets[t].name);
        if (length >= kMaxNameLength || length + 1 > available) continue;
        if (std::memcmp(name, targets[t].name, length + 1) == 0) return static_cast<int>(t);
    }
    return -1;
}

class Patcher {
public:
    Patcher(const Image &image, const Layout &layout, const DynamicInfo &info, const Target *targets,
            size_t target_count, const RelocTypes &types, Memory &memory, PatchResult &result)
        : image_(image), layout_(layout), info_(info), targets_(targets), target_count_(target_count),
          types_(types), memory_(memory), result_(result) {}

    void Table(uintptr_t table, uint64_t size) {
        if (table == 0 || size == 0) return;
        if (size % sizeof(Rela) != 0 || size > kMaxTableBytes || (table % alignof(Rela)) != 0) return;
        if (SegmentOf(layout_, table, size) < 0 || !memory_.Readable(table, static_cast<size_t>(size))) return;
        const auto *relocs = reinterpret_cast<const Rela *>(table);
        const size_t count = static_cast<size_t>(size / sizeof(Rela));
        for (size_t i = 0; i < count; ++i) One(relocs[i]);
    }

private:
    void One(const Rela &rela) {
        const auto type = static_cast<uint32_t>(rela.r_info & 0xffffffffu);
        if (type != types_.jump_slot && type != types_.glob_dat) return;
        const uint64_t index = rela.r_info >> 32;
        if (index == 0 || index > (UINTPTR_MAX - info_.symtab) / sizeof(Sym)) return;
        const uintptr_t sym_address = info_.symtab + static_cast<uintptr_t>(index) * sizeof(Sym);
        if (SegmentOf(layout_, sym_address, sizeof(Sym)) < 0 || !memory_.Readable(sym_address, sizeof(Sym))) return;
        const auto &sym = *reinterpret_cast<const Sym *>(sym_address);
        const int target = MatchName(info_, sym.st_name, targets_, target_count_);
        if (target < 0) return;
        // Only imports: a library defining its own function of that name keeps it.
        if (sym.st_shndx != 0 || rela.r_addend != 0) {
            ++result_.skipped;
            return;
        }
        uintptr_t slot = 0;
        uintptr_t ignored = 0;
        if (!Range(image_.bias, rela.r_offset, sizeof(uintptr_t), slot, ignored) ||
            (slot % alignof(uintptr_t)) != 0) {
            ++result_.skipped;
            return;
        }
        // A GOT slot lives in a readable, writable, non-executable segment; anything else is not one.
        const int segment = SegmentOf(layout_, slot, sizeof(uintptr_t));
        if (segment < 0 || !layout_.loads[segment].writable || layout_.loads[segment].executable) {
            ++result_.skipped;
            return;
        }
        const Target &t = targets_[target];
        uintptr_t current = 0;
        if (!memory_.ReadSlot(slot, current)) {
            ++result_.skipped;
            return;
        }
        if ((current & kAddressMask) == (t.replacement & kAddressMask)) return;  // already ours (a rescan)
        if ((current & kAddressMask) != (t.original & kAddressMask)) {  // someone else's hook: leave it
            ++result_.skipped;
            return;
        }
        const bool relro = slot >= layout_.relro_start && slot < layout_.relro_end;
        if (memory_.WriteSlot(slot, current, t.replacement, relro)) {
            ++result_.patched;
        } else {
            ++result_.skipped;
        }
    }

    const Image &image_;
    const Layout &layout_;
    const DynamicInfo &info_;
    const Target *targets_;
    size_t target_count_;
    const RelocTypes &types_;
    Memory &memory_;
    PatchResult &result_;
};

bool ParseHex(const char *line, size_t length, size_t &i, uintptr_t &out) {
    out = 0;
    size_t digits = 0;
    for (; i < length; ++i, ++digits) {
        const char c = line[i];
        int v;
        if (c >= '0' && c <= '9') {
            v = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            v = c - 'a' + 10;
        } else {
            break;
        }
        if (digits == sizeof(uintptr_t) * 2) return false;
        out = (out << 4) | static_cast<uintptr_t>(v);
    }
    return digits > 0;
}

}  // namespace

bool ParseMapsLine(const char *line, size_t length, Region &region) {
    size_t i = 0;
    uintptr_t start = 0;
    uintptr_t end = 0;
    if (!ParseHex(line, length, i, start) || i >= length || line[i++] != '-' || !ParseHex(line, length, i, end)) {
        return false;
    }
    if (i + 5 > length || line[i] != ' ' || start >= end) return false;
    const char *perms = line + i + 1;
    if ((perms[0] != 'r' && perms[0] != '-') || (perms[1] != 'w' && perms[1] != '-') ||
        (perms[2] != 'x' && perms[2] != '-') || (perms[3] != 'p' && perms[3] != 's')) {
        return false;
    }
    region.start = start;
    region.end = end;
    region.prot = (perms[0] == 'r' ? kProtRead : 0) | (perms[1] == 'w' ? kProtWrite : 0) |
                  (perms[2] == 'x' ? kProtExec : 0) | (perms[3] == 'p' ? kProtPrivate : 0);
    return true;
}

const Region *FindRegion(const Region *regions, size_t count, uintptr_t address) {
    if (regions == nullptr) return nullptr;
    size_t low = 0;
    size_t high = count;
    while (low < high) {
        const size_t mid = low + (high - low) / 2;
        if (regions[mid].end <= address) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    if (low < count && regions[low].start <= address && address < regions[low].end) return &regions[low];
    return nullptr;
}

bool RegionsReadable(const Region *regions, size_t count, uintptr_t address, size_t size) {
    if (size == 0 || size > UINTPTR_MAX - address) return false;
    const uintptr_t end = address + size;
    const Region *region = FindRegion(regions, count, address);
    if (region == nullptr) return false;
    const Region *last = regions + count;
    while (true) {
        if ((region->prot & kProtRead) == 0) return false;
        if (region->end >= end) return true;
        const Region *next = region + 1;
        if (next == last || next->start != region->end) return false;  // a hole
        region = next;
    }
}

bool ContainsAddress(const Image &image, uintptr_t address) {
    if (image.phdr == nullptr || image.phnum > kMaxPhdrs) return false;
    for (size_t i = 0; i < image.phnum; ++i) {
        const Phdr &ph = image.phdr[i];
        if (ph.p_type != kPtLoad) continue;
        uintptr_t start = 0;
        uintptr_t end = 0;
        if (Range(image.bias, ph.p_vaddr, ph.p_memsz, start, end) && address >= start && address < end) {
            return true;
        }
    }
    return false;
}

PatchResult PatchImage(const Image &image, const Target *targets, size_t target_count,
                       const RelocTypes &types, size_t page_size, Memory &memory) {
    PatchResult result;
    if (targets == nullptr || target_count == 0) return result;
    Layout layout;
    const Phdr *dynamic = nullptr;
    if (!ReadLayout(image, page_size, memory, layout, dynamic)) return result;
    DynamicInfo info;
    if (!ReadDynamic(image, layout, *dynamic, memory, info)) return result;
    result.parsed = true;
    result.packed = info.packed;
    Patcher patcher(image, layout, info, targets, target_count, types, memory, result);
    // Calls through the PLT (JUMP_SLOT; never packed), then -fno-plt / address-taken imports
    // (GLOB_DAT) in the plain RELA table. Android packed relocations (DT_ANDROID_RELA) are not
    // decoded: GLOB_DAT imports packed there stay unpatched (fail open).
    if (info.pltrel == kDtRela) patcher.Table(info.jmprel, info.pltrelsz);
    patcher.Table(info.rela, info.relasz);
    return result;
}

}  // namespace hp::elf
