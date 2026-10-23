/*
 * moexec.c —— 墨语言 AArch64 产物启动器（ZorvAI 内置）
 *
 * 为什么需要它（不是"多包一层"，而是绕一道系统限制）：
 *
 *   Android 10（API 29）起，targetSdk >= 29 的应用，其私有数据目录
 *   /data/user/0/<pkg>/... 在 SELinux 层面禁止 execve。ZorvAI 的 targetSdk
 *   是 34，所以"把墨语言编出来的 ARM64 ELF 写到 filesDir 再 ProcessBuilder
 *   调起来"必然 EACCES —— 与文件权限、chmod 无关，是域策略拒绝。
 *
 *   本启动器自身预置在 APK 的 jniLibs/arm64-v8a/ 里，安装后落在
 *   nativeLibraryDir（该目录挂载允许执行），于是它能被拉起来。它把目标 ELF
 *   读进 memfd（匿名内存文件，无路径），再用 execveat(AT_EMPTY_PATH) 执行，
 *   从而绕开"按路径检查"的那道限制。
 *
 * 用法：
 *   libmoexec.so <elf-path> [args...]
 *
 * 退出码：
 *   0..255   目标程序自身的退出码（exec 成功后本进程已被替换）
 *   126      启动器自身错误（参数、读文件、不是 ELF）
 *   127      三种执行方式全部失败（stderr 写明每一步的原因）
 *
 * 三级退化（每级失败都把原因打到 stderr，便于真机定位）：
 *   1. execveat(memfd, "", ..., AT_EMPTY_PATH)   内核 3.19+，最干净
 *   2. execve("/proc/self/fd/N", ...)            老内核走 fd 软链
 *   3. execve(原路径, ...)                       目标目录本身可执行时
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef SYS_memfd_create
#define SYS_memfd_create 279 /* arm64 / asm-generic */
#endif

extern char **environ;

static const char *mo_strerror(int e) {
    return strerror(e);
}

/* 把 in 的全部内容写到 out；EINTR 重试，短写补齐。 */
static int copy_all(int in, int out) {
    char buf[65536];
    for (;;) {
        ssize_t n = read(in, buf, sizeof buf);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
            off += w;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "moexec: 用法: %s <elf路径> [参数...]\n",
                (argc > 0 && argv[0]) ? argv[0] : "moexec");
        return 126;
    }

    const char *path = argv[1];
    char **targv = &argv[1]; /* 目标程序的 argv[0] 就是它的路径 */

    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in < 0) {
        fprintf(stderr, "moexec: 打开 %s 失败: %s\n", path, mo_strerror(errno));
        return 126;
    }

    unsigned char magic[4];
    ssize_t got = read(in, magic, 4);
    if (got != 4 || magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' ||
        magic[3] != 'F') {
        fprintf(stderr, "moexec: %s 不是 ELF 文件\n", path);
        close(in);
        return 126;
    }
    if (lseek(in, 0, SEEK_SET) != 0) {
        fprintf(stderr, "moexec: 回绕 %s 失败: %s\n", path, mo_strerror(errno));
        close(in);
        return 126;
    }

    int first_err = 0;

    /* ---- 1) memfd + execveat(AT_EMPTY_PATH) ---- */
    int fd = (int)syscall(SYS_memfd_create, "mo-elf", 0);
    if (fd >= 0) {
        if (copy_all(in, fd) == 0) {
            if (lseek(fd, 0, SEEK_SET) != 0) {
                fprintf(stderr, "moexec: memfd 回绕失败: %s\n", mo_strerror(errno));
            }
            fchmod(fd, 0700);
            syscall(SYS_execveat, fd, "", targv, environ, AT_EMPTY_PATH);
            first_err = errno;
            fprintf(stderr, "moexec: [1/3] execveat(memfd) 失败: %s\n",
                    mo_strerror(first_err));
        } else {
            first_err = errno;
            fprintf(stderr, "moexec: 写入 memfd 失败: %s\n", mo_strerror(errno));
        }
    } else {
        first_err = errno;
        fprintf(stderr, "moexec: [1/3] memfd_create 失败: %s\n", mo_strerror(errno));
    }

    /* ---- 2) /proc/self/fd/N（老内核没有 execveat） ---- */
    if (fd >= 0) {
        char p[64];
        snprintf(p, sizeof p, "/proc/self/fd/%d", fd);
        execve(p, targv, environ);
        fprintf(stderr, "moexec: [2/3] execve(%s) 失败: %s\n", p,
                mo_strerror(errno));
    }

    /* ---- 3) 直接执行原路径（目标目录本身允许执行时） ---- */
    close(in);
    execve(path, targv, environ);
    fprintf(stderr, "moexec: [3/3] execve(%s) 失败: %s\n", path,
            mo_strerror(errno));

    if (first_err == EPERM || first_err == EACCES) {
        fprintf(stderr,
                "moexec: 该设备的 SELinux 同时拒绝了 memfd 执行与数据目录执行。\n"
                "moexec: 这条 ROM 限制较严，请改用目标 x86_64（qemu）或把产物放进 jniLibs。\n");
    }
    return 127;
}
