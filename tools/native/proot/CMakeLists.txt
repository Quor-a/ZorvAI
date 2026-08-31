# =============================================================================
# proot for Android —— 官方 NDK + CMake 交叉编译
# -----------------------------------------------------------------------------
# 由 tools/native/build-proot.sh 调用，传入：
#   -DCMAKE_TOOLCHAIN_FILE = $NDK/build/cmake/android.toolchain.cmake
#   -DANDROID_ABI          = arm64-v8a | armeabi-v7a | x86_64 | x86
#   -DANDROID_PLATFORM     = android-26
#   -DPROOT_SRC            = proot 源码根（含 src/loader/loader.c、src/build.h）
#   -DTALLOC_SRC           = libtalloc 源码根（含 config.h，由 build-proot.sh 生成）
#
# 产出：libproot.so —— 可直接执行的 PIE（ET_DYN），命名 .so 仅为绕过
#       Android 10+ 禁止 exec 应用数据目录内二进制的限制。
#
# 设计要点（对应原 build-proot.sh 的「最容易漏的一步」）：
#   * proot 的 execve/enter.c 引用 _binary_loader_elf_start/_end（x86_64 还有
#     _binary_loader_m32_elf_*）。这些符号来自「把编译好的裸 ELF loader 用
#     objcopy --input-target binary 当数据塞进 .o」。本 CMake 用 add_custom_command
#     完整复刻这一步（编译 loader.c + assembly.S → 链接裸 ELF → strip → objcopy）。
#   * libtalloc 静态链入，运行时只依赖 Android 的 libc.so / libdl.so。
#   * Android/Ubuntu 魔改（参考 termux proot 适配）：ARG_MAX、_FILE_OFFSET_BITS=64、
#     _GNU_SOURCE，以及 seccomp 过滤（build.h 里的 HAVE_SECCOMP_FILTER）。
# =============================================================================

cmake_minimum_required(VERSION 3.18)
project(proot_android C)

# ── 入参 ────────────────────────────────────────────────────────────────────
set(PROOT_SRC     "" CACHE PATH "proot source root (contains src/)")
set(TALLOC_SRC    "" CACHE PATH "libtalloc source root (contains config.h)")
if(NOT PROOT_SRC OR NOT EXISTS "${PROOT_SRC}/src/loader/loader.c")
  message(FATAL_ERROR "PROOT_SRC 无效或未指向 proot 源码根: ${PROOT_SRC}")
endif()
if(NOT TALLOC_SRC OR NOT EXISTS "${TALLOC_SRC}/talloc.c")
  message(FATAL_ERROR "TALLOC_SRC 无效或未指向 libtalloc 源码根: ${TALLOC_SRC}")
endif()

# ── ABI → loader 链接地址 / objcopy 目标格式 ─────────────────────────────────
# 与 src/arch.h 的 ARCH_*、HAS_LOADER_32BIT 保持一致
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(LOADER_ADDR   "0x2000000000")
  set(OBJCOPY_FMT   "elf64-littleaarch64")
  set(OBJCOPY_ARCH  "aarch64")
  set(LOADER_M32    OFF)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(LOADER_ADDR   "0x10000000")
  set(OBJCOPY_FMT   "elf32-littlearm")
  set(OBJCOPY_ARCH  "arm")
  set(LOADER_M32    OFF)
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(LOADER_ADDR   "0x600000000000")
  set(OBJCOPY_FMT   "elf64-x86-64")
  set(OBJCOPY_ARCH  "x86-64")
  set(LOADER_M32    ON)
elseif(ANDROID_ABI STREQUAL "x86")
  set(LOADER_ADDR   "0xa0000000")
  set(OBJCOPY_FMT   "elf32-i386")
  set(OBJCOPY_ARCH  "i386")
  set(LOADER_M32    OFF)
else()
  message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
endif()

set(OBJDIR "${CMAKE_CURRENT_BINARY_DIR}/obj")
file(MAKE_DIRECTORY "${OBJDIR}")

