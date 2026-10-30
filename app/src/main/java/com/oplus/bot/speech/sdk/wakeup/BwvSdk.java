package com.oplus.bot.speech.sdk.wakeup;

/**
 * 唤醒引擎 —— **从 OPPO Breeno 逆向重建的 JNI 绑定**（详见 {@link com.oplus.bot.speech.sdk.frontend.BwvFeSdk} 的说明）。
 *
 * `libbwvsdkjni.so` 导出的符号是 `Java_com_oplus_bot_speech_sdk_wakeup_BwvSdk_<方法名>`，
 * 本类的包名/类名/方法名/参数 descriptor 全部与它逐项一致（来源：解析它 dex 里的 ACC_NATIVE 方法）：
 *
 * | 方法 | descriptor | 作用 |
 * |---|---|---|
 * | `nGetInstance` | `()J` | 取引擎实例句柄 |
 * | `nInitInstance` | `(JLjava/lang/String;Ljava/lang/String;)I` | 初始化（两个路径参数） |
 * | `nReinitInstance` | `(JLjava/lang/String;Ljava/lang/String;)I` | 换模型重init |
 * | `nResetInstance` / `nStopInstance` | `(J)I` | 重置 / 停止 |
 * | `nReleaseInstance` | `(J)V` | 释放 |
 * | `nInstanceStatus` | `(J)I` | 引擎状态 |
 * | `nQueryModel` | `(J)I` | 查当前模型 |
 * | `nFeedData` | `(J[BILcom/oplus/bot/speech/sdk/wakeup/BwvSdk$FeedOptions;)I` | **喂音频流** |
 * | `nStopFeed` | `(J)I` | 停止喂 |
 * | `nSetListener` | `(JLcom/oplus/bot/speech/sdk/wakeup/BwvSdkListener;)V` | 结果回调 |
 * | `nSetHParams` | `(JLcom/oplus/bot/speech/sdk/wakeup/BwvSdk$HParams;)I` | 参数 |
 * | `nVersion` | `(J)Ljava/lang/String;` | 版本 |
 * | `nVprintMode` | `(J)I` | 声纹模式 |
 *
 * 🔴 内部类与字段名必须逐字一致：JNI 用 `GetFieldID(className, fieldName, fieldSig)` 读字段，
 * `FeedOptions` / `HParams` 里少一个字段、改一个下划线，native 侧就读到脏值或崩溃。
 */
public final class BwvSdk {

    static {
        System.loadLibrary("bwvsdkjni");
    }

    public BwvSdk() {
    }

    /** `nFeedData` 的选项（字段名与 descriptor 照抄自 Breeno dex）。 */
    public static final class FeedOptions {
        public String dump_path;
        public boolean enable_dump;

        public FeedOptions() {
        }
    }

    /** 唤醒参数（11 个字段，照抄自 Breeno dex）。 */
    public static final class HParams {
        public boolean enable_oneshot_register;
        public boolean enable_prefix;
        public boolean enable_vad;
        public boolean enable_vpr;
        public int mode;
        public int num_mics;
        public int num_refs;
        public int sample_rate;
        public boolean stream_mode;
        public int train_steps;
        public boolean two_stage;

        public HParams() {
        }

        /** 语音助手的常见默认：单麦、16k、开 VAD、关声纹注册。 */
        public static HParams defaults() {
            HParams p = new HParams();
            p.enable_vad = true;
            p.enable_prefix = true;
            p.enable_vpr = false;
            p.enable_oneshot_register = false;
            p.stream_mode = true;
            p.two_stage = false;
            p.num_mics = 1;
            p.num_refs = 0;
            p.sample_rate = 16000;
            p.mode = 0;
            p.train_steps = 0;
            return p;
        }
    }

    public native long nGetInstance();

    public native int nInitInstance(long handle, String arg1, String arg2);

    public native int nReinitInstance(long handle, String arg1, String arg2);

    public native int nResetInstance(long handle);

    public native int nStopInstance(long handle);

    public native void nReleaseInstance(long handle);

    public native int nInstanceStatus(long handle);

    public native int nQueryModel(long handle);

    public native int nFeedData(long handle, byte[] pcm, int length, FeedOptions options);

    public native int nStopFeed(long handle);

    public native void nSetListener(long handle, BwvSdkListener listener);

    public native int nSetHParams(long handle, HParams params);

    public native String nVersion(long handle);

    public native int nVprintMode(long handle);
}
