// Host tests of the GOT patcher on synthetic in-memory ELF64 images.
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "check.h"
#include "elf_patch.h"

namespace {

using namespace hp::elf;

constexpr size_t kPage = 0x1000;
constexpr uintptr_t kOriginal = 0x1111;
constexpr uintptr_t kReplacement = 0x2222;
constexpr uint32_t kJumpSlot = kAarch64Relocs.jump_slot;
constexpr uint32_t kGlobDat = kAarch64Relocs.glob_dat;
constexpr uint32_t kAbs64 = 257;  // R_AARCH64_ABS64: never patched

// Segment 0 [0, 0x3000) R: symbols, strings, relocations. Segment 1 [0x3000, 0x6000) RW:
// .dynamic at 0x3000, GOT at 0x4000 (RELRO covers [0x3000, 0x4800)), .data after.
constexpr uint64_t kSymtab = 0x100;
constexpr uint64_t kStrtab = 0x800;
constexpr uint64_t kJmprel = 0x1000;
constexpr uint64_t kRela = 0x2000;
constexpr uint64_t kDynamic = 0x3000;
constexpr uint64_t kGot = 0x4000;
constexpr uint64_t kData = 0x5000;
constexpr size_t kImageSize = 0x6000;

class FakeImage {
public:
    FakeImage() : memory_(static_cast<uint8_t *>(std::aligned_alloc(kPage, kImageSize))) {
        std::memset(memory_, 0, kImageSize);
        AddString("");  // index 0 is the empty name
        AddSymbol("", 0);
        phdrs_.push_back(Phdr{kPtLoad, 4, 0, 0, 0, 0x3000, 0x3000, kPage});
        phdrs_.push_back(Phdr{kPtLoad, 6, 0x3000, 0x3000, 0x3000, 0x3000, 0x3000, kPage});
        phdrs_.push_back(Phdr{kPtDynamic, 6, 0x3000, kDynamic, kDynamic, 0, 0x200, 8});
        phdrs_.push_back(Phdr{kPtGnuRelro, 4, 0x3000, 0x3000, 0x3000, 0x1800, 0x1800, 1});
    }
    ~FakeImage() { std::free(memory_); }
    FakeImage(const FakeImage &) = delete;
    FakeImage &operator=(const FakeImage &) = delete;

    uint32_t AddString(const std::string &text) {
        const auto offset = static_cast<uint32_t>(strings_);
        std::memcpy(memory_ + kStrtab + strings_, text.c_str(), text.size() + 1);
        strings_ += text.size() + 1;
        return offset;
    }

    // Returns the symbol index; [section] 0 means an import.
    uint32_t AddSymbol(const std::string &name, uint16_t section) {
        Sym sym{};
        sym.st_name = name.empty() ? 0 : AddString(name);
        sym.st_shndx = section;
        std::memcpy(memory_ + kSymtab + symbols_ * sizeof(Sym), &sym, sizeof(Sym));
        return static_cast<uint32_t>(symbols_++);
    }

    void AddReloc(bool plt, uint64_t offset, uint32_t symbol, uint32_t type, int64_t addend = 0) {
        Rela rela{offset, (static_cast<uint64_t>(symbol) << 32) | type, addend};
        auto &count = plt ? plt_count_ : rela_count_;
        std::memcpy(memory_ + (plt ? kJmprel : kRela) + count * sizeof(Rela), &rela, sizeof(Rela));
        ++count;
    }

    void SetSlot(uint64_t offset, uintptr_t value) { std::memcpy(memory_ + offset, &value, sizeof(value)); }

    uintptr_t Slot(uint64_t offset) const {
        uintptr_t value;
        std::memcpy(&value, memory_ + offset, sizeof(value));
        return value;
    }

