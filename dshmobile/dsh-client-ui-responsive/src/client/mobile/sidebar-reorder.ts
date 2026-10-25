import { flushSync } from 'react-dom'

/**
 * 手机侧边栏会话行的长按拖动重排（0.14.7 / Spec §2.2）。
 *
 * ## 为什么不自己实现排序
 * 上游 ui-workspace 已有完整、正确的重排语义：`ui-workspace/rows/Rows.tsx` 的会话行带
 * `data-row-key="session:<id>"` 与 `draggable`，其 `onDragStart/onDragOver/onDrop/onDragEnd`
 * 转交给 `WorkspaceBrowser` 的 `dragProps`；`WorkspaceBrowser.tsx` 的 `commitSessionDrag`
 * 负责「同 account、同 pinned 块、非 archived」的判定，跨项目拖动被 `sessionDragOrder` 直接
 * 拒绝，顺序经 `setSessionOrder` 落盘。本模块**只做触摸到 DnD 的翻译**：不复制排序状态、
 * 不写持久化、不判跨项目 —— 否则就是第二套排序真源，必然与上游语义漂移。
 *
 * ## 为什么必须翻译
 * The adapter owns a predictable touch lifecycle and delegates ordering to the
 * upstream React row handlers. Android WebView may also initiate native HTML
 * dragging after a long press; that competing default must be cancelled before
 * it terminates the touch stream. Use a native DataTransfer when available and
 * include transfer types in the fallback for global file-drop listeners.
 *
 * ## 与 R1 的互斥
 * 拖动重排期间在 `<html>` 上打 REORDER_ATTR；sidebar-gestures.ts（R1 实时跟手）在 touchstart
 * 即据此拒绝认领，因此重排拖动绝不会展开/收起抽屉。反向无需处理：R1 一旦确认横向手势就会
 * preventDefault，而长按判定在位移超过 MOVE_SLOP_PX 时已取消。
 *
 * ## 判定
 * 未接入侧开时仅手机形态；接入侧开后横竖屏均可用。触点须落在会话行或可侧开的子代理行；计时约
 * 400ms；期间明显位移、横向滑动、列表滚动、多指加入一律取消。**未激活前不注册任何会拦截
 * 事件的监听**，因此普通点击与纵向滚动完全不受影响。
 */

/** 手机形态标记（mobile/form-marker.ts 的单一来源）。 */
const MOBILE_FORM_ATTR = 'data-dsh-mobile-form'
/** 会话行（Rows.tsx:589 的 `data-row-key={`session:${node.id}`}`）。 */
const SESSION_ROW_SELECTOR = '[data-row-key^="session:"]'
const SOURCE_SELECTOR = SESSION_ROW_SELECTOR + ', [role="treeitem"]'
/** 会话行的 rowKey 前缀，与上游同一字面量。 */
const SESSION_ROW_PREFIX = 'session:'
/** 激活拖动时打在 <html> 上的标记：R1 手势据此互斥，CSS 据此给可见反馈。 */
export const REORDER_ATTR = 'data-dsh-sidebar-reorder'
/** 被拖动源行的标记（CSS 反馈用）。 */
export const REORDER_SOURCE_ATTR = 'data-dsh-reorder-source'
/** 长按激活时长（Spec §2.2「约 400ms」）。 */
const LONG_PRESS_MS = 400
/** 激活前允许的手指抖动；超过即视为滚动/滑动，不进入拖动。 */
const MOVE_SLOP_PX = 10

/** 上游 onDragStart 写入的格式（Rows.tsx:606 的 `text/plain`）。 */
const DRAG_FORMAT = 'text/plain'

/** Transfer metadata is shared by row handlers and document-level file-drop listeners. */
interface DragTransfer {
  /** Global file-drop listeners also inspect this event before React handles it. */
  readonly types: readonly string[]
  /** Rows.tsx:605 `effectAllowed = 'move'`。 */
  effectAllowed: string
  /** Rows.tsx:615 `dropEffect = 'move'`。 */
  dropEffect: string
  /** Rows.tsx:606 `setData('text/plain', node.id)`。 */
  setData(format: string, data: string): void
  /** 上游目前不读，但补全后拿到的必须是 onDragStart 写进去的那个 id。 */
  getData(format: string): string
}