# ── 1) libtalloc（proot 硬依赖，静态链入）────────────────────────────────────
# config.h 由 build-proot.sh 预先生成到 TALLOC_SRC/config.h；这里只补少量宏。
add_library(talloc STATIC "${TALLOC_SRC}/talloc.c")
target_include_directories(talloc PRIVATE
  "${TALLOC_SRC}"
  "${TALLOC_SRC}/lib/replace")
target_compile_definitions(talloc PRIVATE __STDC_WANT_LIB_EXT1__=1)
# 不开 -fPIC 也行（静态库），但 proot 主体要 PIE，talloc 对象被链进 PIE 需可重定位
target_compile_options(talloc PRIVATE -fPIC -O2 -Wno-unused-function)

# ── 2) loader 嵌入（最容易漏的一步）──────────────────────────────────────────
# 用 NDK 提供的「目标三元组 clang」(aarch64-linux-android26-clang 等，Windows 上为 .cmd)
# 编译/链接 loader：该包装器内含 --target / --sysroot，确保产出的是目标架构 ELF，
# 否则裸 clang.exe 会落到主机的 lld-link，拒绝 ELF 专属的 -Ttext / -z noexecstack。
# 这正是原手工脚本用 $CC（三元组 clang）能成、而裸 clang 失败的根因。
get_filename_component(_NDK_BIN "${CMAKE_C_COMPILER}" DIRECTORY)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(_TRIPLE "armv7a-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(_TRIPLE "x86_64-linux-android")
else()
  set(_TRIPLE "i686-linux-android")
endif()
string(REGEX REPLACE "^android-" "" _API_NUM "${ANDROID_PLATFORM}")
set(_LOADER_CC "${_NDK_BIN}/${_TRIPLE}${_API_NUM}-clang")
if(WIN32 AND EXISTS "${_LOADER_CC}.cmd")
  set(_LOADER_CC "${_LOADER_CC}.cmd")
endif()
message(STATUS "loader 编译器: ${_LOADER_CC}")

# 为每个 loader 生成「裸 ELF → objcopy 包裹 .o」：
#   loader.c + assembly.S → loader.o(+asm.o) → ld -Ttext → strip → objcopy
# 产物符号：loader.elf      → _binary_loader_elf_start/_end
#           loader-m32.elf  → _binary_loader_m32_elf_start/_end  (仅 x86_64)
function(build_loader NAME EXTRA_ADDR EXTRA_FLAGS)
  set(elf   "${OBJDIR}/${NAME}.elf")
  set(wrap  "${OBJDIR}/${NAME}-wrapped.o")
  add_custom_command(
    OUTPUT  "${wrap}"
    # 关键：objcopy 用输入文件的「名字」派生符号（_binary_<name>_start）。
    # 必须 cd 进 OBJDIR 再用 basename「loader.elf」调用，否则会拿绝对路径当名字，
    # 生成 _binary_D__..._loader_elf_start 这种错误符号，proot 的 enter.c 找不到。
    WORKING_DIRECTORY "${OBJDIR}"
    COMMAND "${_LOADER_CC}" -O2 -fPIC -ffreestanding -D_GNU_SOURCE
            -I${PROOT_SRC}/src
            -c "${PROOT_SRC}/src/loader/loader.c" -o ${NAME}.o
    COMMAND "${_LOADER_CC}" -O2 -fPIC -ffreestanding -D_GNU_SOURCE
            -I${PROOT_SRC}/src
            -c "${PROOT_SRC}/src/loader/assembly.S" -o ${NAME}_asm.o
    COMMAND "${_LOADER_CC}" ${EXTRA_FLAGS} -static -nostdlib
            -Wl,--build-id=none,-Ttext=${EXTRA_ADDR},-z,noexecstack
            -o ${NAME}.dbg ${NAME}.o ${NAME}_asm.o
    COMMAND "${CMAKE_STRIP}" -o ${NAME}.elf ${NAME}.dbg
    COMMAND "${CMAKE_OBJCOPY}" --input-target binary
            --output-target "${OBJCOPY_FMT}"
            --binary-architecture "${OBJCOPY_ARCH}"
            ${NAME}.elf ${NAME}-wrapped.o
    DEPENDS "${PROOT_SRC}/src/loader/loader.c"
            "${PROOT_SRC}/src/loader/assembly.S"
    COMMENT "proot loader [${NAME}] -> ${wrap}"
    VERBATIM)
  set(LOADER_WRAPPED_${NAME} "${wrap}" PARENT_SCOPE)
endfunction()

build_loader(loader "${LOADER_ADDR}" "")
set(LOADER_OBJS "${LOADER_WRAPPED_loader}")
if(LOADER_M32)
  # x86_64 上 proot 还要一个 32 位 loader（arch.h: HAS_LOADER_32BIT）
  build_loader(loader-m32 "0xa0000000" "-m32")
  list(APPEND LOADER_OBJS "${LOADER_WRAPPED_loader-m32}")
endif()

# 包裹成静态库，供 add_executable 链接（EXTERNAL_OBJECT 告诉 CMake 这是裸 .o）
add_library(loader_embed STATIC ${LOADER_OBJS})
set_source_files_properties(${LOADER_OBJS} PROPERTIES GENERATED TRUE EXTERNAL_OBJECT TRUE)
# 该库只含预编译的裸 .o，CMake 推断不出语言，显式指定 C（用 llvm-ar 打包）
set_target_properties(loader_embed PROPERTIES LINKER_LANGUAGE C)

# ── 3) proot 主体源文件 ──────────────────────────────────────────────────────
set(_PROOT_REL_SRCS
  cli/cli.c cli/proot.c cli/note.c
  execve/enter.c execve/exit.c execve/shebang.c execve/elf.c execve/ldso.c execve/auxv.c execve/aoxp.c
  path/binding.c path/glue.c path/canon.c path/path.c path/proc.c path/temp.c
  syscall/seccomp.c syscall/syscall.c syscall/chain.c syscall/enter.c syscall/exit.c syscall/sysnum.c syscall/socket.c syscall/heap.c syscall/rlimit.c
  tracee/tracee.c tracee/mem.c tracee/reg.c tracee/event.c
  ptrace/ptrace.c ptrace/user.c ptrace/wait.c
  extension/extension.c extension/kompat/kompat.c
  extension/fake_id0/fake_id0.c
  extension/link2symlink/link2symlink.c
  extension/portmap/portmap.c extension/portmap/map.c)
set(PROOT_SRCS "")
foreach(f ${_PROOT_REL_SRCS})
  if(EXISTS "${PROOT_SRC}/src/${f}")
    list(APPEND PROOT_SRCS "${PROOT_SRC}/src/${f}")
  else()
    message(WARNING "缺少 proot 源文件（跳过）: ${f}")
  endif()
endforeach()

if(NOT PROOT_SRCS)
  message(FATAL_ERROR "没有任何 proot 源文件被收集到，检查 PROOT_SRC")
endif()

# ── 4) 链接出 libproot.so（PIE 可执行文件，命名 .so）────────────────────────
add_executable(proot ${PROOT_SRCS})
set_target_properties(proot PROPERTIES
  OUTPUT_NAME libproot
  SUFFIX      .so
  POSITION_INDEPENDENT_CODE ON)

target_include_directories(proot PRIVATE
  "${TALLOC_SRC}"
  "${PROOT_SRC}/src"
  "${PROOT_SRC}/src/cli"
  "${PROOT_SRC}/lib/uthash/include")

# Android/Ubuntu 魔改（参考 termux proot 适配）
target_compile_definitions(proot PRIVATE
  _GNU_SOURCE
  _FILE_OFFSET_BITS=64
  ARG_MAX=131072)        # termux 为 bionic 补的 ARG_MAX（避免某些 glibc 路径假设）

target_compile_options(proot PRIVATE
  -O2 -fPIC
  -Wno-implicit-function-declaration
  -Wno-deprecated-declarations
  -Wno-inline-asm
  -Wno-unused-command-line-argument
  -Wno-unused-function)

target_link_libraries(proot PRIVATE talloc loader_embed)
target_link_options(proot PRIVATE -Wl,-z,noexecstack)

# 不做 install()，产物路径由 build-proot.sh 用 $<TARGET_FILE:proot> 取
