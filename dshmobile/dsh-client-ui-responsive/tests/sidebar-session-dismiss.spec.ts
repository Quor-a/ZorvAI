// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SidebarSessionDismiss } from '../src/client/mobile/sidebar-session-dismiss.ts'

function setMobile(on: boolean): void {
  if (on) document.documentElement.setAttribute('data-dsh-mobile-form', '')
  else document.documentElement.removeAttribute('data-dsh-mobile-form')
}

function harness({ collapsed = false, selected = false, mobile = true } = {}) {
  document.body.innerHTML = ''
  setMobile(mobile)
  const frame = document.createElement('div')
  frame.setAttribute('data-dsh-frame', '')
  if (collapsed) frame.setAttribute('data-sidebar-collapsed', '')
  document.body.appendChild(frame)
  const drawer = document.createElement('aside')
  drawer.className = 'sidebarCol_test'
  frame.appendChild(drawer)
  const row = document.createElement('div')
  row.setAttribute('data-row-key', 'session:session-123')
  row.setAttribute('role', 'treeitem')
  row.setAttribute('aria-selected', String(selected))
  const title = document.createElement('span')
  title.textContent = 'Conversation title'
  row.appendChild(title)
  const action = document.createElement('button')
  action.setAttribute('data-slot', 'sidebar.session.row.action')
  row.appendChild(action)
  const menu = document.createElement('button')
  menu.setAttribute('data-slot', 'sidebar.workspaces.session.menu.item')
  row.appendChild(menu)
  drawer.appendChild(row)
  let collapses = 0
  const deps = {
    mobileForm: () => document.documentElement.hasAttribute('data-dsh-mobile-form'),
    drawerCollapsed: () => frame.hasAttribute('data-sidebar-collapsed'),
    collapseDrawer: vi.fn(() => { collapses++; frame.setAttribute('data-sidebar-collapsed', '') }),
  }
  const dismiss = new SidebarSessionDismiss(deps)
  dismiss.attach()
  return { frame, drawer, row, title, action, menu, deps, dismiss, collapses: () => collapses }
}

function click(target: Element, detail = 1): MouseEvent {
  const event = new MouseEvent('click', { bubbles: true, cancelable: true, detail })
  target.dispatchEvent(event)
  return event
}

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => {
  document.body.innerHTML = ''
  document.documentElement.removeAttribute('data-dsh-mobile-form')
  vi.runOnlyPendingTimers()
  vi.useRealTimers()
})