/** 挂到事件上的最小 dataTransfer；初值是源会话 id，setData 会覆盖它。 */
function createTransfer(sourceId: string): DragTransfer {
  if (typeof DataTransfer === 'function') {
    const transfer = new DataTransfer()
    transfer.setData(DRAG_FORMAT, sourceId)
    return transfer
  }
  let payload = sourceId
  return {
    types: [DRAG_FORMAT],
    effectAllowed: 'none',
    dropEffect: 'none',
    setData(format: string, data: string): void { if (format === DRAG_FORMAT) payload = data },
    getData(format: string): string { return format === DRAG_FORMAT ? payload : '' },
  }
}

function findTouch(touches: TouchList, id: number): Touch | undefined {
  for (let index = 0; index < touches.length; index += 1) {
    const touch = touches[index]
    if (touch?.identifier === id) return touch
  }
  return undefined
}

/**
 * 构造合成 DnD 事件。
 *
 * 优先用真实 `DragEvent`（内核提供时事件类型正确）；缺失时退回通用 `Event` —— React 按事件
 * 名做委托，条目名字与事件对象无关，因此退回后上游 handler 仍然收到。
 */
function dragEvent(type: string, transfer: DragTransfer, clientY: number): Event {
  const init: EventInit = { bubbles: true, cancelable: true }
  const event: Event = typeof DragEvent === 'function' ? new DragEvent(type, init) : new Event(type, init)
  Object.defineProperty(event, 'dataTransfer', { value: transfer })
  Object.defineProperty(event, 'clientY', { value: clientY })
  return event
}

/** Each synthetic phase must observe the React state committed by its predecessor. */
function dispatchDrag(row: HTMLElement, type: string, transfer: DragTransfer, clientY: number): void {
  flushSync(() => { row.dispatchEvent(dragEvent(type, transfer, clientY)) })
}

/** 行的插入半区，与上游 `rowHalf`（Rows.tsx:200）同一口径：行上半 -> before。 */
function rowHalf(row: Element, clientY: number): 'before' | 'after' {
  const rect = row.getBoundingClientRect()
  return clientY < rect.top + rect.height / 2 ? 'before' : 'after'
}

/** 长按待激活态：手指按下到激活（或被取消）之间的全部信息。 */
interface PendingPress { id: number; x: number; y: number; source: HTMLElement }

/** 拖动中的状态：源行，以及上次派发过 dragover 的目标与半区（用于抑制重复派发）。 */
interface ActiveDrag { id: number; source: HTMLElement; over: HTMLElement | null; half: 'before' | 'after' | null }

/**
 * 触摸长按到上游 DnD 的翻译器。
 *
 * `attach()` / `detach()` 幂等可逆（与 TopBarFold / NewSessionDrawer 同风格）：detach 撤掉
 * 全部监听、计时器、根标记与源行标记，行为回到从未挂载的状态。
 */
export class SidebarReorder {
  private attached = false
  private pending: PendingPress | null = null
  private timer: number | null = null
  private active: ActiveDrag | null = null

  constructor(private readonly dock?: {
    accepts(row: HTMLElement): boolean
    preview(active: boolean, over: boolean): void
    drop(row: HTMLElement): void
    capture?(row: HTMLElement): () => void
  }) {}

  private inDropZone(x: number, y: number): boolean {
    return x >= window.innerWidth - 48 && x <= window.innerWidth && y >= 0 && y <= window.innerHeight
  }

  /** 开始监听触摸；重复调用无副作用。 */
  attach(): void {
    if (this.attached) return
    this.attached = true
    document.addEventListener('touchstart', this.onTouchStart, { capture: true, passive: true })
    // 位移取消与拖动期间的 dragover 都需要 preventDefault（阻止原生滚动），必须非 passive。
    document.addEventListener('touchmove', this.onTouchMove, { capture: true, passive: false })
    document.addEventListener('touchend', this.onTouchEnd, true)
    document.addEventListener('touchcancel', this.onTouchCancel, true)
    document.addEventListener('scroll', this.onScroll, { capture: true, passive: true })
    document.addEventListener('dragstart', this.onNativeDragStart, true)
    document.addEventListener('contextmenu', this.onContextMenu, true)
  }

  /** 清理监听、计时器与全部标记；重复调用无副作用。 */
  detach(): void {
    if (!this.attached) return
    this.attached = false
    document.removeEventListener('touchstart', this.onTouchStart, true)
    document.removeEventListener('touchmove', this.onTouchMove, true)
    document.removeEventListener('touchend', this.onTouchEnd, true)
    document.removeEventListener('touchcancel', this.onTouchCancel, true)
    document.removeEventListener('scroll', this.onScroll, true)
    document.removeEventListener('dragstart', this.onNativeDragStart, true)
    document.removeEventListener('contextmenu', this.onContextMenu, true)
    this.cancelPending()
    this.cancelDrag()
  }

