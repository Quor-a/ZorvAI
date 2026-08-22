// IAidlAciService.aidl
// ACI 控制方（AI 中枢）调用受控方（第三方 App）的统一服务接口
package ai.aidl.aci.core;

import ai.aidl.aci.core.AidlAciRequest;
import ai.aidl.aci.core.AidlAciResponse;
import ai.aidl.aci.core.IAidlAciCallback;

interface IAidlAciService {
    /** 同步调用：AI 中枢通过此方法调用第三方 App 的任意功能 */
    AidlAciResponse call(in AidlAciRequest request);
    /** 异步调用：传入回调接口，第三方 App 处理完后回调 */
    void callAsync(in AidlAciRequest request, in IAidlAciCallback callback);
    /** 获取该 App 暴露的所有能力声明列表（用于 AI 侧能力发现），每项为一个 Capability 的 JSON 字符串 */
    String[] getCapabilities();
    /** 心跳检测：AI 侧用来判断服务是否存活 */
    boolean ping();
}
