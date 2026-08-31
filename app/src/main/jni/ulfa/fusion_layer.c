/*
 * ============================================================
 * ULFA - Fusion Layer (融合层)
 * 抽象接口 + proot 后端实现
 * 编译: cc -O2 -D_GNU_SOURCE -o fusiond fusion_layer.c -lutil
 *       (Android/bionic: openpty 在 libc 里, 不需要 -lutil)
 * ============================================================
 *
 * 架构核心: Fusion Layer 抽象了"底层如何跑 Linux"这一能力.
 *   - 当前后端: proot (用户态 ptrace 翻译, 无 root 无 AVF)
 *   - 未来后端: AVF/KVM (vsock + virtio-fs, 真 VM)
 * 上层终端 App 只依赖 FusionOps 接口, 后端可插拔, 上层零改动.
 *
 * ------------------------------------------------------------
 * 修订记录 (2026-09-01) —— 修掉四处"看起来能跑、真机必挂"的问题:
 *
 *  1. execlp("proot") 依赖外部 PATH. 子进程环境里 PATH 常常是继承不到
 *     的(Android 上 ProcessBuilder 默认环境尤其如此), 结果必然 exit 127.
 *     → 改为按 ULFA_PROOT / $ULFA_HOME/rootfs/usr/local/bin/proot /
 *       $ULFA_HOME/bin/proot / PATH 依次解析, 用绝对路径 execv.
 *
 *  2. argv[] 被丢弃. 原实现 execlp("proot", ..., cmd, NULL) 只传了
 *     程序名, 调用方给的 argv 一个都没传进去.
 *     → 现在完整拼出 proot 参数 + 真实 argv.
 *
 *  3. 注释写着"用 PTY", 代码实际是 pipe. pipe 下 isatty()==false,
 *     bash 走非交互模式: vim / top / Ctrl+C / 提示符全部失效.
 *     → 改为 openpty + fork, 子进程 setsid + TIOCSCTTY 取得控制终端.
 *
 *  4. mount() / port_forward() 只 fprintf 打印, 什么都没做.
 *     → mount 现在进 bind 列表, 下一次 exec 真正以 -b 传给 proot;
 *       port_forward 现在真的建一个监听socket转发到 guest.
 * ============================================================
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <fcntl.h>
#include <pty.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pthread.h>

#define MAX_BINDS 32
#define MAX_ARGS  128

/* ---------- 1. 融合层抽象接口 (backend-agnostic) ---------- */
typedef struct FusionOps {
    /* 进程融合: 在融合环境中执行命令, 返回 pid (异步)
     *
     * 注意 PTY 语义: 使用 PTY 时 *in_fd / *out_fd / *err_fd 会被写成
     * 同一个 PTY master fd —— 终端本来就是一条全双工通道。
     * 调用方只需要 close() 一次 (close(*in_fd));
     * out_fd / err_fd 是同一个值的别名, 不要重复关。
     */
    pid_t (*exec)(const char *cmd, char *const argv[], int *in_fd, int *out_fd, int *err_fd);
    /* VFS 融合: 将宿主路径 source 映射到环境内 target */
    int   (*mount)(const char *source, const char *target);
    /* 网络融合: 端口转发 (宿主port -> 环境内port) */
    int   (*port_forward)(int host_port, int guest_port);
    /* 生命周期 */
    int   (*start)(void);
    int   (*stop)(void);
} FusionOps;

static pid_t g_child = -1;
static int   g_master = -1;

/* 运行时 bind 列表: mount() 写进来, 下一次 exec 真正以 -b 传给 proot */
static char  g_binds[MAX_BINDS][2][PATH_MAX];
static int   g_bind_count = 0;

/* ---------- 2. 工具函数 ---------- */

/*
 * 解析 proot 绝对路径。优先级:
 *   1) $ULFA_PROOT                                 显式指定
 *   2) $ULFA_HOME/rootfs/usr/local/bin/proot       ★ rootfs 内自持的那份
 *   3) $ULFA_HOME/bin/proot                        与 rootfs 平铺的布局
 *   4) PATH 查找 (最后的退路, 可能失败)
 *
 * 之所以把 2 放这么靠前: 这就是"把 proot 打进 rootfs"的意义 ——
 * 容器运行时由 rootfs 自带, 宿主 PATH 里有没有 proot 都无所谓。
 */
