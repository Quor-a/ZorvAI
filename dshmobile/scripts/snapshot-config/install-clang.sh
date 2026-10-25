#!/system/bin/sh
# dsh-mobile 0.13.1: clang 按需安装器（apt download-only + dpkg-deb 解包，绕开 dpkg 安装 bug）
# 模板占位 @@PREFIX@@ 由 build-snapshot-013.mjs 构建期替换为设备端前缀（构建期本地路径不可烧入）。
set -e
B="${TERMUX__PREFIX:-@@PREFIX@@}"
export PATH="$B/bin:$PATH"
# 0.13.1 修订：原生库路径必须先于一切外部命令导出（PATH 已指向 Termux bin，
# mkdir 等 GNU coreutils ELF 依赖 libandroid-support.so——先导出后调用）。
export LD_LIBRARY_PATH="$B/lib"
export LD_PRELOAD="$B/lib/libtermux-exec-ld-preload.so"
export HOME="${HOME:-$B/../home}"
export TMPDIR="$HOME/tmp"
mkdir -p "$TMPDIR"
export TERMUX__PREFIX="$B" TERMUX_PREFIX="$B"
export APT_CONFIG="$B/etc/apt/apt.conf"
export OPENSSL_CONF="$B/etc/tls/openssl.cnf"
PKGS="clang binutils ndk-sysroot libllvm lld llvm libcompiler-rt libicu libxml2"
echo "[install-clang] apt-get update…"
apt-get update || echo "[install-clang] 警告：apt update 部分失败，继续用已缓存列表"
echo "[install-clang] 下载依赖（download-only，不进 dpkg 数据库）…"
apt-get install -y --download-only $PKGS
echo "[install-clang] 解包（Termux deb 内嵌完整设备路径，需 usr 层平移）…"
TMPX="$B/../ext-clang-tmp"
mkdir -p "$TMPX"
cd "$B/var/cache/apt/archives"
found=0
for deb in *.deb; do
  case "$deb" in
    clang_*|binutils_*|ndk-sysroot_*|libllvm_*|lld_*|llvm_*|libcompiler-rt_*|libicu_*|libxml2_*) ;;
    *) continue ;;
  esac
  rm -rf "$TMPX/data"
  "$B/bin/dpkg-deb" -x "$deb" "$TMPX"
  cp -a "$TMPX/data/data/com.termux/files/usr/." "$B/"
  found=1
done
rm -rf "$TMPX"
[ "$found" = "1" ] || { echo "[install-clang] 错误：缓存中无目标包（apt 下载失败？）"; exit 1; }
[ -e "$B/bin/gcc" ] || ln -s clang "$B/bin/gcc"
echo "[install-clang] 冒烟验证…"
printf 'int main(void){return 0;}\n' > "$TMPDIR/.clang-smoke.c"
"$B/bin/clang" "$TMPDIR/.clang-smoke.c" -o "$TMPDIR/.clang-smoke"
"$TMPDIR/.clang-smoke"
rm -f "$TMPDIR/.clang-smoke.c" "$TMPDIR/.clang-smoke"
echo "[install-clang] 完成：$("$B/bin/clang" --version | head -1)"