    // Writes .dynamic from the current tables; [extra] entries override or add tags.
    void Finish(std::vector<Dyn> extra = {}) {
        std::vector<Dyn> dyn = {
            {kDtSymTab, kSymtab},
            {kDtStrTab, kStrtab},
            {kDtStrSz, strings_},
            {kDtSymEnt, sizeof(Sym)},
            {kDtJmpRel, kJmprel},
            {kDtPltRelSz, plt_count_ * sizeof(Rela)},
            {kDtPltRel, static_cast<uint64_t>(kDtRela)},
            {kDtRela, kRela},
            {kDtRelaSz, rela_count_ * sizeof(Rela)},
            {kDtRelaEnt, sizeof(Rela)},
        };
        for (const Dyn &e : extra) {
            bool replaced = false;
            for (Dyn &d : dyn) {
                if (d.d_tag == e.d_tag) {
                    d = e;
                    replaced = true;
                }
            }
            if (!replaced) dyn.push_back(e);
        }
        dyn.push_back({kDtNull, 0});
        std::memcpy(memory_ + kDynamic, dyn.data(), dyn.size() * sizeof(Dyn));
    }

    Image View() const { return Image{reinterpret_cast<uintptr_t>(memory_), phdrs_.data(), phdrs_.size()}; }

    uintptr_t Address(uint64_t offset) const { return reinterpret_cast<uintptr_t>(memory_) + offset; }

    std::vector<Phdr> &phdrs() { return phdrs_; }

private:
    uint8_t *memory_;
    std::vector<Phdr> phdrs_;
    size_t strings_ = 0;
    size_t symbols_ = 0;
    size_t plt_count_ = 0;
    size_t rela_count_ = 0;
};

class RecordingWriter final : public Memory {
public:
    bool Readable(uintptr_t address, size_t size) override {
        if (unreadable_start != 0 && address < unreadable_end && address + size > unreadable_start) return false;
        return true;
    }

    bool ReadSlot(uintptr_t slot, uintptr_t &value) override {
        if (!Readable(slot, sizeof(value))) return false;
        std::memcpy(&value, reinterpret_cast<const void *>(slot), sizeof(value));
        return true;
    }

    bool WriteSlot(uintptr_t slot, uintptr_t expected, uintptr_t value, bool relro) override {
        ++writes;
        last_relro = relro;
        if (fail) return false;
        auto *target = reinterpret_cast<uintptr_t *>(slot);
        if (*target != expected) return false;
        *target = value;
        return true;
    }

