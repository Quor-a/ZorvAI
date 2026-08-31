#!/usr/bin/env bash
#
# 用 Android NDK 从源码交叉编译 proot（含内置 loader）
#
# 产物：<out>/libproot.so  —— 可直接执行的 PIE（ET_DYN），与现有
#       app/src/full/jniLibs/arm64-v8a/libproot.so 同机制：命名为 .so 是为了绕过
#       Android 10+ (targetSdk>=28) 禁止 exec 应用数据目录内二进制的限制。
#       libtalloc 静态链入，运行时只依赖 Android 的 libc.so / libdl.so。
#
# 为什么需要：assets/linux_env/ 与 jniLibs/ 里的 proot 是第三方预编译二进制，
# 无法随项目演进、无法审计、无法换 ABI。本脚本改为从 proot-me/proot 源码自持编译。
#
# 用法：
#   tools/native/build-proot.sh [--abi arm64-v8a] [--api 26] [--ndk <path>]
#                               [--out <dir>] [--install]
#
#   --abi     arm64-v8a(默认) | armeabi-v7a | x86_64 | x86
#   --api     Android API 级别，默认 26
#   --ndk     NDK 路径；缺省依次读 ANDROID_NDK_HOME / local.properties / $SDK/ndk/*
#   --out     产物目录，默认 build/native/out/<abi>
#   --install 额外把产物安装到 app/src/full/jniLibs/<abi>/libproot.so 与
#             app/src/main/assets/linux_env/proot（会覆盖现有预编译二进制）
#
# 前置：git、curl、tar，以及 Android NDK r25+（项目 ndkVersion = 27.0.12077973）
# 首次执行会从 samba.org / github.com 拉源码到 build/native/，之后增量复用。
#
# 已知坑（曾导致 2026-08-29 构建失败）：
#   proot 的 execve/enter.c 引用 _binary_loader_elf_start/_end —— 那是用
#   objcopy --input-target binary 把「loader 裸 ELF」嵌进去的符号。只编译
#   src/*.c 而不先编译并嵌入 loader，链接必然报 undefined symbol。
#   本脚本 step 4 专门处理这一步。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# 路径含空格时（D:\Calw OS-project\...）clang 的路径拼接偶发异常，用无空格构建目录
if [[ "$ROOT" =~ [[:space:]] ]]; then
  BUILD_DIR="D:/CalwOS-build/native"
else
  BUILD_DIR="$ROOT/build/native"
fi
mkdir -p "$BUILD_DIR"

ABI="arm64-v8a"
API="26"
NDK=""
OUT_DIR=""
DO_INSTALL=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi) ABI="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --ndk) NDK="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --install) DO_INSTALL=1; shift ;;
    -h|--help) sed -n '2,38p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

[[ -z "$OUT_DIR" ]] && OUT_DIR="$BUILD_DIR/out/$ABI"
mkdir -p "$OUT_DIR"

# ── 1. 定位 NDK ────────────────────────────────────────────────────────────
if [[ -z "$NDK" ]]; then
  NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
fi
if [[ -z "$NDK" && -f "$ROOT/local.properties" ]]; then
  NDK="$(sed -n 's/^ndk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | tail -1)"
fi
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  [[ -z "$SDK" && -f "$ROOT/local.properties" ]] && \
    SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | sed 's/\\//g' | tail -1)"
  # 优先用 app/build.gradle.kts 里 ndkVersion 指定的那一版，保证与 Gradle 构建同源
  WANT_NDK="$(sed -n 's/.*ndkVersion *= *"\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" 2>/dev/null | tail -1)"
  if [[ -n "$WANT_NDK" && -d "$SDK/ndk/$WANT_NDK" ]]; then
    NDK="$SDK/ndk/$WANT_NDK"
  else
    NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "❌ 找不到 Android NDK。请任选其一："
  echo "   export ANDROID_NDK_HOME=/path/to/ndk"
  echo "   在 local.properties 写 ndk.dir=/path/to/ndk"
  echo "   $0 --ndk /path/to/ndk"
  exit 1
