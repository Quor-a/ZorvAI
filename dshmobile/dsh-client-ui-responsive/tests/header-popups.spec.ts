// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HeaderPopups } from '../src/client/mobile/header-popups.ts'

const MARKER = 'data-dsh-header-popup'

function flush(): Promise<void> {
  return new Promise(resolve => { requestAnimationFrame(() => { requestAnimationFrame(() => resolve()) }) })
}

function mountMenu(): { fold: HTMLDivElement; trigger: HTMLButtonElement; list: HTMLUListElement } {
  const fold = document.createElement('div')
  fold.setAttribute('data-dsh-topbar-fold', 'open')
  const trigger = document.createElement('button')
  trigger.setAttribute('aria-expanded', 'true')
  const list = document.createElement('ul')
  list.setAttribute('aria-label', '后台任务')
  list.style.setProperty('left', '7px')
  fold.append(trigger, list)
  document.body.append(fold)
  vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({
    x: 460, y: 120, top: 120, bottom: 148, left: 460, right: 500,
    width: 40, height: 28, toJSON: () => ({}),
  } as DOMRect)
  return { fold, trigger, list }
}

describe('手机顶栏内的后台任务浮窗', () => {
  let popup: HeaderPopups | undefined

  afterEach(() => {
    popup?.detach()
    popup = undefined
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('将绝对定位任务菜单提升为屏幕内 fixed 浮窗并保留触发器语义', async () => {
    const { list } = mountMenu()
    popup = new HeaderPopups()
    popup.attach()
    await flush()

    expect(list.getAttribute(MARKER)).not.toBeNull()
    expect(list.style.getPropertyValue('position')).toBe('fixed')
    expect(list.style.getPropertyPriority('position')).toBe('important')
    expect(list.style.getPropertyValue('left')).toBe('460px') // follows the trigger while staying inside the viewport
    expect(list.style.getPropertyValue('top')).toBe('153px')
    expect(list.style.getPropertyValue('max-height')).toBeTruthy()
  })

  it('恢复菜单节点原有的内联定位样式', async () => {
    const { fold, trigger, list } = mountMenu()
    popup = new HeaderPopups()
    popup.attach()
    await flush()
    fold.remove()
    await flush()

    expect(list.hasAttribute(MARKER)).toBe(false)
    expect(list.style.getPropertyValue('left')).toBe('7px')
    expect(list.style.getPropertyValue('position')).toBe('')
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
  })

  it('detach 后忽略已经排队的 animation frame', () => {
    const { list } = mountMenu()
    let pending: FrameRequestCallback | undefined
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
      pending = callback
      return 41
    })
    popup = new HeaderPopups()
    popup.attach()
    popup.detach()
    pending?.(0)

    expect(list.getAttribute(MARKER)).toBeNull()
    expect(list.style.getPropertyValue('position')).toBe('')
  })

  it('触发器贴近视口底部时向上翻转且菜单不越出可视高度', async () => {
    const { trigger, list } = mountMenu()
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({
      x: 460, y: 550, top: 550, bottom: 578, left: 460, right: 500,
      width: 40, height: 28, toJSON: () => ({}),
    } as DOMRect)
    vi.spyOn(list, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 0, top: 0, bottom: 500, left: 0, right: 500,
      width: 500, height: 500, toJSON: () => ({}),
    } as DOMRect)
    Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 500 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 })
    popup = new HeaderPopups()
    popup.attach()
    await flush()

    const top = Number.parseFloat(list.style.getPropertyValue('top'))
    const height = Number.parseFloat(list.style.getPropertyValue('max-height'))
    expect(top).toBe(12)
    expect(top).toBeLessThan(550)
    expect(top + height).toBeLessThanOrEqual(588)
  })

  it('忽略不属于后台任务的列表', async () => {
    const { fold, list } = mountMenu()
    list.setAttribute('aria-label', 'Agent Team')
    popup = new HeaderPopups()
    popup.attach()
    await flush()

    expect(fold.hasAttribute('data-dsh-header-popup')).toBe(false)
    expect(list.getAttribute(MARKER)).toBeNull()
  })
})
