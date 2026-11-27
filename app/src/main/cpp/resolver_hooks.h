// Native name-resolution hooks: process-wide state and the entry points the JNI layer calls.
//
// Installed only in a protected third-party app whose "block native network requests" rule is on
// when the app starts; nothing here runs, or is even loaded, anywhere else.
#pragma once

#include <cstddef>
#include <cstdint>

namespace hp {

// Results of Install / Rescan below zero.
inline constexpr int kErrorDisabled = -1;   // an earlier Install failed in this process: never retried
inline constexpr int kErrorNoOriginal = -2; // libc's getaddrinfo could not be resolved

// Resolves the original functions and patches every eligible library loaded so far. Runs at most
// once per process (later calls return the first result's sign). Returns the patched slot count.
int Install();

// Patches libraries loaded since the last scan; a no-op returning kErrorDisabled unless Install
// succeeded. Returns the number of slots patched by this call.
int Rescan();

// Publishes the host table encoded in [blob] (see host_table.h). An empty or malformed blob
// publishes none, so every lookup passes through. Returns the number of usable entries.
int SetTable(const uint8_t *blob, size_t size);

// Whether [host] is blocked by the current table (tests).
bool IsBlocked(const char *host);

// Whether slots are written through /proc/self/mem (read-only GOT pages included); false: only
// slots in writable pages are patched (tests and the state log).
bool WritesThroughMemory();

// Tests: before Install, makes it skip /proc/self/mem as if the kernel refused forced writes.
void UseInPlaceWritesOnly();

}  // namespace hp