  private readonly onTouchStart = (event: TouchEvent): void => {
    this.cancelPending()
    this.cancelDrag()
    if ((!this.dock && !document.documentElement.hasAttribute(MOBILE_FORM_ATTR)) || event.touches.length !== 1) return
    const touch = event.touches[0]
    if (touch === null) return
    const target = event.target
    if (!(target instanceof Element)) return
    const row = target.closest(SOURCE_SELECTOR)
    // 排序仍只接管 draggable 会话行；侧开还允许归档会话与子代理，禁止认领工作区行。
    if (!(row instanceof HTMLElement) || (!row.matches(SESSION_ROW_SELECTOR) && !this.dock?.accepts(row)) || (row.draggable !== true && !this.dock?.accepts(row))) return
    this.pending = { id: touch.identifier, x: touch.clientX, y: touch.clientY, source: row }
    this.timer = window.setTimeout(this.onLongPress, LONG_PRESS_MS)
  }

  private readonly onTouchMove = (event: TouchEvent): void => {
    const active = this.active
    if (active !== null) {
      if (event.touches.length !== 1) { this.cancelDrag(); return }
      const touch = findTouch(event.touches, active.id)
      if (touch === undefined) return
      // 拖动期间必须拦掉原生滚动：列表一旦在手指下滚动，elementFromPoint 命中的行就会漂移，
      // 插入位置会跟着乱跳。这里拦的是触摸事件本身（上游对合成 drag 事件的 preventDefault
      // 不影响原生滚动手势）。
      event.preventDefault()
      this.dock?.preview(true, this.inDropZone(touch.clientX, touch.clientY))
      if (active.source.matches(SESSION_ROW_SELECTOR) && active.source.draggable) this.trackOver(touch, active)
      return
    }
    const pending = this.pending
    if (pending === null) return
    if (event.touches.length !== 1) {
      this.cancelPending()
      return
    }
    const touch = findTouch(event.touches, pending.id)
    if (touch === undefined) return
    // 激活前的明显位移一律取消：纵向是滚动，横向留给 R1 的跟手手势（两者必须互斥）。
    const travel = Math.max(Math.abs(touch.clientX - pending.x), Math.abs(touch.clientY - pending.y))
    if (travel > MOVE_SLOP_PX) this.cancelPending()
  }

  private readonly onTouchEnd = (event: TouchEvent): void => {
    const pending = this.pending
    if (pending !== null) {
      // 未到 400ms 就抬手：这是一次普通点击，本模块不做任何事，上游 onClick 照常打开会话。
      const matched = findTouch(event.changedTouches, pending.id) !== undefined
      this.cancelPending()
      if (!matched) return
    }
    const active = this.active
    if (active === null) return
    const touch = findTouch(event.changedTouches, active.id)
    if (event.touches.length !== 0 || touch === undefined) { this.cancelDrag(); return }
    event.preventDefault()
    if (this.dock && this.inDropZone(touch.clientX, touch.clientY)) {
      const source = active.source
      const open = source.isConnected ? this.dock.capture?.(source) ?? (() => this.dock?.drop(source)) : undefined
      this.cancelDrag()
      open?.()
      return
    }
    if (!active.source.matches(SESSION_ROW_SELECTOR) || !active.source.draggable) { this.cancelDrag(); return }
    // 落点按抬手位置再解析一次：最后一次 touchmove 到抬手之间可能还有位移。
    const finalRow = this.rowAt(touch.clientX, touch.clientY)
    if (finalRow === null) { this.cancelDrag(); return }
    active.over = finalRow
    active.half = rowHalf(finalRow, touch.clientY)
    const clientY = touch.clientY
    const transfer = createTransfer('')
    // drop -> dragend 与原生顺序一致：上游 drop 落盘，dragend 收尾（其 end 有幂等守卫）。
    if (active.over !== null && active.half !== null) {
      dispatchDrag(active.over, 'drop', transfer, clientY)
    }
    dispatchDrag(active.source, 'dragend', transfer, clientY)
    this.endDrag()
  }

  private readonly onTouchCancel = (): void => {
    this.cancelPending()
    this.cancelDrag()
  }

  /** 列表滚动（含惯性）说明手指不在「按住」语义上：取消尚未激活的长按。 */
  private readonly onScroll = (event: Event): void => {
    const pending = this.pending
    if (pending === null) return
    // Titles marquee horizontally on hover; only scrolling a source ancestor
    // moves the list under the finger. Descendant scroll is presentation noise.
    const target = event.target
    if (!(target instanceof Node) || (target !== pending.source && target.contains(pending.source))) this.cancelPending()
  }

