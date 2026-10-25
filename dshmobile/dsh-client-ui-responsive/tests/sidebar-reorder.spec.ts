// @vitest-environment jsdom
// 0.14.7 §2.2 侧边栏长按拖动重排：只验证「触摸长按 -> 上游 DnD」的翻译行为。
// 排序语义与持久化属上游（WorkspaceBrowser.commitSessionDrag / setSessionOrder），
// 本模块不得复制 —— 因此这里断言的是「派发了哪些 DnD 事件、落到哪个目标行、什么条件下不介入」。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import React, { useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { flushSync } from 'react-dom'
import {
  REORDER_ATTR, REORDER_SOURCE_ATTR, SidebarReorder,
} from '../src/client/mobile/sidebar-reorder.ts'
import { MobileSidebarGestures } from '../src/client/mobile/sidebar-gestures.ts'

/** jsdom 无 DragEvent；测试里用通用 Event 也能驱动（与实现同一降级路径）。 */
const originalDragEvent = (globalThis as { DragEvent?: unknown }).DragEvent

/**
 * jsdom 不实现 document.elementFromPoint；按行元素自己的 getBoundingClientRect 做命中，
 * 与真实内核在同样几何下会返回的结果一致（本文件里行是唯一的会话行候选）。
 */
function installHitTest(): void {
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: (x: number, y: number): Element | null => {
      for (const element of document.querySelectorAll('[data-row-key^="session:"]')) {
        const rect = element.getBoundingClientRect()
        if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) return element
      }
      return document.body
    },
  })
}

function touch(type: string, x: number, y: number, target: EventTarget, id = 1, activeIds?: number[]): Event {
  const event = new Event(type, { bubbles: true, cancelable: true })
  const point = { identifier: id, clientX: x, clientY: y }
  const active = (activeIds ?? (type === 'touchend' ? [] : [id])).map(identifier => ({ ...point, identifier }))
  Object.defineProperty(event, 'touches', { value: active })
  Object.defineProperty(event, 'changedTouches', { value: [point] })
  target.dispatchEvent(event)
  return event
}

/** 会话行：带上游的 data-row-key 与 draggable（Rows.tsx:589/601）。 */
function row(id: string, top: number, { draggable = true, height = 40 } = {}): HTMLElement {
  const element = document.createElement('div')
  element.setAttribute('data-row-key', `session:${id}`)
  element.setAttribute('role', 'treeitem')
  element.draggable = draggable
  element.getBoundingClientRect = () => ({
    x: 0, y: top, left: 0, right: 200, top, bottom: top + height, width: 200, height,
    toJSON: () => ({}),
  })
  document.body.appendChild(element)
  return element
}

/** 记录一行收到的合成 DnD 事件。 */
function watch(element: HTMLElement): string[] {
  const seen: string[] = []
  for (const type of ['dragstart', 'dragover', 'drop', 'dragend']) {
    element.addEventListener(type, (event) => {
      seen.push(type)
      // dataTransfer 必须由实现补齐，否则上游 onDragStart 会抛（它直接写 effectAllowed）。
      const transfer = (event as Event & { dataTransfer?: { effectAllowed: string } }).dataTransfer
      expect(transfer).toBeDefined()
      expect(transfer?.effectAllowed).toBeDefined()
    })
  }
  return seen
}

let reorder: SidebarReorder

beforeEach(() => {
  document.documentElement.setAttribute('data-dsh-mobile-form', '')
  document.body.innerHTML = ''
  delete (globalThis as { DragEvent?: unknown }).DragEvent
  installHitTest()
  reorder = new SidebarReorder()
  reorder.attach()
})

afterEach(() => {
  reorder.detach()
  document.body.innerHTML = ''
  document.documentElement.removeAttribute('data-dsh-mobile-form')
  document.documentElement.removeAttribute(REORDER_ATTR)
  if (originalDragEvent !== undefined) (globalThis as { DragEvent?: unknown }).DragEvent = originalDragEvent
  vi.restoreAllMocks()
})

