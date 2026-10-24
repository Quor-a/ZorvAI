# kernel64.mo —— 用墨语言写的 **ARM64 裸机内核**（QEMU virt / 真机）
#
#   bash tools/kernel64.sh examples/kernel64.mo /tmp/mokern64.elf
#
# 与 examples/kernel.mo（x86 Multiboot）的关系：同一门语言写的第二个内核，
# 差别全在**硬件接口**上：
#
#   |          | x86 内核              | ARM64 内核                  |
#   |----------|-----------------------|-----------------------------|
#   | 输出     | VGA 文本缓冲 0xB8000  | PL011 UART MMIO 0x09000000  |
#   | 输入     | in/out 端口读键盘     | 同一块 UART 的 MMIO 读      |
#   | I/O 空间 | 有（独立端口地址）    | 无（一切皆 MMIO）           |
#   | 停机     | hlt                   | wfi                         |
#
# 这份内核不调用任何系统调用 —— 它自己就是提供系统调用的那一层。
# 不链接 libc、不需要 Linux、不需要 Android：给一块 ARM64 的裸硬件或
# QEMU virt 就能跑。这才是"独立系统"的字面含义。

# mo-target: kern-aarch64   # 非原生后端，projcheck 跳过原生编译

var TICKS: i64 = 0;
var CHECKS: i64 = 0;

fn fib(n: i64) -> i64 {
    if n < 2 { return n; }
    return fib(n - 1) + fib(n - 2);
}

fn sum_to(n: i64) -> i64 {
    let s: i64 = 0;
    let i: i64 = 1;
    while i <= n {
        s = s + i;
        i = i + 1;
    }
    return s;
}

fn check(label: i64, got: i64, want: i64) -> i64 {
    print("  [");
    print_i64(label);
    print("] got=");
    print_i64(got);
    print(" want=");
    print_i64(want);
    if got == want {
        print("  OK\n");
        return 1;
    }
    print("  FAIL\n");
    return 0;
}

fn main() -> i64 {
    let ok: i64 = 0;

    print("\nMO-KERN64 BOOT\n");
    print("arch=aarch64  console=mmio-uart@0x09000000\n");
    print("no libc / no syscall / no host os\n");

    print("selftest:\n");
    ok = ok + check(1, fib(10), 55);
    ok = ok + check(2, sum_to(100), 5050);
    ok = ok + check(3, fib(20), 6765);

    print("selftest passed: ");
    print_i64(ok);
    print("/3\n");

    TICKS = TICKS + 1;
    CHECKS = ok;
    print("ticks=");
    print_i64(TICKS);
    print(" checks=");
    print_i64(CHECKS);
    print("\n");

    print("MO-KERN64 HALT\n");
    return 0;
}
