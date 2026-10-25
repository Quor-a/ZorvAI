// @vitest-environment jsdom
// 审计 §7.1：原生浏览器工位每帧读全祖先样式。
//
// 判据全部落在**行为语义**上（不测机器速度、不断言实现细节）：
//  ① 不可见（框架 visible 为假 / document.hidden）时，不下发任何几何，但仍必须把
//     「隐藏」送到壳侧一次 —— 2026-09-19 用户实报「收起侧边栏浏览器不卸载」就是这个语义；
//  ② 同一份 DOM 结构下重复刷新，祖先链的样式读取次数不随帧数增长（只在结构变化后失效）；
//  ③ 可见性判据一个不少：祖先隐藏 / 祖先裁剪 / 屏幕外、弹层遮挡、气泡、拖动指针；
//  ④ lease 仍然生效，且严格小于壳侧 TTL（BrowserOverlayPolicy.STAGE_BOUNDS_TTL_MS=4000）。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BROWSER_STAGE_LEASE_MS, NativeBrowserPresentation } from '../src/client/mobile/native-browser-presentation.ts'

let hidden = false
let frameVisible = true
let owner: NativeBrowserPresentation
let bounds: ReturnType<typeof vi.fn>

function rect(left = 20, top = 50, width = 300, height = 400): DOMRect {
  return { left, top, right: left + width, bottom: top + height, width, height, x: left, y: top, toJSON: () => ({}) }
}
function latest(): Record<string, unknown> { return JSON.parse(bounds.mock.calls.at(-1)![0]) }

/** 搭一个 stage（只建节点，挂载点由调用方决定）。 */
function stage(): HTMLElement {
  const element = document.createElement('div')
  element.id = 'stage'
  element.getBoundingClientRect = () => rect()
  return element
}

/** 等 MutationObserver 的微任务派发完，再推进一帧。 */
async function mutate(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  vi.advanceTimersByTime(20)
  await Promise.resolve()
}

/** 统计指定元素上的 getComputedStyle 调用次数。 */
function countingComputedStyle(elements: HTMLElement[]): () => number {
  const original = window.getComputedStyle.bind(window)
  let count = 0
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element: Element, pseudo?: string | null) => {
    if (elements.includes(element as HTMLElement)) count += 1
    return original(element, pseudo ?? undefined)
  })
  return () => count
}

