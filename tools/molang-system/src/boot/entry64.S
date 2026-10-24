/* 墨语言 ARM64 内核入口：建栈 + 清 bss + 跳转 main
 *
 * 与 entry32.S 的差别：
 *   - 没有 Multiboot 头：QEMU virt / U-Boot 直接把 ELF 加载到物理地址执行
 *   - ARM64 没有 I/O 地址空间，设备全是 MMIO，所以这里不需要任何端口操作
 *   - 停机用 wfi 而不是 hlt
 *   - 清 bss 逐字节写：bss 长度不一定按 8 字节对齐，用 str 会越界
 */

.section .text
.global _start
_start:
    /* 建立 64KB 栈 */
    ldr x0, =stack_top
    mov sp, x0

    /* 清 .bss：不清的话全局变量初值不是 0 */
    ldr x0, =_sbss
    ldr x1, =_ebss
    mov w2, #0
.Lbss_loop:
    cmp x0, x1
    b.ge .Lbss_done
    strb w2, [x0], #1
    b .Lbss_loop
.Lbss_done:

    bl main

    /* main 返回就停机 */
.Lhang:
    wfi
    b .Lhang

.section .bss
.align 16
stack_bottom:
    .skip 65536
stack_top:
