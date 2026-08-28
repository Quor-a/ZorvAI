#!/usr/bin/env bash
#
# 用 Android NDK 交叉编译终端原生件：libtalloc + proot
#
# 产物：libproot.so（可直接执行的 PIE，与现有 assets/linux_env/proot 同机制），
#       以及 libtalloc.so.2（proot 运行时依赖）。
#
# 为什么需要：目前 assets/linux_env/ 里的 proot / libproot-loader.so 是**预编译二进制**，
# 无法随项目演进、也无法审计；本脚本改为从源码交叉编译，产物自持。
#
# 用法：
#   tools/native/build-proot.sh [--abi arm64-v8a] [--api 26] [--ndk <path>]
#
# 前置：git、make、Android NDK（r25+，项目 ndkVersion = 27.0.12077973）
#
# ⚠ 本脚本需在有网络的机器上执行（会从GitHub/ samba.org 取源码）。
#   首次执行会克隆到 build/native/ 下，之后增量复用。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# 如果路径含空格，构建目录用无空格替代路径
if [[ "$ROOT" =~ [[:space:]] ]]; then
  BUILD_DIR="D:/CalwOS-build/native"
  mkdir -p "$BUILD_DIR"
else
  BUILD_DIR="$ROOT/build/native"
fi
OUT_DIR="$ROOT/app/src/main/assets/linux_env"

ABI="arm64-v8a"
API="26"
NDK=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi) ABI="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --ndk) NDK="$2"; shift 2 ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

# ── 1. 定位 NDK ──
if [[ -z "$NDK" ]]; then
  NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
fi
if [[ -z "$NDK" && -f "$ROOT/local.properties" ]]; then
  NDK="$(sed -n 's/^ndk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | tail -1)"
fi
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "❌ 找不到 Android NDK。请任选其一："
  echo "   export ANDROID_NDK_HOME=/path/to/ndk"
  echo "   在 local.properties 写 ndk.dir=/path/to/ndk"
  echo "   ./tools/native/build-proot.sh --ndk /path/to/ndk"
  exit 1
fi
echo "NDK: $NDK"

# ── 2. 定位工具链（Windows / macOS / Linux 路径不同）──
case "$(uname -s)" in
  Darwin)          HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *)               HOST_TAG="linux-x86_64" ;;
esac

case "$ABI" in
  arm64-v8a)  CLANG_BASE="aarch64-linux-android" ;;
  armeabi-v7a) CLANG_BASE="armv7a-linux-androideabi" ;;
  x86_64)     CLANG_BASE="x86_64-linux-android" ;;
  x86)        CLANG_BASE="i686-linux-android" ;;
  *) echo "❌ 不支持的 ABI: $ABI"; exit 1 ;;
esac

BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$BIN/${CLANG_BASE}${API}-clang"
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then CC="$CC.cmd"; fi
if [[ ! -f "$CC" ]]; then
  echo "❌ 找不到 NDK 编译器: $CC"
  echo "   （确认 NDK 版本与 HOST_TAG=$HOST_TAG 匹配）"
  exit 1
fi
export CC
export AR="$BIN/llvm-ar"
export STRIP="$BIN/llvm-strip"
echo "CC: $CC"

CFLAGS="-O2 -fPIC -D_GNU_SOURCE -Wno-unused-command-line-argument"
LDFLAGS="-pie"

mkdir -p "$BUILD_DIR" "$OUT_DIR"

# ── 3. 交叉编译 libtalloc（proot 的硬依赖）──
# talloc 核心基本就是 talloc.c，直接静态编成 .a 最省事；
# 同时产出 libtalloc.so.2 供 proot 运行时按 SONAME 查找。
TALLOC_VER="2.4.0"
TALLOC_SRC="$BUILD_DIR/talloc-$TALLOC_VER"
if [[ ! -d "$TALLOC_SRC" ]]; then
  echo ">>> 下载 talloc $TALLOC_VER ..."
  ( cd "$BUILD_DIR" && curl -fsSL -o "talloc-$TALLOC_VER.tar.gz" \
      "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VER.tar.gz" )
  ( cd "$BUILD_DIR" && tar xzf "talloc-$TALLOC_VER.tar.gz" )
fi

# 创建 Android 交叉编译所需的 config.h（talloc 构建系统原本由 configure 生成）
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
#define HAVE_BZERO 1
#define HAVE_MEMSET 1
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

if [[ ! -f "$TALLOC_SRC/libtalloc.a" ]]; then
  echo ">>> 编译 libtalloc ..."
  ( cd "$TALLOC_SRC" \
    && "$CC" $CFLAGS -c talloc.c -o talloc.o -I. -Ilib/replace -D__STDC_WANT_LIB_EXT1__=1 \
    && "$AR" rcs libtalloc.a talloc.o )
fi
( cd "$TALLOC_SRC" \
  && "$CC" $CFLAGS -shared -fPIC -o libtalloc.so.2 talloc.c -I. -Ilib/replace -D__STDC_WANT_LIB_EXT1__=1 \
  && cp -f libtalloc.so.2 "$OUT_DIR/libtalloc.so.2" \
  && cp -f libtalloc.a "$OUT_DIR/libtalloc.a" )
echo "✅ libtalloc.so.2 → $OUT_DIR/libtalloc.so.2"

