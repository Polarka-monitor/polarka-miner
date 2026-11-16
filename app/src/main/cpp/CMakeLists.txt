cmake_minimum_required(VERSION 3.22.1)
project(polarkaminer C CXX ASM)

# RandomX as it stands in the TrueNorth sources (src/randomx), unchanged. The folder is given by the build.
if(NOT RANDOMX_DIR OR NOT EXISTS "${RANDOMX_DIR}/src/randomx.h")
  message(FATAL_ERROR "RANDOMX_DIR must point at src/randomx of the TrueNorth sources")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory("${RANDOMX_DIR}" randomx EXCLUDE_FROM_ALL)

add_library(polarkaminer SHARED bench.cpp)
target_include_directories(polarkaminer PRIVATE "${RANDOMX_DIR}/src")
target_compile_options(polarkaminer PRIVATE -O3 -Wall)
target_link_libraries(polarkaminer randomx log)

# RandomX copies its hand-written arm64 code into a buffer at run time and runs the copy. Two branches in that
# code go to labels that are global symbols. In a shared library the linker sends such branches through its
# jump table, and the copy then jumps to wherever the table would have been: outside the buffer. Keeping the
# symbols of the static library out of the export list, and binding references inside this library, makes the
# linker write the branches directly. tools/check-so.sh fails the build check when it finds such an entry.
target_link_options(polarkaminer PRIVATE -Wl,--exclude-libs,ALL -Wl,-Bsymbolic)
