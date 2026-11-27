// Host tests of the native host table: same semantics as core HostMatcher / NetworkMatchers.findHost.
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "check.h"
#include "host_table.h"

namespace {

using Entries = std::vector<std::pair<std::string, bool>>;

std::vector<uint8_t> Encode(const Entries &entries) {
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

const hp::HostTable *Build(const Entries &entries) {
    const auto blob = Encode(entries);
    return hp::HostTable::Parse(blob.data(), blob.size());
}

void Matching() {
    const auto *table = Build({{"keep.ads.example", false}, {"ads.example", true}, {"tracker.test", true}});
    CHECK(table != nullptr);
    CHECK_EQ(table->size(), 3u);
    CHECK(table->Blocks("ads.example"));
    CHECK(table->Blocks("a.b.ads.example"));
    CHECK(table->Blocks("TRACKER.Test"));          // case-insensitive
    CHECK(table->Blocks("cdn.tracker.test."));     // one trailing dot (FQDN)
    CHECK(!table->Blocks("badads.example"));       // label boundary only
    CHECK(!table->Blocks("example"));
    CHECK(!table->Blocks("keep.ads.example"));     // the most specific pattern wins
    CHECK(!table->Blocks("x.keep.ads.example"));
    CHECK(table->Blocks("eep.ads.example"));
    CHECK(!table->Blocks("ads.example.."));        // empty label
    CHECK(!table->Blocks(".ads.example"));
    CHECK(!table->Blocks("a..ads.example"));
    CHECK(!table->Blocks(""));
    CHECK(!table->Blocks(nullptr));
    CHECK(!table->Blocks("ads.example "));         // whitespace
    CHECK(!table->Blocks("b\xc3\xbc.ads.example"));  // non-ASCII (IDN): allowed, fail open
    hp::HostTable::Destroy(table);
}

void Literals() {
    // Numeric hosts never match, even when a pattern would: IP literals are out of scope.
    const auto *table = Build({{"1.1", true}, {"example", true}});
    CHECK(table != nullptr);
    CHECK(!table->Blocks("1.1.1.1"));
    CHECK(!table->Blocks("::1"));
    CHECK(!table->Blocks("2001:db8::1.example"));
    CHECK(table->Blocks("a1.example"));
    hp::HostTable::Destroy(table);
}

void Lengths() {
    std::string label(63, 'a');
    std::string longest = label + "." + label + "." + label + "." + std::string(61, 'b');  // 253
    CHECK_EQ(longest.size(), 253u);
    const auto *table = Build({{longest, true}, {"example", true}});
    CHECK(table != nullptr);
    CHECK(table->Blocks(longest.c_str()));
    CHECK(table->Blocks((longest + ".").c_str()));
    CHECK(!table->Blocks(("c" + longest).c_str()));  // 254 characters: too long, allowed
    std::string huge(5000, 'a');
    huge += ".example";
    CHECK(!table->Blocks(huge.c_str()));
    hp::HostTable::Destroy(table);
}

void Duplicates() {
    // First entry wins, as in HostMatcher.of.
    const auto *table = Build({{"dup.example", false}, {"dup.example", true}});
    CHECK(table != nullptr);
    CHECK_EQ(table->size(), 1u);
    CHECK(!table->Blocks("dup.example"));
    hp::HostTable::Destroy(table);
}

void NonCanonicalEntriesAreSkipped() {
    const auto *table = Build({{"Upper.example", true}, {"a..b", true}, {".lead", true}, {"ok.example", true}});
    CHECK(table != nullptr);
    CHECK_EQ(table->size(), 1u);
    CHECK(table->Blocks("ok.example"));
    CHECK(!table->Blocks("upper.example"));
    hp::HostTable::Destroy(table);
}

void Malformed() {
    auto blob = Encode({{"ads.example", true}});
    CHECK(hp::HostTable::Parse(nullptr, 0) == nullptr);
    CHECK(hp::HostTable::Parse(blob.data(), 7) == nullptr);              // short header
    CHECK(hp::HostTable::Parse(blob.data(), blob.size() - 1) == nullptr);  // truncated entry
    auto trailing = blob;
    trailing.push_back(0);
    CHECK(hp::HostTable::Parse(trailing.data(), trailing.size()) == nullptr);
    auto magic = blob;
    magic[0] = 'X';
    CHECK(hp::HostTable::Parse(magic.data(), magic.size()) == nullptr);
    auto count = blob;
    count[4] = 2;  // declares more entries than present
    CHECK(hp::HostTable::Parse(count.data(), count.size()) == nullptr);
    auto huge = blob;
    huge[6] = 0x01;  // 65537 entries
    CHECK(hp::HostTable::Parse(huge.data(), huge.size()) == nullptr);
    const auto *empty = Build({});
    CHECK(empty != nullptr);
    CHECK(!empty->Blocks("ads.example"));
    hp::HostTable::Destroy(empty);
}

void MatchesKotlinEncoder() {
    // Golden bytes of NativeHostTable.encode(listOf("keep.ads.example" to false, "ads.example" to true))
    // (core NativeHostTableTest.golden): the two sides agree on the format.
    const uint8_t golden[] = {'H', 'P', 'T', '1', 2, 0, 0, 0, 0, 16, 'k', 'e', 'e', 'p', '.', 'a', 'd', 's', '.',
                              'e', 'x', 'a', 'm', 'p', 'l', 'e', 1, 11, 'a', 'd', 's', '.', 'e', 'x', 'a', 'm',
                              'p', 'l', 'e'};
    const auto *table = hp::HostTable::Parse(golden, sizeof(golden));
    CHECK(table != nullptr);
    CHECK(table->Blocks("x.ads.example"));
    CHECK(!table->Blocks("x.keep.ads.example"));
    hp::HostTable::Destroy(table);
}

void ManyEntries() {
    Entries entries;
    for (int i = 0; i < 5000; ++i) entries.emplace_back("host" + std::to_string(i) + ".example", i % 2 == 0);
    const auto *table = Build(entries);
    CHECK(table != nullptr);
    CHECK_EQ(table->size(), 5000u);
    CHECK(table->Blocks("a.host0.example"));
    CHECK(!table->Blocks("a.host1.example"));
    CHECK(table->Blocks("host4998.example"));
    CHECK(!table->Blocks("host5000.example"));
    hp::HostTable::Destroy(table);
}

}  // namespace

int main() {
    Matching();
    Literals();
    Lengths();
    Duplicates();
    NonCanonicalEntriesAreSkipped();
    Malformed();
    MatchesKotlinEncoder();
    ManyEntries();
    return Finish("host_table_test");
}