fi
echo "NDK: $NDK"

# ── 2. 定位工具链 ──────────────────────────────────────────────────────────
case "$(uname -s)" in
  Darwin)                  HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*)    HOST_TAG="windows-x86_64" ;;
  *)                       HOST_TAG="linux-x86_64" ;;
esac

# ABI → (clang 前缀, loader 链接地址, 是否要 32 位 loader, llvm 目标格式/架构, 期望 ELF machine)
case "$ABI" in
  arm64-v8a)
    CLANG_BASE="aarch64-linux-android"
    LOADER_ADDR="0x2000000000"          # src/arch.h: ARCH_ARM64
    LOADER_M32=0
    OBJCOPY_FMT="elf64-littleaarch64"; OBJCOPY_ARCH="aarch64"
    WANT_MACHINE="b7"                   # EM_AARCH64
    WANT_CLASS="2"
    ;;
  armeabi-v7a)
    CLANG_BASE="armv7a-linux-androideabi"
    LOADER_ADDR="0x10000000"            # src/arch.h: ARCH_ARM_EABI
    LOADER_M32=0
    OBJCOPY_FMT="elf32-littlearm"; OBJCOPY_ARCH="arm"
    WANT_MACHINE="28"                   # EM_ARM
    WANT_CLASS="1"
    ;;
  x86_64)
    CLANG_BASE="x86_64-linux-android"
    LOADER_ADDR="0x600000000000"        # src/arch.h: ARCH_X86_64
    LOADER_M32=1                        # HAS_LOADER_32BIT
    OBJCOPY_FMT="elf64-x86-64"; OBJCOPY_ARCH="x86-64"
    WANT_MACHINE="3e"                   # EM_X86_64
    WANT_CLASS="2"
    ;;
  x86)
    CLANG_BASE="i686-linux-android"
    LOADER_ADDR="0xa0000000"            # src/arch.h: ARCH_X86
    LOADER_M32=0
    OBJCOPY_FMT="elf32-i386"; OBJCOPY_ARCH="i386"
    WANT_MACHINE="03"                   # EM_386
    WANT_CLASS="1"
    ;;
  *) echo "❌ 不支持的 ABI: $ABI"; exit 1 ;;
esac

BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$BIN/${CLANG_BASE}${API}-clang"
[[ "$HOST_TAG" == "windows-x86_64" ]] && CC="$CC.cmd"
for tool in "$CC" "$BIN/llvm-ar" "$BIN/llvm-strip" "$BIN/llvm-objcopy" "$BIN/llvm-readelf"; do
  [[ -e "$tool" ]] || { echo "❌ 缺少工具: $tool（NDK 版本与 HOST_TAG=$HOST_TAG 是否匹配？）"; exit 1; }
done

# clang 在 Windows 下要跑 .cmd 包装器；llvm-* 直接是可执行文件
AR="$BIN/llvm-ar"
STRIP="$BIN/llvm-strip"
OBJCOPY="$BIN/llvm-objcopy"
READELF="$BIN/llvm-readelf"
OBJDUMP="$BIN/llvm-objdump"
echo "CC: $CC"

PROOT_CFLAGS_BASE=(-O2 -fPIC -D_GNU_SOURCE -Wno-unused-command-line-argument)
PROOT_LDFLAGS_BASE=(-pie)

# ── 3. 交叉编译 libtalloc（proot 硬依赖，静态链入）────────────────────────
TALLOC_VER="2.4.0"
TALLOC_SRC="$BUILD_DIR/talloc-$TALLOC_VER"
if [[ ! -d "$TALLOC_SRC" ]]; then
  echo ">>> 下载 talloc $TALLOC_VER ..."
  ( cd "$BUILD_DIR" && curl -fsSL -o "talloc-$TALLOC_VER.tar.gz" \
      "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VER.tar.gz" )
  ( cd "$BUILD_DIR" && tar xzf "talloc-$TALLOC_VER.tar.gz" )
