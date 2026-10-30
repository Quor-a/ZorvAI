package com.oplus.bot.speech.sdk.frontend;

/**
 * 语音前端处理 SDK —— **从 OPPO Breeno（com.heytap.speechassist）逆向重建的 JNI 绑定**。
 *
 * ## 这不是仿写，是照它的真实契约重建
 * `libbwvsdkjni.so` 里导出的符号是 `Java_com_oplus_bot_speech_sdk_frontend_BwvFeSdk_<方法名>`
 * —— JNI 短名绑定要求「包名 + 类名 + 方法名 + 参数 descriptor」四者完全一致才能绑上。
 * 所以本类的包名、类名、方法名、参数类型全部逐项对齐（签名来源：从它 16 个 dex 里解析
 * `BwvFeSdk` 的 ACC_NATIVE 方法 + proto 表得到的精确 descriptor）：
 *
 * | 方法 | descriptor | 作用 |
 * |---|---|---|
 * | `nAgcInit` | `(Ljava/lang/String;)J` | 自动增益，返回句柄 |
 * | `nAgcProcess` | `(J[BIZ)[B` | 逐帧处理 PCM |
 * | `nAgcReset` / `nAgcRelease` | `(J)V` | 重置 / 释放 |
 * | `nNnaecInit` | `(Ljava/lang/String;)J` | **神经网络回声消除** |
 * | `nNnaecProcess` | `(J[BIZ)[B` | AEC 逐帧处理 |
 * | `nOneshotInit` | `(Ljava/lang/String;)J` | 单次（免唤醒/软 AEC）通道 |
 * | `nOneshotSetReference` | `(J[BII)Z` | 喂参考信号（扬声器回采） |
 * | `nOneshotStartSoftAec` | `(J)Z` | 开软 AEC |
 * | `nRemixInit` | `(Ljava/lang/String;)J` | 多麦/重采样合并 |
 * | `nComputeTotalAmplitude` | `([BI)D` | 算总幅度（音量） |
 *
 * 这是小布做「唤醒词在嘈杂环境/外放时也能听清」的底层能力：AEC 消除自己的播放声，
 * AGC 拉平音量，Remix 合并多麦克风。我们原来的语音球没有这一层。
 *
 * ## 注意
 * - `System.loadLibrary("bwvsdkjni")` 会连带加载 `libBreenoWakeupVprint / libnnaec / libMNN /
 *   libopenblas / libkws / libc++_shared`，这些 so 均已从 Breeno.apk 字节级搬入 jniLibs。
 * - 加载或调用失败一律由调用方（`BreenoWakeup`）捕获，**绝不让 JNI 报错崩掉应用**。
 */
public final class BwvFeSdk {

    static {
        System.loadLibrary("bwvsdkjni");
    }

    public BwvFeSdk() {
    }

    // ── AGC（自动增益控制）─────────────────────────────────
    public native long nAgcInit(String configPath);

    public native byte[] nAgcProcess(long handle, byte[] pcm, int length, boolean flag);

    public native void nAgcReset(long handle);

    public native void nAgcRelease(long handle);

    // ── NNAEC（神经网络回声消除）───────────────────────────
    public native long nNnaecInit(String configPath);

    public native byte[] nNnaecProcess(long handle, byte[] pcm, int length, boolean flag);

    public native void nNnaecReset(long handle);

    public native void nNnaecRelease(long handle);

    // ── Oneshot（免唤醒 / 软 AEC 单次通道）─────────────────
    public native long nOneshotInit(String configPath);

    public native byte[] nOneshotProcess(long handle, byte[] pcm, int length, boolean flag);

    public native void nOneshotReset(long handle);

    public native void nOneshotRelease(long handle);

    public native boolean nOneshotSetReference(long handle, byte[] reference, int arg1, int arg2);

    public native boolean nOneshotStartSoftAec(long handle);

    // ── Remix（多麦合并 / 重采样）──────────────────────────
    public native long nRemixInit(String configPath);

    public native byte[] nRemixProcess(long handle, byte[] pcm, int length, boolean flag);

    public native void nRemixReset(long handle);

    public native void nRemixRelease(long handle);

    // ── 幅度（音量）────────────────────────────────────────
    public native double nComputeTotalAmplitude(byte[] pcm, int length);
}
