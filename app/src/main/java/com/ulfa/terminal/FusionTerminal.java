/*
 * ============================================================
 * ULFA - Android 终端 App 端
 * FusionTerminal.java: 终端 UI <-> Fusion Layer 对接
 * 构建: 随你的 App (minSdk 26+) 编译
 * ============================================================
 *
 * 职责:
 *   1. 首次启动解压 rootfs，并把 proot 二进制放进 rootfs（自持运行时）
 *   2. 启动 fusiond (C 融合层)，接管一个 shell 会话
 *   3. 把 IO 双向接到终端 UI
 *
 * ------------------------------------------------------------
 * 修订记录 (2026-09-01) —— 原版有 4 个真问题:
 *
 *  1. 编译不过。原代码
 *         new ProcessBuilder().command(x).directory(d).environment().putAll(env).start()
 *     —— Map.putAll() 返回 void，不能在后面继续 .start()。
 *
 *  2. env 数组是死代码。String[] env 造好了，却从来没交给 ProcessBuilder，
 *     真正的环境是 System.getenv()，ULFA_HOME 一个都没进去 →
 *     fusion_layer.c 拿不到 ULFA_HOME，rootfs 解析成 "./rootfs"。
 *
 *  3. System.setProperty("ULFA_HOME", ...) 是 JVM 属性，不会进入子进程环境。
 *     要用 ProcessBuilder.environment()。
 *
 *  4. binaryPath() 指向 filesDir/../bin/fusiond。Android 10+ (targetSdk>=28)
 *     禁止 exec 应用数据目录下的文件，且这里得叫 libxxx.so 才能被包进
 *     nativeLibraryDir 并拿到可执行权限。
 *
 *  另：App <-> fusiond 这一段用普通 pipe 是**对的**，PTY 在 fusiond 内部
 *  (openpty) 已经建好了，fusiond 负责把 PTY master 的字节搬进搬出。
 *  原注释说"关键:用 PTY 而不是 pipe"却给了 pipe，属于注释与代码不符，
 *  现在注释改成实话。
 * ============================================================
 */
package com.ulfa.terminal;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class FusionTerminal {

    private static final String TAG = "ULFA";

    private Process fusiond;          // fusion_layer.c 的产物
    private OutputStream toShell;     // App -> 融合环境
    private InputStream fromShell;    // 融合环境 -> App
    private File homeDir;             // = $ULFA_HOME，其下是 rootfs/
    private File rootfsDir;

    /* ---------- 第一步: 部署 rootfs ---------- */
    /**
     * 从 assets 解压 rootfs，并把 proot 放进去（ULFA「零外部依赖」）。
     *
     * @param ctx      context
     * @param asset    assets 里的 rootfs 压缩包，如 "ubuntu-24.04-ulfa.tar.gz"
     * @param prootLib 已放在 nativeLibraryDir 下的 libproot.so；可为 null（跳过）
     */
    public void deployRootfs(Context ctx, String asset, File prootLib) throws IOException {
        homeDir = new File(ctx.getFilesDir(), "ulfa");
        rootfsDir = new File(homeDir, "rootfs");

        // 解压（增量：已经解过就跳过）
        File marker = new File(rootfsDir, "usr/bin/bash");
        if (!marker.exists()) {
            rootfsDir.mkdirs();
            // 真实工程请用 tar 解压（commons-compress / 或交给 shell 的 busybox tar）
            extractTarGzFromAssets(ctx, asset, rootfsDir);
        }

        // ★ 把 proot 打进 rootfs：容器运行时由 rootfs 自持
        if (prootLib != null && prootLib.exists()) {
            File target = new File(rootfsDir, "usr/local/bin/proot");
            target.getParentFile().mkdirs();
            if (!target.exists() || target.length() != prootLib.length()) {
                copyFile(prootLib, target);
                // ownerOnly=false → 0755，容器内非 root 也要能执行
                target.setExecutable(true, false);
                target.setReadable(true, false);
            }
            Log.i(TAG, "proot installed into rootfs: " + target + " (" + target.length() + " bytes)");
        } else {
            Log.w(TAG, "prootLib 未提供，fusion_layer 将依赖 ULFA_PROOT 或 PATH");
        }
    }

    /* ---------- 第二步: 启动融合环境 ---------- */
    public void start(Context ctx) throws IOException {
        if (rootfsDir == null || !rootfsDir.isDirectory()) {
            throw new IllegalStateException("先调用 deployRootfs()");
        }

        // ★ 环境必须走 ProcessBuilder.environment()，System.setProperty 不管用
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("ULFA_HOME", homeDir.getAbsolutePath());
        env.put("ULFA_BACKEND", "proot");            // 未来切 AVF: "avf"
        env.put("ULFA_PROOT",
                new File(rootfsDir, "usr/local/bin/proot").getAbsolutePath());
        env.put("PATH", "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin");
        env.put("HOME", "/root");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");

        ProcessBuilder pb = new ProcessBuilder();
        pb.command(binaryPath(ctx, "fusiond"));     // 注意：不能链式 .environment().putAll().start()
        pb.directory(homeDir);
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);

        fusiond = pb.start();
        toShell = fusiond.getOutputStream();
        fromShell = fusiond.getInputStream();

        // 注意: Process.pid() 是 Java 9 / Android API 26+ 才有, 本工程以 Java 8 源码级别编译,
        // 故不可用。需 pid 时可在 API>=26 运行时用反射调用 Process#pid(); 这里仅记日志, 略去。
        Log.i(TAG, "fusion env started, ULFA_HOME=" + homeDir.getAbsolutePath());
    }

    /* ---------- 第三步: UI 写入 ---------- */
    public void write(String data) throws IOException {
        toShell.write(data.getBytes(StandardCharsets.UTF_8));
        toShell.flush();
    }

    /* ---------- 第四步: 读循环（务必放后台线程） ---------- */
    public void readLoop(OutputCallback cb) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = fromShell.read(buf)) > 0) {
            cb.onOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }

    public interface OutputCallback {
        void onOutput(String text);
    }

    /* ---------- 生命周期 ---------- */
    public void stop() {
        try {
            toShell.write("exit\n".getBytes(StandardCharsets.UTF_8));
            toShell.flush();
        } catch (Exception ignored) { }
        if (fusiond != null) fusiond.destroy();
    }

    /**
     * Android 10+ (targetSdk >= 28) 禁止 exec 应用数据目录（/data/data/...）里的文件。
     * 唯一稳定的位置是 nativeLibraryDir，且文件必须以 lib 开头、.so 结尾，
     * 系统才会把它当原生库解压出来并授予可执行权限。
     */
    private String binaryPath(Context ctx, String name) {
        String libName = "lib" + name + ".so";
        File inNative = new File(ctx.getApplicationInfo().nativeLibraryDir, libName);
        if (inNative.exists()) return inNative.getAbsolutePath();
        // 退路：filesDir（仅 targetSdk < 28 或已 root 的设备能用）
        return new File(ctx.getFilesDir(), name).getAbsolutePath();
    }

    /* ---------- 工具 ---------- */
    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        dst.setExecutable(true, false);
    }

    /** 占位：真实工程请换成 tar.gz 解压实现（commons-compress 或 busybox tar）。 */
    private static void extractTarGzFromAssets(Context ctx, String asset, File dest)
            throws IOException {
        try (InputStream in = ctx.getAssets().open(asset)) {
            File tmp = new File(dest.getParentFile(), asset);
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.w(TAG, "rootfs 压缩包已落到 " + tmp + "，请在 shell 内用 tar -xzf 解到 " + dest);
        }
    }
}
