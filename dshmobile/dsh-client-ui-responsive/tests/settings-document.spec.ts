// @vitest-environment jsdom
// SettingsDocumentAction: claims the upstream open-configuration-file action on mobile and
// routes it to the shell chooser; every missing bridge or refusal keeps upstream behavior.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { SettingsDocumentAction } from '../src/client/mobile/settings-document.ts'

let action: SettingsDocumentAction
let dialog: HTMLElement
let button: HTMLButtonElement

beforeEach(() => {
  dialog = document.createElement('div')
  dialog.setAttribute('data-dsh-settings-dialog', '')
  button = document.createElement('button')
  button.textContent = '打开配置文件'
  dialog.appendChild(button)
  document.body.appendChild(dialog)
  action = new SettingsDocumentAction()
})

afterEach(() => {
  action.detach()
  dialog.remove()
  delete (window as unknown as { androidBridge?: unknown }).androidBridge
})

/** Install a shell bridge with the given chooser answer. */
function bridge(answer: string, path = '/data/data/pkg/files/home/.dsh/settings.yaml') {
  const openPathChooser = vi.fn(() => answer)
  const settingsPath = vi.fn(() => path)
  ;(window as unknown as { androidBridge: unknown }).androidBridge = { openPathChooser, settingsPath }
  return { openPathChooser, settingsPath }
}

describe('SettingsDocumentAction', () => {
  it('claims the click and opens the settings document through the shell chooser', () => {
    const { openPathChooser } = bridge('{"ok":true}')
    action.attach()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    button.dispatchEvent(event)
    expect(openPathChooser).toHaveBeenCalledWith('/data/data/pkg/files/home/.dsh/settings.yaml', 'view')
    expect(event.defaultPrevented).toBe(true)
  })

  it('leaves the event alone when the chooser refuses (upstream error path stays)', () => {
    bridge('{"ok":false,"reason":"no-handler"}')
    action.attach()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    button.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
  })

  it('ignores the action outside the settings dialog', () => {
    const { openPathChooser } = bridge('{"ok":true}')
    const loose = document.createElement('button')
    loose.textContent = '打开配置文件'
    document.body.appendChild(loose)
    action.attach()
    loose.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(openPathChooser).not.toHaveBeenCalled()
    loose.remove()
  })

  it('stays inert without the shell bridge', () => {
    action.attach()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    expect(() => button.dispatchEvent(event)).not.toThrow()
    expect(event.defaultPrevented).toBe(false)
  })

  it('detach stops claiming', () => {
    const { openPathChooser } = bridge('{"ok":true}')
    action.attach()
    action.detach()
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(openPathChooser).not.toHaveBeenCalled()
  })
})

// ── 0.14.1 批 9（§3.3 S3-20）：打开的是**副本**必须说明，否则用户以为改的是真源 ──
describe('打开配置文件时说明「这是副本」（S3-20）', () => {
  it('走导出副本路径时给出说明（含「改完要导入」的下一步）', () => {
    const events: CustomEvent[] = []
    const listener = (e: Event): void => { events.push(e as CustomEvent) }
    window.addEventListener('dsh:export-result', listener)
    // 注意：文件既有的 bridge() 工厂**返回的对象与挂到 window 的不是同一个**（各建一次字面量），
    // 所以这里自己装一份完整桥（首版在返回值上加方法，没生效——测试自身的坑）。
    ;(window as unknown as { androidBridge: unknown }).androidBridge = {
      openPathChooser: vi.fn(() => '{"ok":true}'),
      settingsPath: vi.fn(() => '/data/data/pkg/files/home/.dsh/settings.yaml'),
      exportSettingsDocument: vi.fn(() => '/storage/emulated/0/Documents/dshdata/exports/config/settings.yaml'),
    }
    action.attach()
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    window.removeEventListener('dsh:export-result', listener)
    expect(events.length, '必须发出一条用户可见说明').toBe(1)
    expect(String(events[0].detail.title)).toContain('副本')
    expect(String(events[0].detail.detail)).toContain('导入配置')
  })
})