describe('长按判定', () => {
  it('按住约 400ms 后进入拖动并派发 dragstart（计时用假定时器，不用固定延迟掩盖）', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const target = row('b', 200)
    const sourceEvents = watch(source)
    const targetEvents = watch(target)

    touch('touchstart', 100, 120, source)
    // 未到时长：绝不能已经进入拖动。
    vi.advanceTimersByTime(399)
    expect(sourceEvents).toEqual([])
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)

    vi.advanceTimersByTime(1)
    expect(sourceEvents).toEqual(['dragstart'])
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(true)
    expect(source.hasAttribute(REORDER_SOURCE_ATTR)).toBe(true)

    // 拖到目标行的下半区 -> dragover 落在目标行；松手 -> drop + dragend。
    touch('touchmove', 100, 230, source)
    expect(targetEvents).toEqual(['dragover'])
    touch('touchend', 100, 230, source)
    expect(targetEvents).toEqual(['dragover', 'drop'])
    expect(sourceEvents).toEqual(['dragstart', 'dragend'])
    // 全部标记撤净。
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    expect(source.hasAttribute(REORDER_SOURCE_ATTR)).toBe(false)
    vi.useRealTimers()
  })

  it('未到时长就抬手：当作普通点击，不派发任何 DnD 事件', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    const clicked = vi.fn()
    source.addEventListener('click', clicked)

    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(200)
    touch('touchend', 100, 120, source)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    expect(source.hasAttribute(REORDER_SOURCE_ATTR)).toBe(false)

    // 点击仍然可用（本模块不拦截事件的默认行为）。
    source.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 }))
    expect(clicked).toHaveBeenCalledOnce()
    vi.useRealTimers()
  })

  it('计时期间位移超过阈值即取消（纵向滚动与横向滑动都不进拖动）', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source)
    touch('touchmove', 100, 145, source)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    touch('touchend', 100, 145, source)
    expect(events).toEqual([])

    // 横向同理：让给 R1 的跟手手势。
    touch('touchstart', 100, 120, source)
    touch('touchmove', 140, 122, source)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    vi.useRealTimers()
  })

  it('惯性滚动抵达时取消待激活的长按（滚动中不算按住）', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source)
    document.dispatchEvent(new Event('scroll', { bubbles: true }))
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    vi.useRealTimers()
  })

  it('多指加入取消待激活的长按', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source, 1, [1])
    touch('touchstart', 120, 130, source, 2, [1, 2])
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    vi.useRealTimers()
  })

  it('touchcancel 取消待激活的长按与进行中的拖动', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source)
    touch('touchcancel', 100, 120, source)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])

    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    expect(events).toEqual(['dragstart'])
    touch('touchcancel', 100, 120, source)
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    expect(source.hasAttribute(REORDER_SOURCE_ATTR)).toBe(false)
    vi.useRealTimers()
  })

  it('非手机形态完全不介入', () => {
    vi.useFakeTimers()
    document.documentElement.removeAttribute('data-dsh-mobile-form')
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    vi.useRealTimers()
  })

  it('不可拖的行（archived / 空白）不进入长按计时，与上游 draggable 语义一致', () => {
    vi.useFakeTimers()
    const locked = row('archived', 100, { draggable: false })
    const events = watch(locked)
    touch('touchstart', 100, 120, locked)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    vi.useRealTimers()
  })

  it('长按前行的卸载（列表刷新）不会对脱离文档的节点派发事件', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source)
    source.remove()
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    vi.useRealTimers()
  })
})

describe('拖动过程', () => {
  it('目标行的插入半区按手指位置取上半/下半（与上游 rowHalf 同口径）', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    // 目标行 200..240，中点 220。
    const target = row('b', 200)
    const halves: number[] = []
    target.addEventListener('dragover', (event) => { halves.push((event as Event & { clientY: number }).clientY) })

    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    touch('touchmove', 100, 210, source)
    expect(halves).toEqual([210])
    // 同一行同一半区不重复派发（避免上游每次 setState）。
    touch('touchmove', 100, 212, source)
    expect(halves).toEqual([210])
    // 换到下半区 -> 再派发一次。
    touch('touchmove', 100, 235, source)
    expect(halves).toEqual([210, 235])
    vi.useRealTimers()
  })

  it('拖动期间拦掉原生滚动（否则列表在手指下滚动会让插入位置漂移）', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    touch('touchstart', 100, 120, source)
    // 激活前不拦：普通纵向滚动必须照常可用。
    const before = touch('touchmove', 100, 124, source)
    expect(before.defaultPrevented).toBe(false)
    vi.advanceTimersByTime(400)
    const during = touch('touchmove', 100, 140, source)
    expect(during.defaultPrevented).toBe(true)
    vi.useRealTimers()
  })

  it('dropping outside settles back on the source before dragend', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    touch('touchmove', 100, 900, source)
    touch('touchend', 100, 900, source)
    expect(events).toEqual(['dragstart', 'drop', 'dragend'])
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    vi.useRealTimers()
  })

  it('松手位置再解析一次目标：最后一次 touchmove 与抬手之间的位移也算', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const target = row('b', 200)
    const events = watch(target)
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    // 手指直接移到目标行上再抬手（中间没有 touchmove）。
    touch('touchend', 100, 230, source)
    expect(events).toEqual(['drop'])
    vi.useRealTimers()
  })
})

