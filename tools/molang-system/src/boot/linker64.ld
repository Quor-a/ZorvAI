/* 墨语言 ARM64 内核链接脚本 —— QEMU virt（RAM 从 0x40000000 起）
 *
 * 加载地址 0x40000000 是 QEMU virt 机器的 RAM 基址，
 * `qemu-system-aarch64 -M virt -kernel xxx.elf` 会把 ELF 按这里的
 * 物理地址原样铺进内存，然后跳到 ENTRY。
 */
ENTRY(_start)
SECTIONS
{
    . = 0x40000000;

    .text   ALIGN(16) : { *(.text*) }
    .rodata ALIGN(16) : { *(.rodata*) }
    .data   ALIGN(16) : { *(.data*) }
    .bss    ALIGN(16) :
    {
        _sbss = .;
        *(COMMON)
        *(.bss*)
        _ebss = .;
    }

    . = ALIGN(16);
    . += 0x10000;          /* 64KB 栈 */
    stack_top = .;
}
