// IAidlAciCallback.aidl
// 异步调用结果回调接口（受控方持有此引用，处理完后回调 AI 中枢）
package ai.aidl.aci.core;

import ai.aidl.aci.core.AidlAciResponse;

interface IAidlAciCallback {
    /** 异步返回调用结果 */
    void onResult(in AidlAciResponse response);
    /** 进度回调（用于长时间运行的操作），progress 0-100 */
    void onProgress(int progress, in String message);
}