  /** 长按达成：进入拖动并派发 dragstart。 */
  private readonly onLongPress = (): void => {
    const pending = this.pending
    this.pending = null
    this.timer = null
    if (pending === null || (!this.dock && !document.documentElement.hasAttribute(MOBILE_FORM_ATTR))) return
    // 计时期间行可能已卸载（会话切换 / 列表刷新）：对脱离文档的节点派发事件没有意义。
    if (!pending.source.isConnected || (pending.source.draggable !== true && !this.dock?.accepts(pending.source))) return
    const rowKey = pending.source.getAttribute('data-row-key') ?? ''
    const sourceId = rowKey.startsWith(SESSION_ROW_PREFIX) ? rowKey.slice(SESSION_ROW_PREFIX.length) : ''
    this.active = { id: pending.id, source: pending.source, over: null, half: null }
    document.documentElement.setAttribute(REORDER_ATTR, '')
    pending.source.setAttribute(REORDER_SOURCE_ATTR, '')
    this.dock?.preview(true, false)
    if (pending.source.matches(SESSION_ROW_SELECTOR) && pending.source.draggable) dispatchDrag(pending.source, 'dragstart', createTransfer(sourceId), pending.y)
  }

  /** 按手指位置解析目标行并派发 dragover；只在目标行或半区变化时派发（上游每次都会 setState）。 */
  private trackOver(touch: Touch, active: ActiveDrag): void {
    const row = this.rowAt(touch.clientX, touch.clientY)
    if (row === null) {
      active.over = null
      active.half = null
      return
    }
    const half = rowHalf(row, touch.clientY)
    // 目标行与半区都没变就不重复派发：上游的 onDragOver 每次都 setDrag（一次 React 重渲）。
    if (active.over === row && active.half === half) return
    active.over = row
    active.half = half
    dispatchDrag(row, 'dragover', createTransfer(''), touch.clientY)
  }

  /** 命中手指位置下的会话行；不在任何行上时返回 null。 */
  private rowAt(x: number, y: number): HTMLElement | null {
    const hit = document.elementFromPoint(x, y)?.closest(SESSION_ROW_SELECTOR) ?? null
    return hit instanceof HTMLElement ? hit : null
  }

  private cancelPending(): void {
    if (this.timer !== null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    this.pending = null
  }

  /** Android WebView may start its own HTML drag after our long-press timer.
   * Cancel that competing native default before it cancels the touch stream. */
  private readonly onNativeDragStart = (event: DragEvent): void => {
    if (!event.isTrusted || !this.ownsRow(event.target)) return
    event.preventDefault()
    event.stopPropagation()
  }

  private readonly onContextMenu = (event: MouseEvent): void => {
    if (this.ownsRow(event.target)) event.preventDefault()
  }

  private ownsRow(target: EventTarget | null): boolean {
    const source = this.active?.source ?? this.pending?.source
    return source !== undefined && target instanceof Element && target.closest(SOURCE_SELECTOR) === source
  }

  /** A source-row drop clears upstream state without persisting a new order.
   * Upstream dragend alone commits its last hover, so cancellation must first
   * settle through commitSessionDrag's source-id no-op. React must flush that
   * settlement before dragend runs against the updated state. */
  private cancelDrag(): void {
    const active = this.active
    if (active === null) return
    const key = active.source.getAttribute('data-row-key')
    const source = active.source.isConnected ? active.source
      : [...document.querySelectorAll<HTMLElement>(SESSION_ROW_SELECTOR)].find(row => row.getAttribute('data-row-key') === key)
    try {
      if (source !== undefined && active.source.matches(SESSION_ROW_SELECTOR) && active.source.draggable) {
        dispatchDrag(source, 'drop', createTransfer(''), 0)
        dispatchDrag(source, 'dragend', createTransfer(''), 0)
      }
    } finally {
      this.endDrag()
    }
  }

  /** 撤离拖动：撤根标记与源行标记（漏撤会让 R1 永久拒绝手势、CSS 反馈永久留着）。 */
  private endDrag(): void {
    const active = this.active
    if (active === null) return
    this.active = null
    this.dock?.preview(false, false)
    document.documentElement.removeAttribute(REORDER_ATTR)
    active.source.removeAttribute(REORDER_SOURCE_ATTR)
  }
}
