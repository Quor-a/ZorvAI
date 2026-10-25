/**
 * 手机形态下「点新建会话」后把抽屉放下（issue #349）。
 *
 * 现场：从某个会话打开侧边栏，点「新建会话」，抽屉一直张着并停在侧栏，用户要再手动点一次
 * 才回到会话界面（设备实测：点后 400/1500/3000ms 采样 data-sidebar-collapsed 恒缺省）。
 *
 * 真因不在上游 startSession（它确实换了会话），而在**这次切换不产生任何会收起抽屉的通知**：
 * - 上游 `uiWorkspace.startSession()` → `replaceMain(..., 'reveal')`，其中
 *   `if (panel === 'reveal') ctx.layout.selectPanel(null)`（navigation.ts:403）是唯一的相关副作用；
 * - 当前停在会话时 activePanelId 本来就是 null，`selectPanel(null)` 因此**不发通知**；
 * - 本插件收起抽屉的唯一通路 `PanelNavDrawer` 只认「null → panel」那一次转变
 *   （panel-nav-drawer.ts:54，为「进面板时抽屉挡住返回控件」而写），
 * - 手势那条 `returnToConversation` 只在手势里被调用。
 * 于是没有任何人收起抽屉。竖屏新会话键盘弹出时抽屉更是唯一的可见界面，用户就困在侧栏。
 *
 * 修法边界（按 task-4 硬要求）：这里只在「用户点新建会话且抽屉此刻张着」时收起抽屉，
 * 不监听会话切换本身（否则用户手动拉开的抽屉会被别的切换顶掉），
 * 非手机形态完全不介入（横屏停靠侧栏是常驻外框，收起来反而改变了布局）。
 * 键盘：收起抽屉并 blur 掉当前可编辑焦点（与 sidebar-gestures.ts 的 dismissSoftKeyboard 同判据：
 * 软键盘必然由可编辑焦点拉起，故不依赖壳侧 IME 变量）。
 */

/** 手机形态标记（mobile/form-marker.ts）；非手机形态本模块完全不介入。 */
const MOBILE_FORM_ATTR = 'data-dsh-mobile-form'
/** 外框根，抽屉的开合状态写在它上面。 */
const FRAME_SELECTOR = '[data-dsh-frame]'
/** 外框属性：存在即抽屉已收起。 */
const SIDEBAR_COLLAPSED_ATTR = 'data-sidebar-collapsed'
/** 上游左侧栏自己的「新建会话」按钮（ui-sidebar SidebarRoot.tsx:264 `css.newSession`）。 */
const NEW_SESSION_SELECTOR = 'button[class*="newSession"]'
/** 左侧栏列（抽屉本体），用于把「这个按钮来自抽屉」与页内其它按钮区分开。 */
const SIDEBAR_COL_SELECTOR = '[class*="sidebarCol"]'

/** 本模块需要的依赖（全部由调用方注入，便于单测）。 */
export interface NewSessionDrawerDeps {
  /** 抽屉是否处于收起状态；由外框根属性判定。 */
  drawerCollapsed: () => boolean
  /** 收起抽屉（复用布局服务自己的 toggle 动作）。 */
  collapseDrawer: () => void
  /** 当前是否手机形态。 */
  mobileForm: () => boolean
}

/**
 * 命中「抽屉里的新建会话按钮」时返回该按钮，否则返回 undefined。
 * 判据用 DOM 事实而非文案：按钮类是上游 css.newSession（CSS Modules 保留原名），
 * 且它必须在左侧栏列内 —— 页头还有一个同 aria-label 的品牌按钮（css.brand），
 * 那不是新建会话入口，不能误判。
 * @param target - 事件目标。
 * @returns 命中的按钮，或 undefined。
 */
function newSessionButtonOf(target: EventTarget | null): Element | undefined {
  if (!(target instanceof Element)) return undefined
  const button = target.closest(NEW_SESSION_SELECTOR)
  if (button === null) return undefined
  return button.closest(SIDEBAR_COL_SELECTOR) === null ? undefined : button
}

/**
 * 收起软键盘。判据是 activeElement 可编辑（软键盘必然由它拉起）。
 * @param doc - 取焦点的文档。
 */
function dismissSoftKeyboard(doc: Document): void {
  const active = doc.activeElement
  const editable = active instanceof HTMLElement
    && (active.isContentEditable || active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')
  if (!editable) return
  active.blur()
}

/**
 * 监听一次点击：抽屉张着 + 手机形态 + 点的是抽屉里的新建会话时，收起抽屉并收键盘。
 *
 * 监听在冒泡期且不拦截事件：上游自己的 startSession 必须照常执行（换会话是它的职责），
 * 本模块只补上它没做的那一步。事件已被别人 stopPropagation 时（compat 的捕获期拦截等）
 * 本监听收不到，那种情况下的点击本来也不会上游处理，属于另一条链路。
 */
export class NewSessionDrawer {
  private readonly onClick = (event: MouseEvent): void => {
    if (newSessionButtonOf(event.target) === undefined) return
    if (!this.deps.mobileForm()) return
    // 已经收起：什么都不做。不能调 toggle 把它反开（与 panel-nav-drawer.ts:59 同一考虑）。
    if (this.deps.drawerCollapsed()) return
    this.deps.collapseDrawer()
    dismissSoftKeyboard(document)
  }

  /**
   * @param deps - 抽屉状态、收起动作与形态判据。
   * @param doc - 监听所在的文档（测试可注入）。
   */
  constructor(
    private readonly deps: NewSessionDrawerDeps,
    private readonly doc: Document = document,
  ) {}

  /** 开始监听（幂等）。 */
  attach(): void {
    this.doc.addEventListener('click', this.onClick)
  }

  /** 停止监听。 */
  detach(): void {
    this.doc.removeEventListener('click', this.onClick)
  }
}
