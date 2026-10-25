// @vitest-environment jsdom
// 审计 §7.9：顶栏折叠每帧重写样式。
//
// 判据落在**可观测的 DOM 写入**上（不测机器速度）：
//  ① 折叠状态没变时，一批 DOM 变更不得重写容器属性 / 定位属性 / 标题行标记；
//  ② open 边沿（点箭头、点外部、Esc）必须照旧写入 —— 合帧不能吞掉状态变化；
//  ③ 容器换新（会话切换 / 上游重挂）也必须重写，否则属性写在旧容器上等于没写；
//  ④ measure() 读后写：一次测量里没有「写一个属性再读一次布局」的交错。
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { TopBarFold } from '../src/client/mobile/top-bar-fold.ts'

const ACTIONS_SELECTOR = 'div[data-slot="conversation.session.header.actions"]'
const ARROW_ATTR = 'data-dsh-topbar-fold-arrow'
const FOLD_ATTR = 'data-dsh-topbar-fold'

/** 与上游同形的标题栏。 */
function buildHeader(plugins = 2): HTMLElement {
  document.body.innerHTML = ''
  const header = document.createElement('header')
  header.setAttribute('data-window-drag', '')
  const leading = document.createElement('div')
  leading.setAttribute('data-conversation-header-leading', '')
  header.appendChild(leading)
  const sessionSeat = document.createElement('div')
  sessionSeat.setAttribute('data-slot', 'conversation.session.header')
  const titleRow = document.createElement('div')
  titleRow.className = 'titleRow'
  sessionSeat.appendChild(titleRow)
  const actions = document.createElement('div')
  actions.className = 'headerActions'
  const seat = document.createElement('div')
  seat.setAttribute('data-slot', 'conversation.session.header.actions')
  for (let index = 0; index < plugins; index++) {
    const node = document.createElement('span')
    node.setAttribute('data-plugin-node', String(index))
    seat.appendChild(node)
  }
  actions.appendChild(seat)
  titleRow.appendChild(actions)
  const tabs = document.createElement('div')
  tabs.setAttribute('role', 'tablist')
  tabs.setAttribute('data-conversation-tabs', '')
  header.appendChild(sessionSeat)
  header.appendChild(tabs)
  document.body.appendChild(header)
  return header
}

/** 复制一份 DOMRect（jsdom 的 rect 不可写，测里要改字段）。 */
function rectOf(rect: DOMRect): { left: number, top: number, right: number, bottom: number, width: number, height: number } {
  return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height }
}

/** 等折叠层的 rAF 同步跑完。 */
function flush(): Promise<void> {
  return new Promise(resolve => { requestAnimationFrame(() => { requestAnimationFrame(() => { resolve() }) }) })
}

let fold: TopBarFold

beforeEach(() => {
  document.documentElement.setAttribute('data-dsh-mobile-form', '')
  buildHeader()
  fold = new TopBarFold()
})

afterEach(() => {
  fold.detach()
  document.documentElement.removeAttribute('data-dsh-mobile-form')
  document.body.innerHTML = ''
})

describe('审计 §7.9：样式写入只在 open 边沿', () => {
  it('状态没变时，DOM 变更不重写容器属性、定位属性与标题行标记', async () => {
    fold.attach()
    await flush()
    const seat = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    const titleRow = document.querySelector<HTMLElement>('.titleRow')!
    const setAttribute = vi.spyOn(seat, 'setAttribute')
    const removeAttribute = vi.spyOn(seat, 'removeAttribute')
    const styleWrite = vi.spyOn(seat.style, 'setProperty')
    const rowWrite = vi.spyOn(titleRow, 'setAttribute')

    // 稳态流式渲染：座位里不断挂上新节点（真实形状）。
    for (let round = 0; round < 10; round++) {
      const extra = document.createElement('span')
      extra.setAttribute('data-plugin-node', 'x' + String(round))
      seat.appendChild(extra)
      await flush()
    }
    expect(setAttribute.mock.calls.filter(([name]) => name === FOLD_ATTR), '折叠属性不得被重写')
      .toHaveLength(0)
    expect(removeAttribute.mock.calls.filter(([name]) => name === FOLD_ATTR)).toHaveLength(0)
    expect(styleWrite.mock.calls.length, '收起态没有任何定位属性可写').toBe(0)
    expect(rowWrite.mock.calls.filter(([name]) => name === 'data-dsh-topbar-fold-uncontained'),
      '标题行标记不得被重写').toHaveLength(0)
    setAttribute.mockRestore()
    removeAttribute.mockRestore()
    styleWrite.mockRestore()
    rowWrite.mockRestore()
  })

  it('反证：每帧无条件 applyOpen() 的旧形状会重写 10 次', async () => {
    // 旧形状：sync() 每轮都调 applyOpen()，里面无条件 setAttribute + 七个 style 写入。
    const seat = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    let writes = 0
    const oldApplyOpen = (): void => {
      seat.setAttribute(FOLD_ATTR, 'closed')
      for (const name of ['position', 'left', 'right', 'top', 'bottom', 'z-index']) {
        seat.style.setProperty(name, '')
        writes += 1
      }
    }
    for (let round = 0; round < 10; round++) {
      const extra = document.createElement('span')
      seat.appendChild(extra)
      oldApplyOpen() // 每批 mutation 都跑一遍
      await flush()
    }
    expect(writes, '旧形状：10 批变更 = 60 次样式写入').toBe(60)
  })

  it('open 边沿照旧写入：点箭头展开、点外部收起', async () => {
    fold.attach()
    await flush()
    const seat = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    const titleRow = document.querySelector<HTMLElement>('.titleRow')!
    const arrow = document.querySelector<HTMLElement>('[' + ARROW_ATTR + ']')!
    expect(seat.getAttribute(FOLD_ATTR)).toBe('closed')

    arrow.click()
    expect(seat.getAttribute(FOLD_ATTR), '展开必须写').toBe('open')
    expect(titleRow.hasAttribute('data-dsh-topbar-fold-uncontained')).toBe(true)

    document.body.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    expect(seat.getAttribute(FOLD_ATTR), '收起必须写').toBe('closed')
    expect(titleRow.hasAttribute('data-dsh-topbar-fold-uncontained')).toBe(false)
  })

  it('容器换新（会话切换）后必须重写：新容器上也要有折叠属性', async () => {
    fold.attach()
    await flush()
    const first = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    expect(first.getAttribute(FOLD_ATTR)).toBe('closed')

    // 上游重挂标题栏：整块换成一个新容器。
    const header = buildHeader(3)
    await flush()
    const second = header.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    expect(second).not.toBe(first)
    expect(second.getAttribute(FOLD_ATTR), '新容器必须重新写入折叠状态').toBe('closed')
    expect(document.querySelectorAll('[' + ARROW_ATTR + ']'), '箭头不得重复注入').toHaveLength(1)
  })
})

