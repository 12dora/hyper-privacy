// Import-slot (GOT) patching of one loaded ELF64 image, written to be checked on the host.
//
// Everything the patcher reads is first proven to lie inside one of the image's readable PT_LOAD
// segments and to be mapped readable right now (Memory::Readable), every table is bounds-checked,
// and a slot is only rewritten when it lies in a readable, writable, non-executable segment, is
// aligned, is a JUMP_SLOT / GLOB_DAT of an *imported* symbol of one of the requested names and
// still holds exactly the expected original address.
// Anything else leaves the image untouched: the patcher fails open, slot by slot.
//
// Own structure definitions (layout-identical to <elf.h>) keep this file buildable on hosts
// without ELF headers; static_asserts in resolver_hooks.cc pin them to the platform's.
#pragma once

#include <cstddef>
#include <cstdint>

namespace hp::elf {

struct Phdr {
    uint32_t p_type;
    uint32_t p_flags;
    uint64_t p_offset;
    uint64_t p_vaddr;
    uint64_t p_paddr;
    uint64_t p_filesz;
    uint64_t p_memsz;
    uint64_t p_align;
};

struct Dyn {
    int64_t d_tag;
    uint64_t d_val;
};

struct Sym {
    uint32_t st_name;
    uint8_t st_info;
    uint8_t st_other;
    uint16_t st_shndx;
    uint64_t st_value;
    uint64_t st_size;
};

struct Rela {
    uint64_t r_offset;
    uint64_t r_info;
    int64_t r_addend;
};

static_assert(sizeof(Phdr) == 56 && sizeof(Dyn) == 16 && sizeof(Sym) == 24 && sizeof(Rela) == 24);

inline constexpr uint32_t kPtLoad = 1;
inline constexpr uint32_t kPtDynamic = 2;
inline constexpr uint32_t kPtGnuRelro = 0x6474e552;
inline constexpr uint32_t kPfX = 1;
inline constexpr uint32_t kPfW = 2;
inline constexpr uint32_t kPfR = 4;

inline constexpr int64_t kDtNull = 0;
inline constexpr int64_t kDtPltRelSz = 2;
inline constexpr int64_t kDtStrTab = 5;
inline constexpr int64_t kDtSymTab = 6;
inline constexpr int64_t kDtRela = 7;
inline constexpr int64_t kDtRelaSz = 8;
inline constexpr int64_t kDtRelaEnt = 9;
inline constexpr int64_t kDtStrSz = 10;
inline constexpr int64_t kDtSymEnt = 11;
inline constexpr int64_t kDtPltRel = 20;
inline constexpr int64_t kDtJmpRel = 23;
inline constexpr int64_t kDtAndroidRel = 0x6000000f;
inline constexpr int64_t kDtAndroidRelSz = 0x60000010;
inline constexpr int64_t kDtAndroidRela = 0x60000011;
inline constexpr int64_t kDtAndroidRelaSz = 0x60000012;

// Relocation types that bind a GOT slot to a symbol's address on one architecture. Only these
// two are ever patched: they always target the GOT, which is never tagged or instrumented.
// ABS64 / R_X86_64_64 are not: they can sit inside an ordinary global (memory-tagged under MTE
// globals) whose value is not ours to change.
struct RelocTypes {
    uint32_t jump_slot;
    uint32_t glob_dat;
};

inline constexpr RelocTypes kAarch64Relocs{1026, 1025};
inline constexpr RelocTypes kX86_64Relocs{7, 6};

// The address bits of a pointer: arm64 top-byte-ignore (pointer tags, MTE) is masked off before
// comparing a slot with an expected function address.
inline constexpr uintptr_t kAddressMask = (static_cast<uintptr_t>(1) << 56) - 1;

// One loaded image as dl_iterate_phdr describes it.
struct Image {
    uintptr_t bias;
    const Phdr *phdr;
    size_t phnum;
};

// One function to redirect: slots importing [name] that hold [original] get [replacement].
struct Target {
    const char *name;
    uintptr_t original;
    uintptr_t replacement;
};

// Access to the process's memory for the patcher. The patcher dereferences ELF structures
// directly only after [Readable] confirmed them; slots are read and written only through
// [ReadSlot] / [WriteSlot], which never fault.
class Memory {
public:
    // Whether all of [address, address + size) is mapped readable right now.
    virtual bool Readable(uintptr_t address, size_t size) = 0;
    // Reads one aligned slot; false if it cannot be read safely.
    virtual bool ReadSlot(uintptr_t slot, uintptr_t &value) = 0;
    // Replaces [expected] by [value] in one aligned slot. [relro] says the slot lies in the
    // PT_GNU_RELRO range (read-only after relocation). Returns whether the slot now holds [value].
    virtual bool WriteSlot(uintptr_t slot, uintptr_t expected, uintptr_t value, bool relro) = 0;

protected:
    ~Memory() = default;
};

struct PatchResult {
    bool parsed = false;  // false: the image was skipped as a whole (unparsable or nothing to do)
    int patched = 0;      // slots rewritten
    int skipped = 0;      // matching imports left alone (unexpected value, layout or write failure)
    bool packed = false;  // has Android packed relocations (DT_ANDROID_RELA), which are not decoded
};

// Patches the import slots of [targets] in [image]. [page_size] is the runtime page size.
PatchResult PatchImage(const Image &image, const Target *targets, size_t target_count,
                       const RelocTypes &types, size_t page_size, Memory &memory);

// Page protection bits as /proc/self/maps spells them ("rwxp").
inline constexpr int kProtRead = 1;
inline constexpr int kProtWrite = 2;
inline constexpr int kProtExec = 4;
inline constexpr int kProtPrivate = 8;

// One /proc/self/maps entry: [start, end) with kProt* bits.
struct Region {
    uintptr_t start;
    uintptr_t end;
    int prot;
};

// Parses one /proc/self/maps line ("start-end perms ..."; [length] bytes, no newline needed).
bool ParseMapsLine(const char *line, size_t length, Region &region);

// Whether [address, address + size) is covered by consecutive readable regions of the sorted,
// non-overlapping [regions].
bool RegionsReadable(const Region *regions, size_t count, uintptr_t address, size_t size);

// The region of the sorted [regions] holding [address], or nullptr.
const Region *FindRegion(const Region *regions, size_t count, uintptr_t address);

// Whether [address] lies inside one of [image]'s PT_LOAD segments. Reads the program headers
// directly: only for images known to be readable (tests).
bool ContainsAddress(const Image &image, uintptr_t address);

}  // namespace hp::elf