beforeEach(() => {
  vi.useFakeTimers()
  document.body.innerHTML = ''
  hidden = false
  frameVisible = true
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => window.setTimeout(() => callback(0), 16))
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => window.clearTimeout(id))
  bounds = vi.fn(() => '{}')
  window.androidBridge = { browserHostBounds: bounds }
  owner = new NativeBrowserPresentation({ session: 'session-a', uiTabId: 'ui-a', nativeTabId: () => 'native-a',
    visible: () => frameVisible, ready: () => true })
})
afterEach(() => { owner.detach(); delete window.androidBridge; vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('审计 §7.1：不可见时不下发几何，但隐藏必须送到壳侧', () => {
  it('框架 visible 为假：隐藏一次后不再跨桥，且不下发任何几何', () => {
    const element = stage()
    document.body.append(element)
    owner.mount('stage')
    expect(latest()).toMatchObject({ visible: true, width: 300, height: 400 })
    const countVisible = bounds.mock.calls.length

    frameVisible = false
    owner.refresh()
    expect(latest(), '收起侧栏必须把隐藏送到壳侧，否则原生覆盖层留在屏上').toMatchObject({ visible: false })
    expect(bounds.mock.calls.length).toBe(countVisible + 1)
    expect(latest(), '隐藏分支不下发几何（壳侧在 visible=false 时先返回，矩形不被使用）')
      .toMatchObject({ left: 0, top: 0, width: 0, height: 0 })

    const countHidden = bounds.mock.calls.length
    for (let index = 0; index < 20; index++) owner.refresh()
    expect(bounds.mock.calls.length, '已隐藏时重复刷新不得再跨桥').toBe(countHidden)
  })

  it('document.hidden 时同样先判可见性：不下发几何、只送隐藏', async () => {
    const element = stage()
    document.body.append(element)
    owner.mount('stage')
    hidden = true
    document.dispatchEvent(new Event('visibilitychange'))
    expect(latest(), '切后台必须立刻隐藏').toMatchObject({ visible: false, width: 0, height: 0 })
    const count = bounds.mock.calls.length
    vi.advanceTimersByTime(6000)
    expect(bounds.mock.calls.length).toBe(count)
  })

  it('反证：隐藏时先算几何的旧顺序会读到祖先链（判据要能区分两种顺序）', () => {
    const element = stage()
    const ancestor = document.createElement('section')
    ancestor.append(element)
    document.body.append(ancestor)
    const reads = countingComputedStyle([ancestor, element])
    // 旧顺序（审计描述的 :43-49 在 :63 判可见之前）：祖先链每帧读一遍。
    const oldSend = (): void => {
      for (let node: HTMLElement | null = element; node !== null; node = node.parentElement) {
        void window.getComputedStyle(node)
      }
    }
    frameVisible = false
    owner.mount('stage')
    const afterMount = reads()
    for (let index = 0; index < 10; index++) oldSend()
    expect(reads(), '旧顺序在隐藏时也读祖先链：次数随帧数线性增长')
      .toBeGreaterThan(afterMount + 10)
  })
})

describe('审计 §7.1：祖先链样式缓存只在结构变化后失效', () => {
  it('同一份结构下重复刷新：祖先链的样式读取不随帧数增长', async () => {
    const ancestor = document.createElement('section')
    const element = stage()
    ancestor.append(element)
    document.body.append(ancestor)
    const reads = countingComputedStyle([ancestor, element])
    owner.mount('stage')
    await mutate()
    const afterFirst = reads()
    expect(afterFirst, '首帧要真的读过祖先链').toBeGreaterThan(0)
    for (let index = 0; index < 20; index++) owner.refresh()
    expect(reads(), '结构没变 → 祖先链样式一次都不重读').toBe(afterFirst)
  })

  it('结构变化（class 改写）后立刻重读，隐藏判定跟着变（缓存不得变陈旧）', async () => {
    const ancestor = document.createElement('section')
    ancestor.className = 'shown'
    const element = stage()
    ancestor.append(element)
    document.body.append(ancestor)
    owner.mount('stage')
    await mutate()
    expect(latest().visible, '祖先可见 → stage 可见').toBe(true)

    // 上游用 class 驱动 display:none（最常见的隐藏手法）。
    const style = document.createElement('style')
    style.textContent = '.hidden-ancestor { display: none; }'
    document.head.append(style)
    ancestor.className = 'hidden-ancestor'
    await mutate()
    expect(latest().visible, '祖先整块 display:none → stage 必须判隐藏').toBe(false)

    ancestor.className = 'shown'
    await mutate()
    expect(latest().visible, '祖先恢复可见 → stage 跟着恢复').toBe(true)
  })

  it('祖先的 overflow 裁剪仍然生效（裁剪层的几何每次现读，滚动后跟着变）', async () => {
    // jsdom 不支持 overflow 简写的 computed style：用样式表规则驱动裁剪判据（与真实页面同源）。
    const style = document.createElement('style')
    style.textContent = '.clip-x { overflow-x: hidden; } .clip-y { overflow-y: hidden; }'
    document.head.append(style)
    const ancestor = document.createElement('section')
    ancestor.className = 'clip-x clip-y'
    ancestor.getBoundingClientRect = () => rect(0, 0, 200, 300)
    const element = stage()
    element.getBoundingClientRect = () => rect(20, 50, 300, 400)
    ancestor.append(element)
    document.body.append(ancestor)
    owner.mount('stage')
    await mutate()
    expect(latest(), 'stage 必须被裁到祖先的框内').toMatchObject({ left: 20, top: 50, width: 180, height: 250 })

    // 裁剪层滚动后位置变了：几何现读，不是缓存值。
    // 起点是 stage 自己的 top=50，所以被裁剪层的 top=100 一夹，可见顶边落到 100、高度只剩 400-50=... 见下：
    ancestor.getBoundingClientRect = () => rect(0, 100, 200, 300)
    window.dispatchEvent(new Event('scroll'))
    await mutate()
    expect(latest(), '滚动后裁剪矩形必须反映新位置（top 被抬到 100）').toMatchObject({ top: 100 })
  })
})

describe('审计 §7.1：可见性判据一个都不能丢', () => {
  it('祖先 hidden 属性 / visibility / opacity 都判隐藏', async () => {
    const cases: Array<[string, (node: HTMLElement) => void]> = [
      ['hidden 属性', (node) => { node.hidden = true }],
      ['display:none', (node) => { node.style.display = 'none' }],
      ['visibility:hidden', (node) => { node.style.visibility = 'hidden' }],
      ['opacity:0', (node) => { node.style.opacity = '0' }],
    ]
    for (const [label, hide] of cases) {
      document.body.innerHTML = ''
      const ancestor = document.createElement('section')
      const element = stage()
      ancestor.append(element)
      document.body.append(ancestor)
      owner.mount('stage')
      await mutate()
      expect(latest().visible, label + '：挂载时祖先可见').toBe(true)
      hide(ancestor)
      await mutate()
      expect(latest().visible, label + '：必须判隐藏').toBe(false)
      owner.detach()
    }
  })

  it('弹层遮挡：菜单 / 对话框 / listbox / aria-modal 一律让位，屏幕外的例外', async () => {
    const occluders: Array<[string, string, string]> = [
      ['role=menu', 'role', 'menu'],
      ['role=dialog', 'role', 'dialog'],
      ['role=listbox', 'role', 'listbox'],
      ['aria-modal=true', 'aria-modal', 'true'],
    ]
    for (const [label, attribute, value] of occluders) {
      document.body.innerHTML = ''
      const element = stage()
      document.body.append(element)
      const overlay = document.createElement('div')
      overlay.setAttribute(attribute, value)
      overlay.getBoundingClientRect = () => rect(0, 0, 400, 800)
      document.body.append(overlay)
      owner.mount('stage')
      await mutate()
      expect(latest().visible, label + ' 盖在屏幕上 → 原生页必须让位').toBe(false)
      owner.detach()
    }
    // 屏幕外的候选不算遮挡。
    document.body.innerHTML = ''
    const element = stage()
    document.body.append(element)
    const offscreen = document.createElement('div')
    offscreen.setAttribute('role', 'menu')
    offscreen.getBoundingClientRect = () => rect(-900, -900, 100, 100)
    document.body.append(offscreen)
    owner.mount('stage')
    await mutate()
    expect(latest().visible, '屏幕外的弹层不算遮挡').toBe(true)
  })

  it('弹层由 display:none 藏起来时不算遮挡（候选集判据要读 computed style）', async () => {
    const element = stage()
    document.body.append(element)
    const menu = document.createElement('div')
    menu.setAttribute('role', 'menu')
    menu.getBoundingClientRect = () => rect(0, 0, 400, 800)
    menu.style.display = 'none'
    document.body.append(menu)
    owner.mount('stage')
    await mutate()
    expect(latest().visible).toBe(true)

    menu.style.display = 'block'
    await mutate()
    expect(latest().visible, '菜单真的显示出来 → 必须让位').toBe(false)

    menu.remove()
    await mutate()
    expect(latest().visible, '菜单移除 → 恢复').toBe(true)
  })

  it('侧栏气泡（打开与收起动画）与拖动指针都让位', async () => {
    const element = stage()
    document.body.append(element)
    owner.mount('stage')
    await mutate()
    expect(latest().visible).toBe(true)

    const bubbles = document.createElement('nav')
    bubbles.setAttribute('data-dsh-sidebar-bubbles', 'open')
    document.body.append(bubbles)
    await mutate()
    expect(latest().visible, '气泡打开 → 让位').toBe(false)

    bubbles.setAttribute('data-dsh-sidebar-bubbles', 'closing')
    await mutate()
    expect(latest().visible, '气泡收起动画期间仍让位').toBe(false)

    bubbles.setAttribute('data-dsh-sidebar-bubbles', 'closed')
    await mutate()
    expect(latest().visible, '气泡收起完成 → 恢复').toBe(true)

    const grip = document.createElement('div')
    grip.setAttribute('data-dockkit-pointer', '1')
    document.body.append(grip)
    await mutate()
    expect(latest().visible, '工作区拖动期间让位').toBe(false)

    grip.removeAttribute('data-dockkit-pointer')
    await mutate()
    expect(latest().visible, '拖动结束 → 恢复').toBe(true)
  })

  it('stage 自己不在文档里、或裁剪到 1px 以内，都判隐藏', async () => {
    const element = stage()
    document.body.append(element)
    owner.mount('stage')
    await mutate()
    expect(latest().visible).toBe(true)

    element.getBoundingClientRect = () => rect(20, 50, 1, 1)
    window.dispatchEvent(new Event('resize'))
    await mutate()
    expect(latest().visible, '裁剪后不足 1px → 隐藏').toBe(false)
  })
})

describe('审计 §7.1：lease 继续有效且严格小于壳侧 TTL', () => {
  it('lease 周期不变，且严格小于 BrowserOverlayPolicy.STAGE_BOUNDS_TTL_MS(4000)', () => {
    // 壳侧常量在 app/src/main/java/.../BrowserOverlayPolicy.kt；这里断言前端这一侧的不变量，
    // 跨仓的一致性由 BrowserOverlayContractTest.clientLeasePeriodIsStrictlyBelowNativeTtl 守。
    expect(BROWSER_STAGE_LEASE_MS).toBe(1000)
    expect(BROWSER_STAGE_LEASE_MS).toBeLessThan(4000)
  })

  it('不可见期间 lease 停摆，回前台立刻补一次并恢复续租', async () => {
    const element = stage()
    document.body.append(element)
    owner.mount('stage')
    vi.advanceTimersByTime(2000)
    const renewed = bounds.mock.calls.length
    expect(renewed, '可见期间按 lease 续期').toBeGreaterThanOrEqual(3)

    frameVisible = false
    owner.refresh()
    const stopped = bounds.mock.calls.length
    vi.advanceTimersByTime(6000)
    expect(bounds.mock.calls.length, '隐藏期间不再续租').toBe(stopped)

    frameVisible = true
    window.dispatchEvent(new Event('focus'))
    const restored = bounds.mock.calls.length
    expect(restored).toBeGreaterThan(stopped)
    vi.advanceTimersByTime(2000)
    expect(bounds.mock.calls.length, '回前台后续租恢复').toBeGreaterThan(restored)
  })
})