static int resolve_proot(char *out, size_t outsz) {
    const char *home;
    const char *p;
    char cand[PATH_MAX];

    if ((p = getenv("ULFA_PROOT")) && *p) {
        snprintf(out, outsz, "%s", p);
        return access(out, X_OK) == 0;
    }

    home = getenv("ULFA_HOME");
    if (!home || !*home) home = ".";

    snprintf(cand, sizeof(cand), "%s/rootfs/usr/local/bin/proot", home);
    if (access(cand, X_OK) == 0) { snprintf(out, outsz, "%s", cand); return 1; }

    snprintf(cand, sizeof(cand), "%s/rootfs/usr/bin/proot", home);
    if (access(cand, X_OK) == 0) { snprintf(out, outsz, "%s", cand); return 1; }

    snprintf(cand, sizeof(cand), "%s/bin/proot", home);
    if (access(cand, X_OK) == 0) { snprintf(out, outsz, "%s", cand); return 1; }

    snprintf(out, outsz, "proot");   /* 退路: 走 execvp */
    return 0;
}

static char *xstrdup(const char *s) {
    char *r = strdup(s ? s : "");
    if (!r) { perror("strdup"); _exit(126); }
    return r;
}

/* ---------- 3. proot 后端实现 ---------- */
/*
 * 进程融合原理:
 *   proot 通过 ptrace 拦截 guest 进程的 syscall, 把 Ubuntu/glibc
 *   发出的路径/uid/pid 等做翻译, 使其能被 Android Linux 内核接受.
 *   这就是"用户态内核抽象层" - 类比 WSL1 的 syscall 翻译.
 */
static pid_t proot_exec(const char *cmd, char *const argv[],
                        int *in_fd, int *out_fd, int *err_fd) {
    char proot_path[PATH_MAX];
    char rootfs[PATH_MAX];
    char *args[MAX_ARGS];
    int n = 0;
    int i;
    const char *home = getenv("ULFA_HOME");
    if (!home || !*home) home = ".";

    resolve_proot(proot_path, sizeof(proot_path));
    snprintf(rootfs, sizeof(rootfs), "%s/rootfs", home);

    if (access(rootfs, F_OK) != 0) {
        fprintf(stderr, "[ULFA] rootfs 不存在: %s\n", rootfs);
        return -1;
    }
    if (access(proot_path, X_OK) != 0 && strcmp(proot_path, "proot") == 0) {
        fprintf(stderr, "[ULFA] 找不到 proot。请设置 ULFA_PROOT=<绝对路径>，"
                        "或把 proot 放到 %s/rootfs/usr/local/bin/proot\n", home);
        return -1;
    }

    /* ---- 真 PTY: openpty 而不是 pipe ---- */
    int master = -1, slave = -1;
    struct winsize ws = { .ws_row = 24, .ws_col = 80, .ws_xpixel = 0, .ws_ypixel = 0 };

    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        perror("openpty failed");
        return -1;
    }
    /* master 设非阻塞, 避免上层读线程卡死 */
    int fl = fcntl(master, F_GETFL, 0);
    if (fl >= 0) fcntl(master, F_SETFL, fl | O_NONBLOCK);

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork failed");
        close(master); close(slave);
        return -1;
    }
    if (pid == 0) {
        /* ---- 子进程: 变成会话首进程并接管 PTY 作为控制终端 ---- */
        close(master);
        setsid();
#ifdef TIOCSCTTY
        if (ioctl(slave, TIOCSCTTY, 0) < 0) {
            /* 某些内核/容器下会失败, 不致命, 继续 */
        }
#endif
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        /* ---- 组装 proot 命令行 ---- */
        args[n++] = xstrdup(proot_path);
        args[n++] = xstrdup("-r");
        args[n++] = xstrdup(rootfs);
        args[n++] = xstrdup("--link2symlink");
        args[n++] = xstrdup("-0");                 /* fake root: 容器内当 root */

        /* 固定绑定: /proc /sys /dev /dev/pts (PTY 在容器内可用的前提) */
        static const char *fixed[] = { "/proc", "/sys", "/dev", "/dev/pts", NULL };
        for (i = 0; fixed[i] && n < MAX_ARGS - 4; i++) {
            args[n++] = xstrdup("-b");
            args[n++] = xstrdup(fixed[i]);
        }
        /* 设备特殊绑定（对齐生产 proot 启动，参考 Agora）:
           /dev/random 映射到 urandom，避免容器内读 /dev/random 阻塞 */
        args[n++] = xstrdup("-b");
        args[n++] = xstrdup("/dev/urandom:/dev/random");
        /* /system/build.prop 可读时才绑（否则整体 proot 启动失败） */
        if (access("/system/build.prop", R_OK) == 0) {
            args[n++] = xstrdup("-b");
            args[n++] = xstrdup("/system/build.prop:/system/build.prop");
        }
        /* 注: /root(home) / /tmp / /sdcard 由 main() 经 f->mount() 登记进 g_binds，
           上面"运行时 bind"循环已统一以 -b 处理，这里不重复。 */
        /* 运行时由 mount() 登记的绑定 */
        for (i = 0; i < g_bind_count && n < MAX_ARGS - 4; i++) {
            char spec[PATH_MAX * 2 + 2];
            snprintf(spec, sizeof(spec), "%s:%s", g_binds[i][0], g_binds[i][1]);
            args[n++] = xstrdup("-b");
            args[n++] = xstrdup(spec);
        }

        args[n++] = xstrdup("-w");
        args[n++] = xstrdup("/root");
        args[n++] = (char *)"--";

        /* 真实命令 + 它的 argv (原实现把这整个数组丢了) */
        args[n++] = (char *)cmd;
        if (argv) {
            for (i = 1; argv[i] && n < MAX_ARGS - 1; i++) args[n++] = (char *)argv[i];
        }
        args[n] = NULL;

        execv(proot_path, args);
        /* execvp 退路: proot_path 可能是裸 "proot" */
        if (errno == ENOENT) execvp("proot", args);

        fprintf(stderr, "[ULFA] exec %s failed: %s\n", proot_path, strerror(errno));
        _exit(127);
    }

    close(slave);
    g_child = pid;
    g_master = master;
    if (in_fd)  *in_fd  = master;
    if (out_fd) *out_fd = master;
    if (err_fd) *err_fd = master;
    return pid;
}