describe('SidebarSessionDismiss：切换已有会话后收起手机侧栏', () => {
  it('手机侧栏打开时选择未选中的真实 session row，延后收起并保留上游点击', () => {
    const h = harness()
    const upstream = vi.fn(() => h.row.setAttribute('aria-selected', 'true'))
    h.row.addEventListener('click', upstream)
    const event = click(h.title)
    expect(event.defaultPrevented).toBe(false)
    expect(upstream).toHaveBeenCalledOnce()
    expect(h.deps.collapseDrawer).not.toHaveBeenCalled()
    vi.advanceTimersByTime(500)
    expect(h.deps.collapseDrawer).toHaveBeenCalledOnce()
    expect(h.frame.hasAttribute('data-sidebar-collapsed')).toBe(true)
    h.dismiss.detach()
  })

  it('上游同步提交选中并聚焦时也能先抑制焦点，再确认选择后收起', () => {
    const h = harness()
    const card = document.createElement('div')
    card.setAttribute('data-composer-card', '')
    const composer = document.createElement('textarea')
    card.append(composer)
    document.body.append(card)
    h.row.addEventListener('click', () => {
      h.row.setAttribute('aria-selected', 'true')
      composer.focus()
    })
    click(h.title)
    expect(document.activeElement).not.toBe(composer)
    vi.advanceTimersByTime(500)
    expect(h.deps.collapseDrawer).toHaveBeenCalledOnce()
    h.dismiss.detach()
  })

  it('上游未成功选中时不收起，侧栏外的同形treeitem不介入', () => {
    const h = harness()
    click(h.title)
    vi.advanceTimersByTime(500)
    expect(h.deps.collapseDrawer).not.toHaveBeenCalled()
    const external = h.row.cloneNode(true) as HTMLElement
    document.body.append(external)
    external.addEventListener('click', () => external.setAttribute('aria-selected', 'true'))
    click(external)
    vi.advanceTimersByTime(500)
    expect(h.deps.collapseDrawer).not.toHaveBeenCalled()
    h.dismiss.detach()
  })

  it('当前已选中行、新建会话占位行和普通 Workspace 行都不触发收起', () => {
    const selected = harness({ selected: true })
    click(selected.title)
    vi.advanceTimersByTime(900)
    expect(selected.deps.collapseDrawer).not.toHaveBeenCalled()
    selected.dismiss.detach()

    const h = harness()
    const placeholder = document.createElement('div')
    placeholder.setAttribute('data-row-key', 'new-session')
    placeholder.setAttribute('role', 'treeitem')
    h.drawer.appendChild(placeholder)
    const workspace = document.createElement('div')
    workspace.setAttribute('data-row-key', 'workspace:abc')
    workspace.setAttribute('role', 'treeitem')
    h.drawer.appendChild(workspace)
    click(placeholder)
    click(workspace)
    vi.advanceTimersByTime(900)
    expect(h.deps.collapseDrawer).not.toHaveBeenCalled()
    h.dismiss.detach()
  })

  it('桌面形态与已收起侧栏都不改变布局', () => {
    const desktop = harness({ mobile: false })
    click(desktop.title)
    vi.advanceTimersByTime(900)
    expect(desktop.deps.collapseDrawer).not.toHaveBeenCalled()
    desktop.dismiss.detach()

    const collapsed = harness({ collapsed: true })
    click(collapsed.title)
    vi.advanceTimersByTime(900)
    expect(collapsed.deps.collapseDrawer).not.toHaveBeenCalled()
    collapsed.dismiss.detach()
  })

  it('会话操作按钮和菜单项仍由上游处理，不触发收起', () => {
    const h = harness()
    const operation = vi.fn()
    h.action.addEventListener('click', operation)
    click(h.action)
    click(h.menu)
    document.documentElement.setAttribute('data-dsh-sidebar-reorder', '')
    click(h.title)
    document.documentElement.removeAttribute('data-dsh-sidebar-reorder')
    const move = new Event('touchmove', { bubbles: true, cancelable: true })
    h.row.dispatchEvent(move)
    vi.advanceTimersByTime(900)
    expect(operation).toHaveBeenCalledOnce()
    expect(move.defaultPrevented).toBe(false)
    expect(h.deps.collapseDrawer).not.toHaveBeenCalled()
    h.dismiss.detach()
  })

  it('标题双击重命名保留：第二次点击取消待收起', () => {
    const h = harness()
    const rename = vi.fn()
    h.title.addEventListener('dblclick', rename)
    click(h.title, 1)
    click(h.title, 2)
    h.title.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }))
    vi.advanceTimersByTime(900)
    expect(rename).toHaveBeenCalledOnce()
    expect(h.deps.collapseDrawer).not.toHaveBeenCalled()
    h.dismiss.detach()
  })

  it('短暂挡住切换后异步聚焦 composer，pointerdown 后允许用户主动聚焦', () => {
    const h = harness()
    const card = document.createElement('div')
    card.setAttribute('data-composer-card', '')
    const composer = document.createElement('div')
    composer.setAttribute('data-composer-input', 'true')
    composer.setAttribute('data-lexical-editor', 'true')
    composer.setAttribute('contenteditable', 'true')
    composer.setAttribute('role', 'textbox')
    composer.tabIndex = 0
    card.appendChild(composer)
    document.body.appendChild(card)
    const blur = vi.spyOn(composer, 'blur')

    click(h.title)
    composer.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
    expect(blur).toHaveBeenCalledOnce()

    composer.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    composer.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
    expect(blur).toHaveBeenCalledOnce()
    h.dismiss.detach()
  })
})