# ── 4. 交叉编译 proot ──
PROOT_SRC="$BUILD_DIR/proot"
if [[ ! -d "$PROOT_SRC/.git" ]]; then
  echo ">>> 克隆 proot 源码 ..."
  git clone --depth 1 https://github.com/proot-me/proot.git "$PROOT_SRC"
fi

# 生成 proot 需要的 build.h（检测平台特性）
if [[ ! -f "$PROOT_SRC/src/build.h" ]]; then
  cat > "$PROOT_SRC/src/build.h" << 'BUILD_H'
#ifndef BUILD_H
#define BUILD_H
#undef VERSION
#define VERSION "5.0.0-proot"
#undef HAVE_PYTHON_EXTENSION
#define HAVE_SECCOMP_FILTER
#endif /* BUILD_H */
BUILD_H
fi

# 收集 proot 源文件（排除 care、python extension 和 loader）
PROOT_SRCS=()
for f in \
    cli/cli.c cli/proot.c cli/note.c \
    execve/enter.c execve/exit.c execve/shebang.c execve/elf.c execve/ldso.c execve/auxv.c execve/aoxp.c \
    path/binding.c path/glue.c path/canon.c path/path.c path/proc.c path/temp.c \
    syscall/seccomp.c syscall/syscall.c syscall/chain.c syscall/enter.c syscall/exit.c syscall/sysnum.c syscall/socket.c syscall/heap.c syscall/rlimit.c \
    tracee/tracee.c tracee/mem.c tracee/reg.c tracee/event.c \
    ptrace/ptrace.c ptrace/user.c ptrace/wait.c \
    extension/extension.c extension/kompat/kompat.c extension/fake_id0/fake_id0.c extension/link2symlink/link2symlink.c extension/portmap/portmap.c extension/portmap/map.c; do
  [[ -f "$PROOT_SRC/src/$f" ]] && PROOT_SRCS+=("$PROOT_SRC/src/$f")
done

echo ">>> 编译 proot（ABI=$ABI, API=$API）..."
PROOT_CFLAGS=($CFLAGS "-I$TALLOC_SRC" "-I$PROOT_SRC/src" "-I$PROOT_SRC/src/cli" "-I$PROOT_SRC/lib/uthash/include" "-Wno-implicit-function-declaration" "-Wno-deprecated-declarations")
PROOT_LDFLAGS=($LDFLAGS "-L$TALLOC_SRC" "-ltalloc")

# 编译每个 .o 然后链接（用目录前缀避免同名冲突）
PROOT_OBJS=()
mkdir -p "$BUILD_DIR/proot_objs"
for src in "${PROOT_SRCS[@]}"; do
  # 用目录名_文件名作为 .o 名避免冲突
  reldir="$(dirname "${src#$PROOT_SRC/src/}")"
  base="$(basename "${src%.c}")"
  if [[ "$reldir" == "." ]]; then
    obj="$BUILD_DIR/proot_objs/$base.o"
  else
    obj="$BUILD_DIR/proot_objs/${reldir//\//_}_$base.o"
  fi
  echo "  CC $(basename "$src")"
  "$CC" "${PROOT_CFLAGS[@]}" -c "$src" -o "$obj" 2>&1 | head -5
  [[ -f "$obj" ]] && PROOT_OBJS+=("$obj")
done

# 链接 proot
echo "  LD proot"
"$CC" -v -o "$BUILD_DIR/proot_bin" "${PROOT_OBJS[@]}" "${PROOT_LDFLAGS[@]}" 2>&1 | tail -20

if [[ ! -x "$BUILD_DIR/proot_bin" ]]; then
  echo "❌ proot 编译失败。尝试直接用 GNUmakefile ..."
  # 备用方案：用 GNUmakefile 但覆盖 CC 和 talloc 路径
  ( cd "$PROOT_SRC/src" \
    && make clean >/dev/null 2>&1 || true \
    && make \
        CC="$CC" \
        CFLAGS="$CFLAGS -I$TALLOC_SRC -I. -I../lib/uthash/include" \
        LDFLAGS="$LDFLAGS -L$TALLOC_SRC -ltalloc" \
        V=1 \
        -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" )
fi

if [[ -x "$BUILD_DIR/proot_bin" ]]; then
  cp -f "$BUILD_DIR/proot_bin" "$OUT_DIR/libproot.so"
elif [[ -x "$PROOT_SRC/src/proot" ]]; then
  cp -f "$PROOT_SRC/src/proot" "$OUT_DIR/libproot.so"
else
  echo "❌ proot 编译未产出可执行文件。"
  echo "   请检查上方编译输出中的错误信息。"
  exit 1
fi
"$STRIP" "$OUT_DIR/libproot.so" 2>/dev/null || true
chmod +x "$OUT_DIR/libproot.so"
echo "✅ libproot.so → $OUT_DIR/libproot.so"

# ── 5. 校验 ──
echo ""
echo "=== 产物校验 ==="
file "$OUT_DIR/libproot.so" 2>/dev/null || true
ls -lh "$OUT_DIR/libproot.so" "$OUT_DIR/libtalloc.so.2"
echo ""
echo "下一步：正常构建 APK（./gradlew :app:assembleFullRelease）即可带上新编译的原生件。"
echo "注意：编译产物会覆盖 assets/linux_env 下的旧预编译二进制，建议先 git 备份。"