    int writes = 0;
    bool last_relro = false;
    bool fail = false;
    uintptr_t unreadable_start = 0;  // [start, end) reported unreadable
    uintptr_t unreadable_end = 0;
};

const Target kTargets[] = {
    {"getaddrinfo", kOriginal, kReplacement},
    {"gethostbyname", kOriginal + 1, kReplacement + 1},
};

PatchResult Patch(const FakeImage &image, RecordingWriter &writer) {
    return PatchImage(image.View(), kTargets, 2, kAarch64Relocs, kPage, writer);
}

void PatchesOnlyVerifiedImports() {
    FakeImage image;
    const uint32_t gai = image.AddSymbol("getaddrinfo", 0);
    const uint32_t defined = image.AddSymbol("gethostbyname", 12);  // defined here: never touched
    const uint32_t other = image.AddSymbol("getaddrinfo_other", 0);
    image.AddReloc(true, kGot + 0x00, gai, kJumpSlot);
    image.AddReloc(true, kGot + 0x08, defined, kJumpSlot);
    image.AddReloc(true, kGot + 0x10, other, kJumpSlot);
    image.AddReloc(false, kGot + 0x18, gai, kGlobDat);
    image.AddReloc(false, kGot + 0x20, gai, kGlobDat);      // holds someone else's value
    image.AddReloc(false, kGot + 0x48, gai, kAbs64);        // ABS64 may be a tagged global: never
    image.AddReloc(false, kGot + 0x50, gai, kGlobDat);      // pointer tag in the top byte: still libc's
    image.AddReloc(false, kGot + 0x28, gai, kGlobDat, 8);   // addend: not a plain function pointer
    image.AddReloc(false, 0x9000, gai, kGlobDat);           // outside every segment
    image.AddReloc(false, 0x200, gai, kGlobDat);            // read-only segment
    image.AddReloc(false, kGot + 0x31, gai, kGlobDat);      // misaligned
    image.AddReloc(false, kData, gai, kGlobDat);            // writable, outside RELRO
    image.AddReloc(false, kGot + 0x40, gai, 3);             // a relocation type we never touch
    for (uint64_t offset : {0x00, 0x08, 0x10, 0x18, 0x28, 0x40, 0x48}) image.SetSlot(kGot + offset, kOriginal);
    image.SetSlot(kGot + 0x50, kOriginal | (static_cast<uintptr_t>(0x0b) << 56));
    image.SetSlot(kGot + 0x20, 0x9999);
    image.SetSlot(kData, kOriginal);
    image.Finish();

    RecordingWriter writer;
    const PatchResult result = Patch(image, writer);
    CHECK(result.parsed);
    CHECK_EQ(result.patched, 4);
    CHECK_EQ(result.skipped, 6);
    CHECK_EQ(image.Slot(kGot + 0x00), kReplacement);
    CHECK_EQ(image.Slot(kGot + 0x08), kOriginal);
    CHECK_EQ(image.Slot(kGot + 0x10), kOriginal);
    CHECK_EQ(image.Slot(kGot + 0x18), kReplacement);
    CHECK_EQ(image.Slot(kGot + 0x20), 0x9999u);
    CHECK_EQ(image.Slot(kGot + 0x28), kOriginal);
    CHECK_EQ(image.Slot(kGot + 0x40), kOriginal);
    CHECK_EQ(image.Slot(kGot + 0x48), kOriginal);
    CHECK_EQ(image.Slot(kGot + 0x50), kReplacement);
    CHECK_EQ(image.Slot(kData), kReplacement);
    CHECK(!writer.last_relro);  // the last write was the .data slot

    // A rescan finds everything already redirected: no write, nothing new skipped.
    RecordingWriter again;
    const PatchResult second = Patch(image, again);
    CHECK_EQ(second.patched, 0);
    CHECK_EQ(again.writes, 0);
    CHECK_EQ(second.skipped, 6);
}

void ReportsRelroAndWriteFailures() {
    FakeImage image;
    const uint32_t gai = image.AddSymbol("getaddrinfo", 0);
    image.AddReloc(true, kGot, gai, kJumpSlot);
    image.SetSlot(kGot, kOriginal);
    image.Finish();
    RecordingWriter writer;
    writer.fail = true;
    const PatchResult result = Patch(image, writer);
    CHECK_EQ(result.patched, 0);
    CHECK_EQ(result.skipped, 1);
    CHECK(writer.last_relro);
    CHECK_EQ(image.Slot(kGot), kOriginal);
}

void SkipsMalformedImages() {
    const auto parsed = [](FakeImage &image) {
        RecordingWriter writer;
        const PatchResult result = Patch(image, writer);
        return result.parsed || writer.writes != 0;
    };
    {
        FakeImage image;
        image.Finish({{kDtSymEnt, 16}});
        CHECK(!parsed(image));
    }
    {
        FakeImage image;
        image.Finish({{kDtStrSz, 0x100000}});  // strings run past the segment
        CHECK(!parsed(image));
    }
    {
        FakeImage image;
        image.Finish({{kDtSymTab, UINT64_MAX - 4}});  // overflows the bias
        CHECK(!parsed(image));
    }
    {
        FakeImage image;
        image.Finish();
        image.phdrs().erase(image.phdrs().begin() + 2);  // no PT_DYNAMIC
        CHECK(!parsed(image));
    }
    {
        FakeImage image;
        image.Finish();
        image.phdrs()[2].p_vaddr = 0x7000;  // .dynamic outside every segment
        CHECK(!parsed(image));
    }
    {
        FakeImage image;
        image.Finish();
        image.phdrs().push_back(image.phdrs()[2]);  // two PT_DYNAMIC
        CHECK(!parsed(image));
    }
    {
        FakeImage image;
        image.Finish();
        Image view = image.View();
        view.phnum = 0;
        RecordingWriter writer;
        CHECK(!PatchImage(view, kTargets, 2, kAarch64Relocs, kPage, writer).parsed);
        view = image.View();
        view.phdr = nullptr;
        CHECK(!PatchImage(view, kTargets, 2, kAarch64Relocs, kPage, writer).parsed);
        CHECK(!PatchImage(image.View(), kTargets, 2, kAarch64Relocs, 3000, writer).parsed);  // bad page size
        CHECK(!PatchImage(image.View(), nullptr, 0, kAarch64Relocs, kPage, writer).parsed);
    }
}

void SkipsBadTables() {
    FakeImage image;
    const uint32_t gai = image.AddSymbol("getaddrinfo", 0);
    image.AddReloc(true, kGot, gai, kJumpSlot);
    image.AddReloc(false, kGot + 8, 0x7fffffff, kGlobDat);  // symbol index far past the table
    image.SetSlot(kGot, kOriginal);
    image.SetSlot(kGot + 8, kOriginal);
    // A PLT table size that is not a whole number of entries: the table is ignored, the image is not.
    image.Finish({{kDtPltRelSz, sizeof(Rela) + 3}});
    RecordingWriter writer;
    const PatchResult result = Patch(image, writer);
    CHECK(result.parsed);
    CHECK_EQ(result.patched, 0);
    CHECK_EQ(image.Slot(kGot), kOriginal);
    CHECK_EQ(image.Slot(kGot + 8), kOriginal);
}

void SkipsExecutableSegments() {
    FakeImage image;
    const uint32_t gai = image.AddSymbol("getaddrinfo", 0);
    image.AddReloc(true, kGot, gai, kJumpSlot);
    image.SetSlot(kGot, kOriginal);
    image.Finish();
    image.phdrs()[1].p_flags = 7;  // RWX
    RecordingWriter writer;
    const PatchResult result = Patch(image, writer);
    CHECK_EQ(result.patched, 0);
    CHECK_EQ(writer.writes, 0);
}

void Contains() {
    FakeImage image;
    image.Finish();
    CHECK(ContainsAddress(image.View(), image.Address(0x10)));
    CHECK(ContainsAddress(image.View(), image.Address(0x5fff)));
    CHECK(!ContainsAddress(image.View(), image.Address(0x6000)));
}

void MapsLines() {
    Region r{};
    const std::string rw = "7f0000-7f2000 rw-p 00000000 fd:00 123  /data/app/x/lib/arm64/libx.so";
    CHECK(ParseMapsLine(rw.c_str(), rw.size(), r));
    CHECK_EQ(r.start, 0x7f0000u);
    CHECK_EQ(r.end, 0x7f2000u);
    CHECK_EQ(r.prot, kProtRead | kProtWrite | kProtPrivate);
    const std::string shared = "7f0000-7f2000 rw-s 00000000 00:01 9 /dev/ashmem";
    CHECK(ParseMapsLine(shared.c_str(), shared.size(), r));
    CHECK_EQ(r.prot, kProtRead | kProtWrite);
    const std::string rx = "7f0000-7f2000 r-xp";
    CHECK(ParseMapsLine(rx.c_str(), rx.size(), r));
    CHECK_EQ(r.prot, kProtRead | kProtExec | kProtPrivate);
    for (const std::string bad : {"", "7f0000", "7f0000-", "7f2000-7f0000 rw-p", "7f0000-7f2000 rwzp",
                                  "zz-7f2000 rw-p", "11111111111111111-2 rw-p", "7f0000-7f2000", "7f0000-7f2000 rw-x"}) {
        CHECK(!ParseMapsLine(bad.c_str(), bad.size(), r));
    }
}

void Regions() {
    const Region regions[] = {
        {0x1000, 0x2000, kProtRead | kProtPrivate},
        {0x2000, 0x3000, kProtRead | kProtWrite | kProtPrivate},
        {0x3000, 0x4000, kProtPrivate},  // PROT_NONE
        {0x5000, 0x6000, kProtRead | kProtPrivate},
    };
    CHECK(FindRegion(regions, 4, 0x1800) == &regions[0]);
    CHECK(FindRegion(regions, 4, 0x2000) == &regions[1]);
    CHECK(FindRegion(regions, 4, 0x4800) == nullptr);
    CHECK(FindRegion(regions, 4, 0x6000) == nullptr);
    CHECK(FindRegion(regions, 4, 0x0fff) == nullptr);
    CHECK(RegionsReadable(regions, 4, 0x1ff8, 16));      // spans two adjacent readable regions
    CHECK(!RegionsReadable(regions, 4, 0x2ff8, 16));     // runs into PROT_NONE
    CHECK(!RegionsReadable(regions, 4, 0x4000, 8));      // a hole
    CHECK(!RegionsReadable(regions, 4, 0x5ff8, 16));     // past the end
    CHECK(RegionsReadable(regions, 4, 0x5000, 0x1000));
    CHECK(!RegionsReadable(regions, 4, 0x5000, 0));
    CHECK(!RegionsReadable(nullptr, 0, 0x5000, 8));
}

void UnreadableMemoryIsNeverTouched() {
    FakeImage image;
    const uint32_t gai = image.AddSymbol("getaddrinfo", 0);
    image.AddReloc(true, kGot, gai, kJumpSlot);
    image.SetSlot(kGot, kOriginal);
    image.Finish();
    {
        RecordingWriter memory;  // the slot's page reads as unreadable (e.g. PROT_NONE at runtime)
        memory.unreadable_start = image.Address(kGot);
        memory.unreadable_end = image.Address(kGot + 8);
        const PatchResult result = Patch(image, memory);
        CHECK(result.parsed);
        CHECK_EQ(result.patched, 0);
        CHECK_EQ(result.skipped, 1);
        CHECK_EQ(memory.writes, 0);
    }
    {
        RecordingWriter memory;  // program headers unreadable (a packer protected them): skipped
        const Image view = image.View();
        memory.unreadable_start = reinterpret_cast<uintptr_t>(view.phdr);
        memory.unreadable_end = memory.unreadable_start + sizeof(Phdr);
        CHECK(!Patch(image, memory).parsed);
        CHECK_EQ(memory.writes, 0);
    }
    {
        RecordingWriter memory;  // .dynamic unreadable: the image is skipped
        memory.unreadable_start = image.Address(kDynamic);
        memory.unreadable_end = image.Address(kDynamic + 8);
        CHECK(!Patch(image, memory).parsed);
    }
    {
        FakeImage unreadable;
        const uint32_t sym = unreadable.AddSymbol("getaddrinfo", 0);
        unreadable.AddReloc(true, kGot, sym, kJumpSlot);
        unreadable.SetSlot(kGot, kOriginal);
        unreadable.Finish();
        unreadable.phdrs()[1].p_flags = 2;  // writable but not readable (PF_R missing)
        RecordingWriter memory;
        CHECK(!Patch(unreadable, memory).parsed);  // .dynamic lives there
        CHECK_EQ(memory.writes, 0);
    }
}

void ZeroTableAddressesAreRejected() {
    FakeImage image;
    image.Finish({{kDtSymTab, 0}});
    RecordingWriter memory;
    CHECK(!Patch(image, memory).parsed);
}

void PackedRelocationsAreReported() {
    FakeImage image;
    const uint32_t gai = image.AddSymbol("getaddrinfo", 0);
    image.AddReloc(true, kGot, gai, kJumpSlot);
    image.SetSlot(kGot, kOriginal);
    image.Finish({{kDtAndroidRela, 0x2800}, {kDtAndroidRelaSz, 16}});
    RecordingWriter memory;
    const PatchResult result = Patch(image, memory);
    CHECK(result.parsed && result.packed);
    CHECK_EQ(result.patched, 1);  // the PLT table is never packed and is still handled
}

}  // namespace

int main() {
    PatchesOnlyVerifiedImports();
    ReportsRelroAndWriteFailures();
    SkipsMalformedImages();
    SkipsBadTables();
    SkipsExecutableSegments();
    Contains();
    MapsLines();
    Regions();
    UnreadableMemoryIsNeverTouched();
    ZeroTableAddressesAreRejected();
    PackedRelocationsAreReported();
    return Finish("elf_patch_test");
}