static int proot_mount(const char *source, const char *target) {
    /*
     * 真实实现: 登记进 bind 列表, 下一次 exec 时以 proot -b 生效。
     * (proot 没有运行时 mount syscall, 绑定只能在启动时指定, 这是机制限制,
     *  不是偷懒 —— 但至少现在它是真生效的, 不再只是打印一行日志。)
     */
    if (!source || !target) return -EINVAL;
    for (int i = 0; i < g_bind_count; i++) {
        if (strcmp(g_binds[i][1], target) == 0) {
            snprintf(g_binds[i][0], PATH_MAX, "%s", source);   /* 覆盖同 target */
            fprintf(stderr, "[ULFA:VFS] bind updated: %s -> %s\n", source, target);
            return 0;
        }
    }
    if (g_bind_count >= MAX_BINDS) {
        fprintf(stderr, "[ULFA:VFS] bind list full (max %d)\n", MAX_BINDS);
        return -ENOSPC;
    }
    snprintf(g_binds[g_bind_count][0], PATH_MAX, "%s", source);
    snprintf(g_binds[g_bind_count][1], PATH_MAX, "%s", target);
    g_bind_count++;
    fprintf(stderr, "[ULFA:VFS] bind registered: %s -> %s (生效于下一次 exec)\n", source, target);
    return 0;
}

/* 端口转发线程: 在宿主 host_port 监听, 原样转发到 guest 的 guest_port。
 * proot 共享 Android 网络栈, guest 内监听的就是 127.0.0.1, 所以这里
 * 只是一个 user-space 的中继 (对"宿主其他进程访问 guest 服务"有效)。 */
typedef struct { int host_port, guest_port, listen_fd; } pf_t;