describe('与 R1 跟手手势互斥', () => {
  function gestureHarness() {
    const frame = document.createElement('div')
    frame.setAttribute('data-dsh-frame', '')
    frame.setAttribute('data-sidebar-collapsed', '')
    const drawer = document.createElement('aside')
    drawer.className = 'sidebarCol_test'
    frame.appendChild(drawer)
    document.body.appendChild(frame)
    const openLeft = vi.fn(() => frame.removeAttribute('data-sidebar-collapsed'))
    // task-7 起手势源在确认 open-left 意图时会**提前提交**展开状态（让上游挂载完整抽屉内容），
    // 松手不够阈值 / 中途放弃时用 closeLeft 原路收回。这里按真实装配（index.ts）提供它，
    // 否则手势源会走"缺回调则不提前提交"的降级分支，与线上行为不一致。
    const closeLeft = vi.fn(() => frame.setAttribute('data-sidebar-collapsed', ''))
    const gestures = new MobileSidebarGestures({
      mobileForm: () => document.documentElement.hasAttribute('data-dsh-mobile-form'),
      leftOpen: () => !frame.hasAttribute('data-sidebar-collapsed'),
      rightOpen: () => false,
      mainPanelSelected: () => false,
      openLeft,
      closeLeft,
      returnToConversation: vi.fn(),
      openRight: vi.fn(),
      closeRight: vi.fn(),
    })
    gestures.attach()
    return { gestures, openLeft }
  }

  it('重排拖动进行中时，R1 不认领横向手势（抽屉绝不展开）', () => {
    vi.useFakeTimers()
    const { gestures, openLeft } = gestureHarness()
    const source = row('a', 100)
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(true)

    // 拖动中把手指横向拉出很远：R1 若认领会 openLeft。
    // （这里不断言 defaultPrevented：重排拖动自身就是要 preventDefault 拦原生滚动；
    //   互斥的可观测后果是"抽屉没动"。）
    touch('touchmove', 300, 122, source)
    touch('touchend', 300, 122, source)
    expect(openLeft).not.toHaveBeenCalled()
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)

    // 拖动结束后 R1 恢复可用（互斥不是永久禁用）。
    touch('touchstart', 150, 300, document.body)
    touch('touchmove', 300, 302, document.body)
    touch('touchend', 300, 302, document.body)
    expect(openLeft).toHaveBeenCalledOnce()
    gestures.detach()
    vi.useRealTimers()
  })

  it('R1 已经跟手拖动时，长按不再触发重排（方向已被横向手势认领）', () => {
    vi.useFakeTimers()
    const { gestures } = gestureHarness()
    const source = row('a', 300)
    const events = watch(source)
    touch('touchstart', 100, 320, source)
    // 横向超过重排的位移阈值 -> 重排取消。
    touch('touchmove', 140, 322, source)
    vi.advanceTimersByTime(400)
    expect(events).toEqual([])
    gestures.detach()
    vi.useRealTimers()
  })
})

describe('detach 可逆', () => {
  it('detach 后不再介入触摸，且不残留任何标记与监听', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    reorder.detach()
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(1000)
    expect(events).toEqual([])
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    vi.useRealTimers()
  })

  it('拖动中 detach 会立刻撤离标记（不能把 R1 永久锁死）', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(true)
    reorder.detach()
    expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
    expect(source.hasAttribute(REORDER_SOURCE_ATTR)).toBe(false)
    vi.useRealTimers()
  })

  it('attach 幂等：重复 attach 不会重复派发 dragstart', () => {
    vi.useFakeTimers()
    const source = row('a', 100)
    const events = watch(source)
    reorder.attach()
    reorder.attach()
    touch('touchstart', 100, 120, source)
    vi.advanceTimersByTime(400)
    expect(events).toEqual(['dragstart'])
    vi.useRealTimers()
  })
})

