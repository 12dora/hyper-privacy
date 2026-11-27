# Native name-resolution hooks for protected third-party apps (rule native.dns), arm64-v8a only.
cmake_minimum_required(VERSION 3.22.1)
project(hpnative CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(hpnative SHARED
    host_table.cc
    elf_patch.cc
    resolver_hooks.cc
    jni_entry.cc)

target_compile_options(hpnative PRIVATE
    -O2
    -Wall -Wextra -Werror
    -fno-exceptions -fno-rtti
    -fvisibility=hidden -fvisibility-inlines-hidden
    -fstack-protector-strong
    -ffunction-sections -fdata-sections)

if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    target_compile_options(hpnative PRIVATE -mbranch-protection=standard)
endif()

# libc++ is linked statically (ANDROID_STL=c++_static, set by Gradle) and garbage-collected; only
# the JNI symbols are exported.
target_link_options(hpnative PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map
    -Wl,-z,max-page-size=16384)

target_link_libraries(hpnative PRIVATE dl)
