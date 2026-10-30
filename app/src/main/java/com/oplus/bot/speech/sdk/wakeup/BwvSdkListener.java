package com.oplus.bot.speech.sdk.wakeup;

/**
 * 唤醒结果回调 —— 接口名与方法 descriptor 照抄自 OPPO Breeno：
 * `onInit(Ljava/lang/String;)V` / `onResult(Ljava/lang/String;)V` / `onError(Ljava/lang/String;)V`。
 *
 * native 侧通过 `nSetListener(handle, listener)` 持有它，并在引擎线程上回调。
 * 🔴 回调在 **native 线程**执行，不要在里面直接碰 UI；`BreenoWakeup` 内部已切线程。
 */
public interface BwvSdkListener {
    void onInit(String info);

    void onResult(String result);

    void onError(String error);
}
