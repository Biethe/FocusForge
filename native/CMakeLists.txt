cmake_minimum_required(VERSION 3.22.1)
# C as well as CXX: ggml is C, and enabling it here rather than relying on
# llama.cpp's own project() call keeps the toolchain checks in one place.
project(focusforge_llm C CXX)

# ============================================================================
#  THE HARD RULE (CLAUDE.md §2)
#
#  The operator's Samsung A20e is an Exynos 7884B: Armv8.0-A baseline.
#  Features are exactly: fp asimd aes sha1 sha2 crc32.
#  It has NO dotprod, NO i8mm, NO SVE, NO LSE atomics, NO fp16 arithmetic.
#
#  llama.cpp's Android examples routinely carry -march=armv8.2-a+dotprod or
#  +i8mm. Those binaries SIGILL on this device — the instruction simply does
#  not exist. Every flag below exists to make that impossible, and the guard
#  at the bottom fails the build rather than shipping an APK that crashes on
#  the only phone we have.
# ============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- ggml / llama.cpp configuration -----------------------------------------
# GGML_NATIVE already defaults OFF when cross-compiling, but state it: with it
# ON, ggml runs -mcpu=native against the *build host* and bakes in whatever the
# CI runner happens to support (the arm64 runner has i8mm and SVE2 — exactly
# the instructions that would kill us).
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)

# The single flag that decides everything. ggml appends this verbatim as
# -march=${GGML_CPU_ARM_ARCH} and then *feature-detects* against it: with a
# plain armv8-a, __ARM_FEATURE_DOTPROD / MATMUL_INT8 / SVE are undefined, so
# those kernels are never compiled in at all.
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)

# Builds one backend per ISA level and picks at runtime. Attractive-sounding
# and wrong for us: it requires GGML_BACKEND_DL, ships variants this CPU can
# never run, and moves the arch decision somewhere we cannot audit.
set(GGML_CPU_ALL_VARIANTS  OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)

# KleidiAI's kernels are dotprod/i8mm/SVE — every one of them is unusable here.
set(GGML_CPU_KLEIDIAI      OFF CACHE BOOL "" FORCE)

# No OpenMP runtime in the NDK sysroot; ggml uses its own thread pool.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# Off for the smoke test to keep the variable count at one. It is a candidate
# *measured* optimization later — exactly the kind of A/B the Phase 6 governor
# exists to decide, rather than something we assume helps.
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

# --- our JNI layer ----------------------------------------------------------
add_library(focusforge_llm SHARED focusforge_llm.cpp)

target_compile_options(focusforge_llm PRIVATE -march=armv8-a)
target_link_libraries(focusforge_llm PRIVATE llama ggml android log)

# ============================================================================
#  The guard.
#
#  Reads back the flags CMake actually resolved — ours and ggml's — and fails
#  the build if any Armv8.2+ feature reached them. This is deliberately a
#  build failure and not a warning: a warning scrolls past in CI, and the cost
#  of missing it is an APK that SIGILLs on the operator's only phone.
# ============================================================================
function(focusforge_assert_baseline target)
    get_target_property(_opts ${target} COMPILE_OPTIONS)
    if (NOT _opts)
        set(_opts "")
    endif()
    set(_all "${_opts} ${CMAKE_CXX_FLAGS} ${CMAKE_C_FLAGS}")
    foreach (_banned "dotprod" "i8mm" "\\+sve" "sme" "armv8.1" "armv8.2" "armv8.3"
                     "armv8.4" "armv8.5" "armv8.6" "armv8.7" "armv9" "mcpu=native"
                     "march=native")
        if (_all MATCHES "${_banned}")
            message(FATAL_ERROR
                "ARMV8.0 BASELINE VIOLATED on target '${target}': found '${_banned}' in:\n"
                "  ${_all}\n"
                "The A20e (Exynos 7884B) will SIGILL on this binary. See CLAUDE.md §2.")
        endif()
    endforeach()
    message(STATUS "[focusforge] baseline OK for ${target}: ${_all}")
endfunction()

focusforge_assert_baseline(focusforge_llm)
foreach (_t llama ggml ggml-base ggml-cpu)
    if (TARGET ${_t})
        focusforge_assert_baseline(${_t})
    endif()
endforeach()