fi

# talloc 的 config.h 原本由 configure 生成；Android 交叉编译手工给一份最小集
if [[ ! -f "$TALLOC_SRC/config.h" ]]; then
  cat > "$TALLOC_SRC/config.h" << 'TALLOC_CFG'
#ifndef _TALLOC_CONFIG_H
#define _TALLOC_CONFIG_H
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 0
#define TALLOC_BUILD_EXPAND(x) #x
#define TALLOC_BUILD_VERSION TALLOC_BUILD_EXPAND(TALLOC_BUILD_VERSION_MAJOR.TALLOC_BUILD_VERSION_MINOR.TALLOC_BUILD_VERSION_RELEASE)
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_ERRNO_DECL 1
#define HAVE_STRERROR 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1
#define HAVE_BZERO 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_STRTOK_R 1
#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_DPRINTF 1
#define HAVE_VDPRINTF 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_CHOWN 1
#define HAVE_LINK 1
#define HAVE_READLINK 1
#define HAVE_SYMLINK 1
#define HAVE_REALPATH 1
#define HAVE_LCHOWN 1
#define HAVE_DUP2 1
#define HAVE_PREAD 1
#define HAVE_PWRITE 1
#define HAVE_FTRUNCATE 1
#define HAVE_INITGROUPS 1
#define HAVE_DECL_EWOULDBLOCK 1
#define HAVE_DECL_ENVIRON 1
#define HAVE_BOOL 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_FUNCTION_MACRO 1
#define HAVE_POLL 1
#define HAVE_FDATASYNC 1
#define HAVE_VOLATILE 1
#define HAVE_DLERROR 1
#define HAVE_DLOPEN 1
#define HAVE_DLSYM 1
#define HAVE_DLCLOSE 1
#define HAVE_DLFCN_H 1
#define HAVE_LIMITS_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_SECURE_MKSTEMP 1
#define HAVE_MKDTEMP 1
#define HAVE_SYSLOG 1
#define HAVE_USLEEP 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_FALLTHROUGH_ATTRIBUTE 1
#define HAVE_VISIBILITY_ATTR 1
#define HAVE___THREAD 1
#define HAVE_PTHREAD 1
#define STDC_HEADERS 1
#define HAVE_LINUX_TYPES_H 1
#define HAVE_MALLOC_H 1
#undef HAVE_STANDARDS_H
#undef HAVE_MEMMEM
#undef HAVE_STRLCPY
#undef HAVE_STRLCAT
#undef HAVE_GETPEEREID
#undef HAVE_SETPROCTITLE
#undef HAVE_SETPROCTITLE_INIT
#undef HAVE_SETPROCTITLE_H
#undef HAVE_MEMSET_S
#undef HAVE_GETPROGNAME
#undef HAVE_COPY_FILE_RANGE
#undef HAVE_WORKING_STRPTIME
#undef HAVE_BSD_STRING_H
#undef HAVE_BSD_UNISTD_H
#undef HAVE_BSD_STRTOLL
#undef HAVE_GET_CURRENT_DIR_NAME
#endif
TALLOC_CFG
fi

# 按 ABI 分开命名：否则换 ABI 重跑会复用上一次架构的 .a，链接报 incompatible with elf_*
TALLOC_A="$TALLOC_SRC/libtalloc-$ABI.a"
if [[ ! -f "$TALLOC_A" ]]; then
  echo ">>> 编译 libtalloc ($ABI) ..."
  ( cd "$TALLOC_SRC" \
    && "$CC" "${PROOT_CFLAGS_BASE[@]}" -c talloc.c -o "talloc-$ABI.o" -I. -Ilib/replace -D__STDC_WANT_LIB_EXT1__=1 \
    && "$AR" rcs "$TALLOC_A" "talloc-$ABI.o" )
fi
echo "✅ libtalloc ($ABI) → $TALLOC_A"

