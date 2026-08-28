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
BUILD_DIR="$ROOT/build/native"
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

if [[ ! -f "$TALLOC_SRC/libtalloc.a" ]]; then
  echo ">>> 编译 libtalloc ..."
  ( cd "$TALLOC_SRC" \
    && "$CC" $CFLAGS -c talloc.c -o talloc.o -I. \
    && "$AR" rcs libtalloc.a talloc.o )
fi
( cd "$TALLOC_SRC" \
  && "$CC" $CFLAGS -shared -fPIC -o libtalloc.so.2 talloc.c -I. \
  && cp -f libtalloc.so.2 "$OUT_DIR/libtalloc.so.2" ) 
echo "✅ libtalloc.so.2 → $OUT_DIR/libtalloc.so.2"

# ── 4. 交叉编译 proot ──
PROOT_SRC="$BUILD_DIR/proot"
if [[ ! -d "$PROOT_SRC/.git" ]]; then
  echo ">>> 克隆 proot 源码 ..."
  git clone --depth 1 https://github.com/proot-me/proot.git "$PROOT_SRC"
fi

echo ">>> 编译 proot（ABI=$ABI, API=$API）..."
( cd "$PROOT_SRC/src" \
  && make clean >/dev/null 2>&1 || true \
  && make \
      CC="$CC" \
      CFLAGS="$CFLAGS -I$TALLOC_SRC" \
      LDFLAGS="$LDFLAGS -L$TALLOC_SRC -ltalloc" \
      -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" )

if [[ ! -x "$PROOT_SRC/src/proot" ]]; then
  echo "❌ proot 编译未产出可执行文件。常见原因："
  echo "   1) 缺 libtalloc 头/库（检查 $TALLOC_SRC）"
  echo "   2) proot 上游 Makefile 变动（本脚本按 proot-me/proot 的 src/Makefile 编写）"
  echo "   3) NDK 版本差异导致编译错误——请贴出上面的完整报错"
  exit 1
fi

# proot 作为可执行 .so 运行（Android 上从 APK lib 目录才有执行权限），故命名为 libproot.so
cp -f "$PROOT_SRC/src/proot" "$OUT_DIR/libproot.so"
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