static void *pf_thread(void *arg) {
    pf_t *p = (pf_t *)arg;
    for (;;) {
        int c = accept(p->listen_fd, NULL, NULL);
        if (c < 0) { if (errno == EINTR) continue; break; }
        int g = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in a = {0};
        a.sin_family = AF_INET;
        a.sin_port = htons(p->guest_port);
        a.sin_addr.s_addr = inet_addr("127.0.0.1");
        if (g >= 0 && connect(g, (struct sockaddr *)&a, sizeof(a)) == 0) {
            pid_t pid = fork();
            if (pid == 0) {                 /* 子进程: g -> c */
                char buf[8192]; ssize_t n;
                close(p->listen_fd);
                while ((n = read(g, buf, sizeof(buf))) > 0) {
                    ssize_t off = 0;
                    while (off < n) {
                        ssize_t w = write(c, buf + off, n - off);
                        if (w <= 0) goto done;
                        off += w;
                    }
                }
            done:
                close(c); close(g); _exit(0);
            } else {                        /* 父线程: c -> g */
                char buf[8192]; ssize_t n;
                while ((n = read(c, buf, sizeof(buf))) > 0) {
                    ssize_t off = 0;
                    while (off < n) {
                        ssize_t w = write(g, buf + off, n - off);
                        if (w <= 0) break;
                        off += w;
                    }
                }
                close(c); close(g);
            }
        } else {
            if (g >= 0) close(g);
            close(c);
        }
    }
    close(p->listen_fd);
    free(p);
    return NULL;
}

static int proot_port_forward(int host_port, int guest_port) {
    if (host_port <= 0 || host_port > 65535 || guest_port <= 0 || guest_port > 65535)
        return -EINVAL;

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return -errno; }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)host_port);
    a.sin_addr.s_addr = INADDR_ANY;
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) != 0) {
        fprintf(stderr, "[ULFA:NET] bind :%d failed: %s\n", host_port, strerror(errno));
        close(fd);
        return -errno;
    }
    if (listen(fd, 8) != 0) { close(fd); return -errno; }

    pf_t *p = calloc(1, sizeof(pf_t));
    p->host_port = host_port; p->guest_port = guest_port; p->listen_fd = fd;
    pthread_t t;
    if (pthread_create(&t, NULL, pf_thread, p) != 0) {
        close(fd); free(p);
        return -errno;
    }
    pthread_detach(t);
    fprintf(stderr, "[ULFA:NET] forwarding host:%d -> guest:%d (thread started)\n",
            host_port, guest_port);
    return 0;
}

static int proot_start(void) { fprintf(stderr, "[ULFA] proot backend started\n"); return 0; }
static int proot_stop(void)  {
    if (g_child > 0) kill(g_child, SIGHUP), kill(g_child, SIGTERM);
    if (g_master >= 0) close(g_master), g_master = -1;
    return 0;
}

/* proot 后端实例 */
static FusionOps proot_backend = {
    .exec         = proot_exec,
    .mount        = proot_mount,
    .port_forward = proot_port_forward,
    .start        = proot_start,
    .stop         = proot_stop,
};

/* ---------- 4. 后端选择 (未来 AVF 在此分支) ---------- */
FusionOps *fusion_select(const char *backend) {
    if (backend && strcmp(backend, "avf") == 0) {
        /* TODO: 返回 avf_backend (vsock + virtio-fs)
         * 需要 Android 15+ AVF + protected KVM + VirtualMachineManager API.
         * 接口完全相同, 上层无需改动.
         *
         * 2026-09-01 实测本机: 无 /dev/kvm、无虚拟化内核模块、
         * CPU flags 无虚拟化特性、无 hypervisor 目录 ——
         * 在这台设备上 avf_backend 写完也永远跑不起来, 故不实现。 */
        fprintf(stderr, "[ULFA] AVF backend not built (requires AVF device)\n");
        return NULL;
    }
    return &proot_backend;  /* 默认: proot, 无 root 无 AVF 可用 */
}

/* ---------- 5. 演示: 在融合环境中编译一个 NDK 项目 ---------- */
/*
 * 交互式终端入口（替代原 android-cmake demo）。
 *
 * 设计:
 *   - App 经 Termux 的 PTY 拉起 fusiond: fusiond 的 stdin/stdout 就是那层
 *     "外层 PTY" 的 slave 端，App 持有 master 端做终端 IO。
 *   - fusiond 内部 openpty 再开一层 "内层 PTY"，proot 的 guest shell 以
 *     内层 PTY 为控制终端（-0 fake root，isatty 为真 → bash 走交互模式）。
 *   - 本 main() 只做一件事: 把 外层PTY(stdin/stdout) ⇄ 内层PTY(g_master)
 *     双向转发，直到 guest shell 退出。App 侧看到的是一条 PTY，没有双层 PTY。
 *
 * guest shell 取法: argv[1] 为程序（默认 /bin/bash），argv[1..] 为其参数。
 */