# ── 4. 拉取 proot 源码 ─────────────────────────────────────────────────────
PROOT_SRC="$BUILD_DIR/proot"
if [[ ! -d "$PROOT_SRC/.git" ]]; then
  echo ">>> 克隆 proot 源码 ..."
  rm -rf "$PROOT_SRC"
  git clone --depth 1 https://github.com/proot-me/proot.git "$PROOT_SRC"
fi
PROOT_COMMIT="$(git -C "$PROOT_SRC" rev-parse --short HEAD)"
echo "proot 源码: $PROOT_COMMIT"

# build.h 原本由 configure 生成
cat > "$PROOT_SRC/src/build.h" << 'BUILD_H'
#ifndef BUILD_H
#define BUILD_H
#undef VERSION
#define VERSION "5.0.0-proot"
#undef HAVE_PYTHON_EXTENSION
#define HAVE_SECCOMP_FILTER
#endif /* BUILD_H */
BUILD_H

# ── 5~7. ★ 用 Android 官方 NDK + CMake 交叉编译 proot（含 loader 嵌入）────
# 不再手工调 clang；改用 NDK 自带的 CMake toolchain 驱动 tools/native/proot/CMakeLists.txt。
# CMakeLists 内部完成：loader 编译+链接裸 ELF+objcopy 嵌入、libtalloc 静态链入、
# proot 主体 PIE 链接，产出 libproot.so（命名 .so 仅为绕过 exec 限制）。
# 对应原「最容易漏的 loader 嵌入」一步，这里由 add_custom_command + llvm-objcopy 严谨复刻。
echo ">>> 用 NDK+CMake 交叉编译 proot (ABI=$ABI, API=$API) ..."

# 定位 cmake：优先 SDK 自带（NDK 不自带 cmake 二进制，但 Android SDK 有），其次 PATH
# 定位 cmake：NDK r27 的 toolchain 需要 cmake ≥ 3.19 才能正确探测编译器，优先 3.22.1
CMAKE_BIN=""
for cand in "$SDK/cmake/3.22.1/bin/cmake.exe" "$SDK/cmake/3.22.0/bin/cmake.exe" "$SDK/cmake/3.21.4/bin/cmake.exe"; do
  if [[ -f "$cand" ]]; then CMAKE_BIN="$cand"; break; fi
