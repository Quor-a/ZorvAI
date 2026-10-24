#!/system/bin/sh
# 构建墨语言自举的系统 → rootfs + ISO
# 在 ZorvAI 墨语言页点「构建系统镜像」时由 App 调用；也可手工在 proot 内跑。
#
#   用法: build.sh <运行时目录> <输出目录>
#
# 流水线：
#   1) kernel64.mo --mo2x(kern-aarch64)--> C --zig(aarch64-freestanding)--> kernel64.elf
#   2) uspace2.mo  --mo2x(cfs-aarch64)--> C --zig(aarch64-linux-musl,static)--> uspace2
#   3) 组装 rootfs（/boot、/bin、/etc、/mo）
#   4) 打包 ISO9660
set -e
RT="$1"; OUT="$2"
SRC="$(dirname "$0")/src"
[ -d "$RT/mo/bin" ] || { echo "缺少墨语言运行时: $RT"; exit 2; }
mkdir -p "$OUT/rootfs"/{boot,bin,etc,mo}

BOX64="${BOX64:-$RT/box64}"          # aarch64 宿主上的 x86_64 模拟器
ZIG="$RT/zig"                        # AArch64 静态 zig
MO2X="$RT/mo/bin/mo2x"
[ -x "$BOX64" ] || { echo "缺少 box64: $BOX64"; exit 3; }
[ -x "$ZIG" ] || { echo "缺少 zig: $ZIG"; exit 4; }

echo "[1/5] 编译内核 (kern-aarch64)"
W="$OUT/work"; mkdir -p "$W"
"$BOX64" "$MO2X" "$SRC/kernel64.mo" kern-aarch64 > "$W/kernel.c" || { echo "mo2x 内核转译失败"; exit 5; }
"$ZIG" cc -target aarch64-freestanding -fno-pic -fno-stack-protector \
  -O2 -ffreestanding -std=c11 -mgeneral-regs-only \
  -c "$W/kernel.c" -o "$W/kernel.o"
"$ZIG" cc -target aarch64-freestanding -c "$SRC/boot/entry64.S" -o "$W/entry.o"
"$ZIG" cc -target aarch64-freestanding \
  -Wl,--script="$SRC/boot/linker64.ld" -nostdlib -Wl,--build-id=none \
  "$W/entry.o" "$W/kernel.o" -o "$OUT/rootfs/boot/kernel64.elf"
echo "      -> $OUT/rootfs/boot/kernel64.elf ($(wc -c < "$OUT/rootfs/boot/kernel64.elf") 字节)"

echo "[2/5] 编译用户空间 (cfs-aarch64)"
"$BOX64" "$MO2X" "$SRC/uspace2.mo" cfs-aarch64 > "$W/uspace2.c" || { echo "mo2x 用户空间转译失败"; exit 6; }
"$ZIG" cc -target aarch64-linux-musl -static -nostdlib -fno-builtin -O2 \
  -o "$OUT/rootfs/bin/uspace2" "$W/uspace2.c"
chmod 0755 "$OUT/rootfs/bin/uspace2"
echo "      -> $OUT/rootfs/bin/uspace2 ($(wc -c < "$OUT/rootfs/bin/uspace2") 字节)"

echo "[3/5] 组装 rootfs"
cat > "$OUT/rootfs/etc/uspace.conf" <<EOF
# 墨语言自举的用户空间配置
root=/mo
shell=uspace2
EOF
echo "      rootfs: $OUT/rootfs"

echo "[4/5] 打包 ISO9660"
if command -v mkisofs >/dev/null 2>&1; then
  mkisofs -R -J -V MOLANG -o "$OUT/molang-system.iso" "$OUT/rootfs"
elif command -v xorriso >/dev/null 2>&1; then
  xorriso -as mkisofs -R -J -V MOLANG -o "$OUT/molang-system.iso" "$OUT/rootfs"
else
  echo "      (无 mkisofs/xorriso，跳过 ISO；rootfs 仍可用)"
fi

echo "[5/5] 完成"
ls -l "$OUT/rootfs/boot/kernel64.elf" "$OUT/rootfs/bin/uspace2"
