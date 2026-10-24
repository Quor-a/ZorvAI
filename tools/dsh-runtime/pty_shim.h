/*
 * pty_shim.h —— 给 Android(bionic) 补上 node-pty 需要的 openpty / forkpty。
 *
 * 背景：node-pty 的 unix 实现 #include <pty.h> 并调用 openpty(3) / forkpty(3)。
 * 这两个是 libutil / glibc 的便利函数，**bionic 从来没有实现过**（Android 的
 * libutil 是空的）。但底层原语 bionic 是有的：posix_openpt / grantpt /
 * unlockpt / ptsname / TIOCSCTTY。
 *
 * 所以这里用原语把两个便利函数重建出来，语义对齐 glibc 的 pty(7) 实现：
 *   openpty()  开一对 master/slave，可选设置 termios 与窗口大小
 *   forkpty()  openpty + fork + setsid + 把 slave 设成控制终端并 dup 到 0/1/2
 *
 * 编译时用 -include pty_shim.h 注入到 pty.cc 之前，就等价于它拿到了 <pty.h>。
 */
#ifndef MO_PTY_SHIM_H
#define MO_PTY_SHIM_H

#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 打开一对 pty。成功返回 0，失败返回 -1 并置 errno（与 glibc 一致）。 */
static int openpty(int *amaster, int *aslave, char *name,
                   const struct termios *termp, const struct winsize *winp) {
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    if (grantpt(master) != 0) { int e = errno; close(master); errno = e; return -1; }
    if (unlockpt(master) != 0) { int e = errno; close(master); errno = e; return -1; }

    const char *slave_name = ptsname(master);
    if (slave_name == NULL) { int e = errno; close(master); errno = e; return -1; }
    if (name != NULL) strcpy(name, slave_name);

    if (aslave != NULL) {
        int slave = open(slave_name, O_RDWR | O_NOCTTY);
        if (slave < 0) { int e = errno; close(master); errno = e; return -1; }
        if (termp != NULL) tcsetattr(slave, TCSAFLUSH, termp);
        if (winp != NULL) ioctl(slave, TIOCSWINSZ, winp);
        *aslave = slave;
    }
    *amaster = master;
    return 0;
}

/*
 * openpty + fork。
 * 子进程：setsid 成为会话首进程 → 打开 slave → TIOCSCTTY 设为控制终端 →
 *         dup2 到 0/1/2 → 应用 termios/winsize → 返回 0 给调用方继续 exec。
 * 父进程：返回子 pid，master 写入 *amaster。
 */
static pid_t forkpty(int *amaster, char *name,
                     const struct termios *termp, const struct winsize *winp) {
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    if (grantpt(master) != 0) { int e = errno; close(master); errno = e; return -1; }
    if (unlockpt(master) != 0) { int e = errno; close(master); errno = e; return -1; }

    const char *slave_name = ptsname(master);
    if (slave_name == NULL) { int e = errno; close(master); errno = e; return -1; }
    if (name != NULL) strcpy(name, slave_name);

    pid_t pid = fork();
    if (pid < 0) { int e = errno; close(master); errno = e; return -1; }

    if (pid == 0) {
        close(master);
        if (setsid() < 0) _exit(1);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(1);
        /* 成为控制终端；失败不致命（有些环境不允许），继续往下走 */
        ioctl(slave, TIOCSCTTY, 0);
        if (dup2(slave, STDIN_FILENO) < 0) _exit(1);
        if (dup2(slave, STDOUT_FILENO) < 0) _exit(1);
        if (dup2(slave, STDERR_FILENO) < 0) _exit(1);
        if (slave > STDERR_FILENO) close(slave);
        if (termp != NULL) tcsetattr(STDIN_FILENO, TCSAFLUSH, termp);
        if (winp != NULL) ioctl(STDIN_FILENO, TIOCSWINSZ, winp);
        return 0;
    }

    *amaster = master;
    return pid;
}

#ifdef __cplusplus
}
#endif

#endif /* MO_PTY_SHIM_H */