done
if [[ -z "$CMAKE_BIN" ]]; then
  shopt -s nullglob
  cands=("$SDK"/cmake/*/bin/cmake.exe)
  shopt -u nullglob
  [[ ${#cands[@]} -gt 0 ]] && CMAKE_BIN="$(printf '%s\n' "${cands[@]}" | sort -V | tail -1)"
fi
[[ -z "$CMAKE_BIN" ]] && CMAKE_BIN="$(command -v cmake 2>/dev/null || true)"
[[ -z "$CMAKE_BIN" ]] && { echo "❌ 找不到 cmake（需 ≥3.19，请确认 SDK/cmake 存在）"; exit 1; }
echo "cmake: $CMAKE_BIN"

# 生成器：Windows 下默认 NMake Makefiles 会因缺 nmake 失败；改用 NDK 自带的 Unix make
CMAKE_GEN="Unix Makefiles"
NDK_MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"
[[ -f "$NDK_MAKE" ]] || { echo "❌ 找不到 NDK 自带 make: $NDK_MAKE"; exit 1; }
# 把 NDK 的 make 所在目录放进 PATH，供 cmake --build 子调用找到 make
export PATH="$(dirname "$NDK_MAKE"):$PATH"

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[[ -f "$TOOLCHAIN" ]] || { echo "❌ 找不到 NDK CMake toolchain: $TOOLCHAIN"; exit 1; }

CMAKE_BUILD="$BUILD_DIR/cmake_$ABI"
rm -rf "$CMAKE_BUILD"
# cmake.exe 是原生 Windows 程序，不认 Git Bash 的 /d/... MSYS 路径，先转成 Windows 混合路径
CMAKE_SRC="$(cygpath -w -m "$ROOT/tools/native/proot" 2>/dev/null || echo "$ROOT/tools/native/proot")"
"$CMAKE_BIN" -S "$CMAKE_SRC" -B "$CMAKE_BUILD" \
  -G "$CMAKE_GEN" \
  -DCMAKE_MAKE_PROGRAM="$NDK_MAKE" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API" \
  -DPROOT_SRC="$PROOT_SRC" \
  -DTALLOC_SRC="$TALLOC_SRC" \
  -DCMAKE_BUILD_TYPE=Release \
  || { echo "❌ cmake 配置失败"; exit 1; }

"$CMAKE_BIN" --build "$CMAKE_BUILD" --target proot -j"$(nproc 2>/dev/null || echo 4)" \
  || { echo "❌ cmake 构建失败（详见上方编译错误）"; exit 1; }

# 产物：未 strip 的 libproot.so（保留 _binary_loader_elf_* 符号供校验）
BUILT="$CMAKE_BUILD/libproot.so"
[[ -f "$BUILT" ]] || { echo "❌ 未产出 $BUILT"; exit 1; }
# 保留未 strip 版本用于符号校验
cp -f "$BUILT" "$OUT_DIR/libproot.so.unstripped"
# strip 出最终交付二进制（运行时不需要符号，减小体积）
"$STRIP" -o "$OUT_DIR/libproot.so" "$BUILT" 2>/dev/null \
  || cp -f "$BUILT" "$OUT_DIR/libproot.so"
chmod +x "$OUT_DIR/libproot.so"
echo "✅ libproot.so 构建完成（CMake 驱动）"

# 外部 loader（PROOT_LOADER 指向；proot 运行时 execve 的就是它，必须与新 proot 同源重建）
# loader.elf 由 CMake 的 build_loader() 生成（与内嵌 loader 同源、同 NDK、同 proot commit 编译），
# 类型为 ET_EXEC 固定地址（与既有 libproot-loader.so 一致），可直接作外部 loader 使用。
# 漏装它会导致 PROOT_LOADER 仍指向旧 loader，与新 proot 版本漂移 → guest execve 报 Bad address。
LOADER_ELF="$CMAKE_BUILD/obj/loader.elf"
if [[ -f "$LOADER_ELF" ]]; then
  cp -f "$LOADER_ELF" "$OUT_DIR/libproot-loader.so"
  chmod +x "$OUT_DIR/libproot-loader.so"
  echo "✅ libproot-loader.so 提取完成（外部 loader，与 proot 同源）"
else
  echo "❌ 未找到 $LOADER_ELF（loader 构建步骤未产出）"; exit 1
fi

# ── 8. 校验（不靠肉眼，靠 ELF 头）─────────────────────────────────────────
echo ""
echo "=== 产物校验 ==="
BIN_FILE="$OUT_DIR/libproot.so"
ELFHDR="$("$READELF" -h "$BIN_FILE")"
E_CLASS_STR="$(echo "$ELFHDR" | sed -n 's/^  Class: *//p' | tr -d ' ')"
E_MACH_STR="$(echo "$ELFHDR" | sed -n 's/^  Machine: *//p' | tr -d ' ')"
echo "ELF Class   = $E_CLASS_STR"
echo "ELF Machine = $E_MACH_STR"

case "$ABI" in
  arm64-v8a)   WANT_CLASS_STR="ELF64";    WANT_MACH_STR="AArch64" ;;
  armeabi-v7a) WANT_CLASS_STR="ELF32";    WANT_MACH_STR="ARM" ;;
  x86_64)      WANT_CLASS_STR="ELF64";    WANT_MACH_STR="AdvancedMicroDevicesX86-64" ;;
  x86)         WANT_CLASS_STR="ELF32";    WANT_MACH_STR="Intel80386" ;;
