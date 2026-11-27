# On-device test of the native resolver hooks; built with the NDK toolchain by
# scripts/native-device-test.sh, never part of the app build.
cmake_minimum_required(VERSION 3.22.1)
project(hpnative_device_tests CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(NATIVE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../main/cpp)
add_compile_options(-Wall -Wextra -Werror -fno-exceptions -fno-rtti -O2)
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    add_compile_options(-mbranch-protection=standard)
endif()

# Two copies of one stand-in library: one loaded before the hooks are installed, one after.
foreach(stage early late norelro)
    add_library(hptarget_${stage} SHARED target_lib.cc)
    target_link_libraries(hptarget_${stage} PRIVATE android)
endforeach()
# Writable GOT (no RELRO, lazy-style layout) for the in-place fallback.
target_link_options(hptarget_norelro PRIVATE -Wl,-z,norelro -Wl,-z,lazy)

add_executable(hp_device_test device_test.cc
    ${NATIVE_SRC}/host_table.cc ${NATIVE_SRC}/elf_patch.cc ${NATIVE_SRC}/resolver_hooks.cc)
target_include_directories(hp_device_test PRIVATE ${NATIVE_SRC})
target_link_libraries(hp_device_test PRIVATE dl)