// Exercise React batching and the upstream source-drop/no-op contract together.
function reactDragOwner() {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  const writes: string[] = []
  let dragging = false
  function Owner() {
    const [drag, setDrag] = useState<{ source: string; over: string | null } | null>(null)
    const committed = useRef(false)
    dragging = drag !== null
    const commit = (target: string) => {
      if (drag === null || committed.current) return
      committed.current = true
      setDrag(null)
      if (drag.source !== target) writes.push(`${drag.source}->${target}`)
    }
    return React.createElement('div', {}, ...['a', 'b'].map(id => React.createElement('div', {
      key: id, 'data-row-key': `session:${id}`, draggable: true,
      onDragStart: () => { committed.current = false; setDrag({ source: id, over: null }) },
      onDragOver: () => { if (drag !== null) setDrag({ ...drag, over: id }) },
      onDrop: () => commit(id),
      onDragEnd: () => {
        if (drag?.over != null) commit(drag.over)
        else setDrag(null)
        committed.current = false
      },
    }, id)))
  }
  flushSync(() => { root.render(React.createElement(Owner)) })
  const rows = [...container.querySelectorAll<HTMLElement>('[data-row-key]')]
  rows.forEach((element, index) => {
    const top = 100 + index * 100
    element.getBoundingClientRect = () => ({ x: 0, y: top, left: 0, right: 200, top, bottom: top + 40, width: 200, height: 40, toJSON: () => ({}) })
  })
  return { source: rows[0]!, writes, dragging: () => dragging, dispose: () => { flushSync(() => root.unmount()); container.remove() } }
}

describe('React drag ownership and cancellation', () => {
  it.each(['touchcancel', 'second-finger', 'unmatched-end', 'outside', 'detach'])('%s clears ownership without persisting a reorder, then permits a new drag', kind => {
    vi.useFakeTimers()
    const owner = reactDragOwner()
    try {
      touch('touchstart', 100, 120, owner.source)
      vi.advanceTimersByTime(400)
      touch('touchmove', 100, 230, owner.source)
      expect(owner.dragging()).toBe(true)
      if (kind === 'touchcancel') touch('touchcancel', 100, 230, owner.source)
      if (kind === 'second-finger') {
        touch('touchstart', 120, 230, owner.source, 2, [1, 2])
        touch('touchend', 120, 230, owner.source, 2, [1])
      }
      if (kind === 'unmatched-end') touch('touchend', 100, 230, owner.source, 2)
      if (kind === 'outside') touch('touchend', 100, 900, owner.source)
      if (kind === 'detach') reorder.detach()
      expect(owner.writes).toEqual([])
      expect(owner.dragging()).toBe(false)
      expect(document.documentElement.hasAttribute(REORDER_ATTR)).toBe(false)
      reorder.attach()
      touch('touchstart', 100, 120, owner.source)
      vi.advanceTimersByTime(400)
      touch('touchmove', 100, 230, owner.source)
      touch('touchend', 100, 230, owner.source)
      expect(owner.writes).toEqual(['a->b'])
      expect(owner.dragging()).toBe(false)
    } finally {
      owner.dispose()
      vi.useRealTimers()
    }
  })
})

describe('global file-drop interoperability', () => {
  it('exposes text/plain types before the React row drop handler runs', () => {
    vi.useFakeTimers()
    const owner = reactDragOwner()
    const observed: boolean[] = []
    const onDrop = (event: Event) => {
      const transfer = (event as DragEvent).dataTransfer!
      observed.push(transfer.types.includes('Files'))
      expect(transfer.types).toContain('text/plain')
    }
    document.addEventListener('drop', onDrop, true)
    try {
      touch('touchstart', 100, 120, owner.source)
      vi.advanceTimersByTime(400)
      touch('touchmove', 100, 230, owner.source)
      touch('touchcancel', 100, 230, owner.source)
      expect(observed).toEqual([false])
      expect(owner.writes).toEqual([])
      expect(owner.dragging()).toBe(false)
    } finally {
      document.removeEventListener('drop', onDrop, true)
      owner.dispose()
      vi.useRealTimers()
    }
  })
})
