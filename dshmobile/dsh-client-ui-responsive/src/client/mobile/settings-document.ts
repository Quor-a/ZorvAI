/**
 * Mobile takeover of the upstream "open configuration file" settings action (apk #152).
 *
 * Upstream renders that action while the settings dialog is open and, on click, calls
 * `settings.openSettingsDocument()`: the Host materializes the provider-owned document and
 * hands it to a native desktop text editor (mac/win/linux). Android has no such opener, so the
 * click always ended in the localized 「无法打开配置文件」 error.
 *
 * The shell can open any path the app is allowed to read through its system chooser
 * (`androidBridge.openPathChooser`), and the settings document path is a fixed app-private
 * location the shell can report (`androidBridge.settingsPath`). This handler claims the click
 * while the settings dialog is up and routes it there; when either bridge is missing, or the
 * chooser refuses, the event is left alone so upstream behavior (and its error message) stays.
 */
import { chooserAvailable, openPathChooser } from './open-path.ts'
import { reportUserFacingResult } from '../export-result.ts'

/** Upstream action labels this handler claims (zh / en dictionaries). */
const ACTION_LABELS = ['打开配置文件', 'Open configuration file']

/** Host bridge surface this handler needs beyond the path chooser. */
interface SettingsPathBridge {
  settingsPath?: () => string
  /** apk #168：壳侧把活动 settings.yaml 复制到白名单目录后返回副本路径（空 = 失败）。 */
  exportSettingsDocument?: () => string
}

/** Read the settings document path from the shell bridge; empty when unavailable. */
function settingsPath(): string {
  const bridge = (window as unknown as { androidBridge?: SettingsPathBridge }).androidBridge
  if (typeof bridge?.settingsPath !== 'function') return ''
  try {
    return bridge.settingsPath() || ''
  } catch {
    return ''
  }
}

/**
 * apk #168 的关键一步：优先用**壳侧导出的副本**路径。
 *
 * 活动配置在私有 `$DSH_HOME`，而选择器白名单（与 FileProvider 映射）刻意不包括 `.dsh`——
 * 那里有 `.credentials.yaml` 等凭据，放宽等于把凭据交给系统选择器。所以壳侧先把 settings.yaml
 * 复制到已放行的 `Documents/dshdata/exports/config/`，页面打开的是这份副本（UI 文案已说明）。
 * 副本拿不到时才退回旧路径（私有路径会被白名单拒绝，届时仍走上游错误路径）。
 */
/**
 * 选择器要打开的路径，并标明它是不是**导出副本**（S3-20）。
 *
 * 缺陷现场：这个动作实际打开的是壳侧导出的副本，而界面上一个字都没说——用户以为改的是真源，
 * 改完发现「不生效」。把「是不是副本」作为返回值交给调用方，由它给用户可见说明。
 */
function settingsPathForChooser(): { path: string; isCopy: boolean } {
  const bridge = (window as unknown as { androidBridge?: SettingsPathBridge }).androidBridge
  if (typeof bridge?.exportSettingsDocument === 'function') {
    try {
      const exported = bridge.exportSettingsDocument() || ''
      if (exported !== '') return { path: exported, isCopy: true }
    } catch {
      /* 导出失败后回退 */
    }
  }
  return { path: settingsPath(), isCopy: false }
}

/** Whether the clicked element is the upstream open-configuration-file action. */
function isSettingsDocumentAction(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  const button = target.closest('button')
  if (button === null) return false
  // The settings dialog marker is set by the mobile form marker; the action lives in its header.
  if (button.closest('[data-dsh-settings-dialog]') === null) return false
  const label = (button.textContent ?? '').trim()
  return ACTION_LABELS.includes(label)
}

/** Claims the upstream action and opens the settings document through the shell chooser. */
export class SettingsDocumentAction {
  private readonly onClick = (event: MouseEvent): void => {
    if (!chooserAvailable()) return
    if (!isSettingsDocumentAction(event.target)) return
    const target = settingsPathForChooser()
    if (target.path === '') return
    // Claim only when the shell really took the path: a refusal keeps upstream's own error path.
    if (!openPathChooser(target.path, 'view').ok) return
    event.preventDefault()
    event.stopPropagation()
    if (target.isCopy) {
      // S3-20：打开的是**副本**，改它不会生效。用与导出结果同一条对话框通道如实说明
      // （这条动作由上游按钮触发，我们无法在它旁边内联渲染文字）。
      reportUserFacingResult({
        ok: true,
        title: '已打开配置文件的副本',
        detail: '这是导出副本（Documents/dshdata/exports/config/settings.yaml），在它上面修改不会直接生效；'
          + '改完请回到「设置 → 开发者选项 → 导入配置」。',
      })
    }
  }

  attach(): void {
    document.addEventListener('click', this.onClick, true)
  }

  detach(): void {
    document.removeEventListener('click', this.onClick, true)
  }
}