esac
if [[ "$E_CLASS_STR" != "$WANT_CLASS_STR" || "$E_MACH_STR" != "$WANT_MACH_STR" ]]; then
  echo "❌ 架构不符：期望 $WANT_CLASS_STR / $WANT_MACH_STR，实际 $E_CLASS_STR / $E_MACH_STR"
  exit 1
fi
echo "✅ 架构匹配 $ABI"

echo "--- 动态依赖 ---"
"$READELF" -d "$BIN_FILE" | grep -E "NEEDED|SONAME" || true
NEEDS="$("$READELF" -d "$BIN_FILE" | grep -c NEEDED || true)"
BAD_DEP="$("$READELF" -d "$BIN_FILE" | grep -c "libtalloc" || true)"
if [[ "$BAD_DEP" != "0" ]]; then
  echo "⚠ 产物仍动态依赖 libtalloc —— 需要把 libtalloc.so.2 一起塞进 rootfs"
fi

echo "--- loader 已嵌入 ---"
# strip 会摘掉符号表条目，所以看未 strip 的那份
UNSTRIPPED="$OUT_DIR/libproot.so.unstripped"
if [[ -f "$UNSTRIPPED" ]] && "$OBJDUMP" -t "$UNSTRIPPED" 2>/dev/null | grep -q "_binary_loader_elf_start"; then
  START_N="$("$OBJDUMP" -t "$UNSTRIPPED" | awk '/_binary_loader_elf_start/{print "0x" $1; exit}')"
  END_N="$("$OBJDUMP" -t "$UNSTRIPPED" | awk '/_binary_loader_elf_end/{print "0x" $1; exit}')"
  echo "✅ _binary_loader_elf_start..end = $START_N .. $END_N " \
       "(约 $(( END_N - START_N )) 字节)"
  [[ $(( END_N - START_N )) -gt 1000 ]] || { echo "❌ loader 尺寸异常小"; exit 1; }
else
  echo "❌ loader 符号缺失（objify 步骤没生效）"; exit 1
fi

ls -lh "$BIN_FILE"
echo "proot 源码版本: $PROOT_COMMIT"

# ── 9. 可选安装进工程 ─────────────────────────────────────────────────────
if [[ "$DO_INSTALL" == "1" ]]; then
  echo ""
  echo ">>> 安装到工程（覆盖现有预编译二进制）"
  mkdir -p "$ROOT/app/src/full/jniLibs/$ABI"
  cp -f "$BIN_FILE" "$ROOT/app/src/full/jniLibs/$ABI/libproot.so"
  echo "  → app/src/full/jniLibs/$ABI/libproot.so"
  # 外部 loader 同步安装（与 proot 同源重建，PROOT_LOADER 指向它）
  LOADER_BIN="$OUT_DIR/libproot-loader.so"
  if [[ -f "$LOADER_BIN" ]]; then
    cp -f "$LOADER_BIN" "$ROOT/app/src/full/jniLibs/$ABI/libproot-loader.so"
    echo "  → app/src/full/jniLibs/$ABI/libproot-loader.so"
  else
    echo "❌ 缺少 $LOADER_BIN，loader 未生成"; exit 1
  fi
  if [[ "$ABI" == "arm64-v8a" ]]; then
    cp -f "$BIN_FILE" "$ROOT/app/src/main/assets/linux_env/proot"
    echo "  → app/src/main/assets/linux_env/proot"
    if [[ -f "$LOADER_BIN" ]]; then
      cp -f "$LOADER_BIN" "$ROOT/app/src/main/assets/linux_env/libproot-loader.so"
      echo "  → app/src/main/assets/linux_env/libproot-loader.so"
    fi
  fi
  echo "⚠ 已覆盖运行时二进制，务必真机验证终端；回退：git checkout -- app/src/full/jniLibs app/src/main/assets/linux_env"
else
  echo ""
  echo "产物未安装（安全模式）。要替换工程内运行时二进制请加 --install："
  echo "  $0 --abi $ABI --install"
fi

echo ""
echo "完成：$OUT_DIR/libproot.so"
