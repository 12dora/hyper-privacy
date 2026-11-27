# Host-side tests of the native resolver's pure parts (host table, ELF patcher, maps parser).
# Run: scripts/native-test.sh (configures, builds and runs ctest; CI runs the same script).
cmake_minimum_required(VERSION 3.22.1)
project(hpnative_tests CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NATIVE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
add_compile_options(-Wall -Wextra -Werror -fno-exceptions -fno-rtti -g -O1
    -fsanitize=address,undefined -fno-sanitize-recover=all)
add_link_options(-fsanitize=address,undefined)
include_directories(${NATIVE_SRC})

enable_testing()
add_executable(host_table_test host_table_test.cc ${NATIVE_SRC}/host_table.cc)
add_executable(elf_patch_test elf_patch_test.cc ${NATIVE_SRC}/elf_patch.cc)
add_test(NAME host_table COMMAND host_table_test)
add_test(NAME elf_patch COMMAND elf_patch_test)
