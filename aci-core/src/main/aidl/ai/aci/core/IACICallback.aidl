// IACICallback.aidl
// 异步调用回调接口 —— v1.1 引入
// AI 中枢实现此接口，传给第三方 App，
// 第三方 App 处理完后异步回调用结果。

package ai.aci.core;

import ai.aci.core.ACIResponse;

interface IACICallback {
    /**
     * 异步返回调用结果
     * @param response 执行结果
     */
    void onResult(in ACIResponse response);

    /**
     * 进度回调（用于长时间运行的操作）
     * @param progress 0-100
     * @param message  进度描述
     */
    void onProgress(int progress, String message);
}
