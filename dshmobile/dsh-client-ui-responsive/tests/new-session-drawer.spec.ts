// @vitest-environment jsdom
// issue #349：从侧边栏创建新会话后停留侧栏（竖屏还有键盘），应自动返回新会话界面。
//
// 现场实测（MuMu x86_64，600px 竖屏 data-dsh-mobile-form）：抽屉打开后点「新建会话」，
// 400/1500/3000ms 采样 data-sidebar-collapsed 恒缺省（抽屉一直张着），
// activeElement 停在 hHd-Xa_newSession。
//
// 真因：上游 startSession → replaceMain(...,'reveal') 里的 ctx.layout.selectPanel(null)
// （navigation.ts:403）在「本来就停在会话」时不发通知；本插件收起抽屉的唯一通路
// PanelNavDrawer 只认 null → panel 那次转变（panel-nav-drawer.ts:54），
// 手势的 returnToConversation 只在手势里调用 —— 没有人收起抽屉。
//
// 判据（每条配反证；反证 = 把旧形态改回去必须判红）：
//   A. 抽屉张着 + 手机形态 + 点抽屉里的新建会话 → 收起抽屉一次；
//   B. 抽屉本来就关着 → 不得调 toggle（toggle 会把它反开）；
//   C. 桌面形态（非 data-dsh-mobile-form）→ 完全不介入（停靠侧栏是常驻外框）；
//   D. 只认抽屉里的按钮：页头同 aria-label 的品牌按钮、抽屉外同类的按钮都不得触发；
//   E. 键盘：可编辑焦点被 blur（用户报「并弹出键盘」）。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { NewSessionDrawer } from '../src/client/mobile/new-session-drawer.ts'

function setPhoneForm(on: boolean): void {
  if (on) document.documentElement.setAttribute('data-dsh-mobile-form', '')
  else document.documentElement.removeAttribute('data-dsh-mobile-form')
}

/**
 * 按设备实测的 DOM 造一个抽屉：外框根 + 左侧栏列 + 上游自己的按钮类名。
 * @param collapsed - 抽屉初始是否已收起。
 * @param withEditableFocus - 是否让一个可编辑元素持有焦点（模拟软键盘已弹起）。
 */
function harness(collapsed: boolean, withEditableFocus = false) {
  document.body.innerHTML = ''
  const frame = document.createElement('div')
  frame.setAttribute('data-dsh-frame', '')
  if (collapsed) frame.setAttribute('data-sidebar-collapsed', '')
  document.body.appendChild(frame)

  const col = document.createElement('div')
  col.className = 'hHd-Xa_sidebarCol'
  const newSession = document.createElement('button')
  newSession.className = 'hHd-Xa_newSession'
  newSession.setAttribute('aria-label', '新建会话')
  newSession.textContent = '新会话'
  col.appendChild(newSession)
  frame.appendChild(col)

  // 页头品牌按钮：同 aria-label，但不是新建会话入口，不得被误判。
  const brand = document.createElement('button')
  brand.className = 'hHd-Xa_brand hHd-Xa_wide'
  brand.setAttribute('aria-label', '新建会话')
  frame.appendChild(brand)

  // 抽屉外的同类按钮：页面别处也可能有 newSession 类名，同样不得触发。
  const outside = document.createElement('button')
  outside.className = 'hHd-Xa_newSession'
  frame.appendChild(outside)

  let editable: HTMLTextAreaElement | undefined
  if (withEditableFocus) {
    editable = document.createElement('textarea')
    document.body.appendChild(editable)
    editable.focus()
  }

  const collapseDrawer = vi.fn(() => { frame.setAttribute('data-sidebar-collapsed', '') })
  const deps = {
    drawerCollapsed: () => frame.hasAttribute('data-sidebar-collapsed'),
    collapseDrawer,
    mobileForm: () => document.documentElement.hasAttribute('data-dsh-mobile-form'),
  }
  return { frame, col, newSession, brand, outside, editable, collapseDrawer, deps }
}

/** 派发一次可取消的冒泡点击（与真实用户点击同一路径）。 */
function click(el: Element): void {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
}

describe('NewSessionDrawer：点新建会话后放下抽屉（issue #349）', () => {
  beforeEach(() => { setPhoneForm(true) })

  it('抽屉张着时点抽屉里的新建会话：收起抽屉一次', () => {
    const h = harness(false)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.newSession)
    expect(h.collapseDrawer).toHaveBeenCalledTimes(1)
    expect(h.frame.hasAttribute('data-sidebar-collapsed')).toBe(true)
    inst.detach()
  })

  it('反证：抽屉本来就关着时不得调 toggle（toggle 会把它反开）', () => {
    const h = harness(true)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.newSession)
    expect(h.collapseDrawer).not.toHaveBeenCalled()
    expect(h.frame.hasAttribute('data-sidebar-collapsed')).toBe(true)
    inst.detach()
  })

  it('反证：桌面形态（非手机表单）完全不介入', () => {
    setPhoneForm(false)
    const h = harness(false)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.newSession)
    expect(h.collapseDrawer).not.toHaveBeenCalled()
    expect(h.frame.hasAttribute('data-sidebar-collapsed')).toBe(false)
    inst.detach()
  })

  it('反证：页头同 aria-label 的品牌按钮不得被误判', () => {
    const h = harness(false)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.brand)
    expect(h.collapseDrawer).not.toHaveBeenCalled()
    inst.detach()
  })

  it('反证：抽屉外同类按钮不得触发', () => {
    const h = harness(false)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.outside)
    expect(h.collapseDrawer).not.toHaveBeenCalled()
    inst.detach()
  })

  it('键盘：可编辑焦点被 blur（用户报「并弹出键盘」）', () => {
    const h = harness(false, true)
    expect(document.activeElement).toBe(h.editable)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.newSession)
    expect(h.collapseDrawer).toHaveBeenCalledTimes(1)
    expect(document.activeElement).not.toBe(h.editable)
    inst.detach()
  })

  it('反证：detach 后点击不得再收起抽屉', () => {
    const h = harness(false)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    inst.detach()
    click(h.newSession)
    expect(h.collapseDrawer).not.toHaveBeenCalled()
  })

  it('不拦截事件：上游自己的 startSession 必须照常收到点击', () => {
    const h = harness(false)
    const upstream = vi.fn()
    h.newSession.addEventListener('click', upstream)
    const inst = new NewSessionDrawer(h.deps)
    inst.attach()
    click(h.newSession)
    expect(upstream).toHaveBeenCalledTimes(1)
    inst.detach()
  })
})