describe('审计 §7.9：measure() 读后写分离', () => {
  it('一次 measure() 内部：所有读取都排在所有写入之前', async () => {
    fold.attach()
    await flush()
    const seat = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    const arrow = document.querySelector<HTMLElement>('[' + ARROW_ATTR + ']')!
    const header = document.querySelector('header')!
    arrow.click() // 打开态：measure() 才有活干
    await flush()

    const events: string[] = []
    const originalRect = header.getBoundingClientRect
    // 让几何**真的变了**：不这样的话 measure() 会因为值没变而一次都不写，测不到写入顺序。
    header.getBoundingClientRect = () => { events.push('read'); return { ...rectOf(originalRect.call(header)), bottom: 140 } as DOMRect }
    const originalWrite = seat.style.setProperty
    seat.style.setProperty = (name: string, value: string | null, priority?: string) => {
      events.push('write:' + name)
      return originalWrite.call(seat.style, name, value ?? '', priority)
    }
    ;(fold as unknown as { measure: () => void }).measure()

    const order = events.join(',')
    const firstWrite = events.findIndex(event => event.startsWith('write:'))
    expect(firstWrite, '几何变了就必须写，实际顺序 = ' + order).toBeGreaterThan(-1)
    expect(events.filter(event => event === 'read').length, '测量必须真的读过几何').toBeGreaterThan(0)
    expect(events.slice(firstWrite).some(event => event === 'read'),
      '写阶段里不得再出现布局读取，实际顺序 = ' + order).toBe(false)

    header.getBoundingClientRect = originalRect
    seat.style.setProperty = originalWrite
  })

  it('反证：旧形状逐属性交替写读，写之后仍读布局', async () => {
    // 旧 measure()：每写一个属性就夹着下一次读取的输入（客户端宽、视口高）。
    const seat = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    const header = document.querySelector('header')!
    const events: string[] = []
    const originalRect = header.getBoundingClientRect
    header.getBoundingClientRect = () => { events.push('read'); return originalRect.call(header) }
    const originalWrite = seat.style.setProperty
    seat.style.setProperty = (name: string, value: string | null, priority?: string) => {
      events.push('write:' + name)
      return originalWrite.call(seat.style, name, value ?? '', priority)
    }
    // 旧顺序：写 top → 读 rect → 写 left → 读 rect → 写 right
    seat.style.setProperty('top', '10px')
    header.getBoundingClientRect()
    seat.style.setProperty('left', '12px')
    header.getBoundingClientRect()
    seat.style.setProperty('right', '12px')
    const firstWrite = events.findIndex(event => event.startsWith('write:'))
    expect(events.slice(firstWrite).some(event => event === 'read'),
      '旧顺序在写之后继续读布局（强制重排）').toBe(true)
    header.getBoundingClientRect = originalRect
    seat.style.setProperty = originalWrite
  })

  it('几何没变就不写定位属性（稳态一次都不写）', async () => {
    fold.attach()
    await flush()
    const seat = document.querySelector<HTMLElement>(ACTIONS_SELECTOR)!
    const arrow = document.querySelector<HTMLElement>('[' + ARROW_ATTR + ']')!
    arrow.click()
    await flush()
    const firstTop = seat.style.getPropertyValue('top')
    expect(firstTop).not.toBe('')

    const writes: string[] = []
    const originalWrite = seat.style.setProperty
    seat.style.setProperty = (name: string, value: string | null, priority?: string) => {
      writes.push(name)
      return originalWrite.call(seat.style, name, value ?? '', priority)
    }
    // 打开态下的稳态流式渲染：几何没变，一次都不该写。
    for (let round = 0; round < 5; round++) {
      seat.appendChild(document.createElement('span'))
      await flush()
    }
    expect(writes, '值没变时不得写样式').toEqual([])
    expect(seat.style.getPropertyValue('top')).toBe(firstTop)
    seat.style.setProperty = originalWrite
  })
})
