/**
 * dsh-android-bridge browser half（0.14.0）。
 *
 * 0.14.0（用户定例）：「设备控制授权」ADB 诊断面板**已移除**——ADB 链退役、正式特权通道转 Shizuku，
 * 屏幕/Shizuku/虚拟屏/浮窗/无障碍/强制销毁统一收进 dsh-client-ui-responsive 的「手机控制」设置分区。
 * 本文件保留 `./client` 入口契约（插件 manifest 要求），不再注册任何 UI。
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'

/** Required services (cordis fiber inject). */
export const inject = [] as const

/**
 * Client plugin body: browser half is intentionally empty after the ADB panel removal.
 * @param _ctx - client root context (unused).
 */
export function apply(_ctx: ClientContext): void {
  // Intentionally empty: the phone-control surface lives in dsh-client-ui-responsive.
}
