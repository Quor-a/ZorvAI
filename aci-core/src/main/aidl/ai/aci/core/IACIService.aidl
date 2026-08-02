// IACIService.aidl
// ACI 控制方（AI 中枢）调用受控方（第三方 App）的统一服务接口
package ai.aci.core;

import ai.aci.core.ACIRequest;
import ai.aci.core.ACIResponse;
import ai.aci.core.IACICallback;

interface IACIService {
    /** 同步调用：AI 中枢通过此方法调用第三方 App 的任意功能 */
    ACIResponse call(in ACIRequest request);
    /** 异步调用：传入回调接口，第三方 App 处理完后回调 */
    void callAsync(in ACIRequest request, in IACICallback callback);
    /** 获取该 App 暴露的所有能力声明列表（用于 AI 侧能力发现），每项为一个 Capability 的 JSON 字符串 */
    String[] getCapabilities();
    /** 心跳检测：AI 侧用来判断服务是否存活 */
    boolean ping();
}