static int  g_inner_fd = -1;
static pid_t g_shell_pid = -1;

/* 外层 → 内层: App 敲的键转发进 proot 的 shell */
static void *bridge_to_inner(void *arg) {
    (void)arg;
    char buf[4096];
    ssize_t n;
    while ((n = read(STDIN_FILENO, buf, sizeof(buf))) > 0) {
        ssize_t w = 0;
        while (w < n) {
            ssize_t k = write(g_inner_fd, buf + w, (size_t)(n - w));
            if (k < 0) { if (errno == EINTR) continue; goto done; }
            w += k;
        }
    }
done:
    /* App 关了终端(stdin EOF): 通知 guest shell 退出 */
    if (g_shell_pid > 0) kill(g_shell_pid, SIGHUP);
    return NULL;
}

/* 内层 → 外层: proot 的 shell 输出回传给 App */
static void *bridge_to_outer(void *arg) {
    (void)arg;
    char buf[4096];
    ssize_t n;
    while ((n = read(g_inner_fd, buf, sizeof(buf))) > 0) {
        ssize_t w = 0;
        while (w < n) {
            ssize_t k = write(STDOUT_FILENO, buf + w, (size_t)(n - w));
            if (k < 0) { if (errno == EINTR) continue; goto done; }
            w += k;
        }
    }
done:
    return NULL;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);   /* 终端关闭后写 stdout 不应崩进程 */

    FusionOps *f = fusion_select(getenv("ULFA_BACKEND"));
    if (!f) return 1;
    f->start();

    /* VFS 融合: 把生产 proot 启动的关键绑定对齐进来，做成真正平替。
     * 宿主侧路径可由 env 覆盖（默认回退），不同 ROM 上真实路径可能不同，由调用方注入。 */
    const char *ws = getenv("ULFA_WORKSPACE_HOST");
    if (!ws || !*ws) ws = "/sdcard/Download";
    f->mount(ws, "/sdcard");                       /* 共享存储 → /sdcard（对齐生产） */

    const char *rb = getenv("ULFA_ROOT_BIND");     /* 外置 home → /root */
    if (rb && *rb) f->mount(rb, "/root");

    const char *tb = getenv("ULFA_TMP_BIND");      /* 外置 tmp → /tmp */
    if (tb && *tb) f->mount(tb, "/tmp");

    /* 进程融合: 交互式 shell。argv[1] 为 guest 程序（默认 /bin/bash） */
    char *def_argv[2];
    char **guest_argv;
    const char *shell;
    if (argc > 1 && argv[1] && *argv[1]) {
        shell = argv[1];
        guest_argv = &argv[1];          /* [0]=程序名(被 exec 忽略), [1..]=参数 */
    } else {
        def_argv[0] = (char *)"/bin/bash";
        def_argv[1] = NULL;
        shell = "/bin/bash";
        guest_argv = def_argv;
    }

    int fd = -1;
    pid_t pid = f->exec(shell, (char *const *)guest_argv, &fd, NULL, NULL);
    if (pid < 0) { fprintf(stderr, "[ULFA] exec failed\n"); return 1; }
    g_shell_pid = pid;
    g_inner_fd  = fd;
    fprintf(stderr, "[ULFA] spawned pid=%d on inner pty master fd=%d\n", pid, fd);

    /* 网络融合（示例，真起转发线程；默认 8080→8080） */
    f->port_forward(8080, 8080);

    /* 双向桥接: 外层 PTY ⇄ 内层 PTY。t_in 不 join（可能阻塞在 stdin 读），
       进程退出即随线程一起结束；t_out 在 shell 退出后内层 PTY EOF 自行结束。 */
    pthread_t t_in, t_out;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&t_in, &attr, bridge_to_inner, NULL);
    pthread_create(&t_out, NULL, bridge_to_outer, NULL);
    pthread_attr_destroy(&attr);

    int st = 0;
    waitpid(pid, &st, 0);        /* 等 guest shell 退出 */
    f->stop();
    pthread_join(t_out, NULL);
    if (fd >= 0) close(fd);       /* 只关一次: in/out/err 是同一个 fd */
    return WIFEXITED(st) ? WEXITSTATUS(st) : (WIFSIGNALED(st) ? 128 + WTERMSIG(st) : 1);
}
