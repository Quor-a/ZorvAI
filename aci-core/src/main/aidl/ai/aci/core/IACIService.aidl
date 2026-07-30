// IACIService.aidl
// ACI 核心跨进程调用接口 —— 所有第三方 App 必须实现此接口
package ai.aci.core;

import ai.aci.core.ACIRequest;
import ai.aci.core.ACIResponse;
import ai.aci.core.IACICallback;

interface IACIService {
    /**
     * 同步调用：AI 中枢通过此方法调用第三方 App 的任意功能
     * @param request 统一的请求对象（包含 capability + params）
     * @return 统一的响应对象（包含 success + result）
     */
    ACIResponse call(in ACIRequest request);

    /**
     * 异步调用：传入回调接口，第三方 App 处理完后回调
     * @param request  请求对象
     * @param callback 回调（第三方 App 持有此引用）
     */
    void callAsync(in ACIRequest request, in IACICallback callback);

    /**
     * 获取该 App 暴露的所有能力声明列表（用于 AI 侧能力发现）
     * @return JSON 字符串数组，每项为一个 Capability 描述
     */
    String[] getCapabilities();

    /**
     * 心跳检测：AI 侧用来判断服务是否存活
     * @return true = 服务正常
     */
    boolean ping();
}
