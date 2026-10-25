// @vitest-environment jsdom
// KeyboardBoundary unit tests: pins the mobile frame height to the
// visualViewport height while an IME inset is present (issue #57), restores
// 100% when the keyboard closes, and stays inert without an IME inset.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { KeyboardBoundary } from '../src/client/keyboard-boundary.ts'

/** Set the root --dsh-android-ime-bottom CSS variable (the shell's push). */
function setImeInset(px: number) {
  document.documentElement.style.setProperty('--dsh-android-ime-bottom', px > 0 ? `${px}px` : '0px')
}

let frame: HTMLElement
let seat: HTMLElement
let boundary: KeyboardBoundary

/** jsdom has no visualViewport: shim it as an observable event target. */
function installViewport() {
  const listeners = new Map<string, Set<EventListener>>()
  const viewport = {
    height: 758,
    addEventListener: (type: string, cb: EventListener) => {
      if (!listeners.has(type)) listeners.set(type, new Set())
      listeners.get(type)!.add(cb)
    },
    removeEventListener: (type: string, cb: EventListener) => {
      listeners.get(type)?.delete(cb)
    },
    _fire: (type: string) => {
      listeners.get(type)?.forEach(cb => cb({ type } as Event))
    },
  }
  Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
  return viewport
}

let viewport: ReturnType<typeof installViewport>

beforeEach(() => {
  setImeInset(0)
  viewport = installViewport()
  frame = document.createElement('div')
  frame.setAttribute('data-dsh-frame', '')
  document.body.appendChild(frame)
  seat = document.createElement('div')
  seat.setAttribute('data-composer-seat', '')
  document.body.appendChild(seat)
  boundary = new KeyboardBoundary()
  boundary.attach()
})

afterEach(() => {
  boundary.detach()
  frame.remove()
  seat.remove()
  setImeInset(0)
  delete (window as { visualViewport?: unknown }).visualViewport
  vi.restoreAllMocks()
})

describe('KeyboardBoundary 键盘展开（IME inset > 0）', () => {
  it('IME inset 出现且 viewport 收缩：frame 高度钉到 viewport 高度', () => {
    setImeInset(316)
    Object.defineProperty(viewport, 'height', { value: 484 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('484px')
  })

  it('viewport 高度变化时跟随（键盘动画逐帧）', () => {
    setImeInset(316)
    Object.defineProperty(viewport, 'height', { value: 500 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('500px')
    Object.defineProperty(viewport, 'height', { value: 484 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('484px')
  })

  it('frame 钉高时 seat 的 IME padding 归零（防 sticky 失效导致 composer 上飘）', () => {
    setImeInset(316)
    Object.defineProperty(viewport, 'height', { value: 484 })
    viewport._fire('resize')
    expect(seat.style.paddingBottom).toBe('0px')
  })

  it('无 seat 时仍钉高 frame（seat 为可选）', () => {
    seat.remove()
    setImeInset(316)
    Object.defineProperty(viewport, 'height', { value: 484 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('484px')
  })
})

describe('KeyboardBoundary 键盘收起 / 无 inset', () => {
  it('IME inset 归零：恢复 100% 高度 + seat padding 还原', () => {
    setImeInset(316)
    Object.defineProperty(viewport, 'height', { value: 484 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('484px')
    expect(seat.style.paddingBottom).toBe('0px')
    setImeInset(0)
    viewport._fire('resize')
    expect(frame.style.height).toBe('')
    expect(seat.style.paddingBottom).toBe('')
  })

  it('无 IME inset 的窗口 resize：保持 100%（不误伤）', () => {
    setImeInset(0)
    Object.defineProperty(viewport, 'height', { value: 600 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('')
  })
})

describe('KeyboardBoundary 生命周期', () => {
  it('detach 后恢复并停止监听', () => {
    setImeInset(316)
    Object.defineProperty(viewport, 'height', { value: 484 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('484px')
    boundary.detach()
    expect(frame.style.height).toBe('')
    Object.defineProperty(viewport, 'height', { value: 400 })
    viewport._fire('resize')
    expect(frame.style.height).toBe('')
  })

  it('无 [data-dsh-frame] frame：不抛错', () => {
    frame.remove()
    expect(() => boundary.attach()).not.toThrow()
  })

  // 回归（0.14.7 设备实测）：KeyboardBoundary.apply 曾在设备控制台抛
  // "Cannot read properties of undefined (reading 'height')"。真因是判空只写了 `vv === null`，
  // 漏掉 visualViewport 缺席时 `window.visualViewport` 求值为 undefined 的宿主（隔离 WebView / 旧内核）。
  // 该异常会打断 onViewportChange，连带影响同一 effect 链上的移动适配逻辑，故必须钉住。
  it('visualViewport 缺席（undefined）：不抛错，且不误设 frame 高度', () => {
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: undefined })
    setImeInset(316)
    expect(() => boundary.attach()).not.toThrow()
    expect(frame.style.height).toBe('')
  })

  it('visualViewport 为 null：同样不抛错', () => {
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: null })
    setImeInset(316)
    expect(() => boundary.attach()).not.toThrow()
    expect(frame.style.height).toBe('')
  })
})
