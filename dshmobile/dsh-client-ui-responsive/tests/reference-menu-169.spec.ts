// @vitest-environment jsdom
// ReferenceMenuEnhancer 的 #169 审计回归（0.13.8）：逐条给审计点补断言。
// ① 移动形态门（宽视口不注入）② renderBar 幂等（不重建节点 → 无 rAF/observer 抖动循环）
// ③ 稳定身份（同名行不塌缩）④ 关菜单清态（无幻影「已选 N 项」）⑤ 按行去抖（不吞别的行）
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { ReferenceMenuEnhancer } from '../src/client/mobile/reference-menu.ts'

let enhancer: ReferenceMenuEnhancer
let menu: HTMLElement

/** 上游菜单形状：listbox + 若干 option 行（label 可重复，用来验稳定身份）。 */
function buildMenu(labels: string[]): void {
  menu = document.createElement('div')
  menu.setAttribute('data-trigger-menu', '')
  const list = document.createElement('div')
  list.setAttribute('role', 'listbox')
  for (const [index, label] of labels.entries()) {
    const row = document.createElement('button')
    row.setAttribute('role', 'option')
    // 上游行 id = optionId(source, index)；默认 fixture 扮演 @ 引用菜单。
    row.id = 'dsh-slash-option-reference-' + String(index)
    const name = document.createElement('span')
    name.className = 'x_itemName'
    name.textContent = label
    row.appendChild(name)
    list.appendChild(row)
  }
  menu.appendChild(list)
  document.body.appendChild(menu)
}

function flush(): Promise<void> {
  return new Promise((resolve) => { requestAnimationFrame(() => { requestAnimationFrame(() => { resolve() }) }) })
}

function composer(): void {
  const card = document.createElement('div')
  card.setAttribute('data-composer-card', '')
  const ed = document.createElement('div')
  ed.setAttribute('contenteditable', 'true')
  card.appendChild(ed)
  document.body.appendChild(card)
}

function checkboxes(): Element[] {
  return Array.from(document.querySelectorAll('[data-dsh-ref-check]'))
}

function rows(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('[data-trigger-menu] [role="option"]'))
}

function tap(row: HTMLElement): void {
  // jsdom 无 PointerEvent：用 mousedown（与既有 spec 一致，且三连手势里同样是首个 kind 的等价物）
  row.querySelector('[data-dsh-ref-check]')?.dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
  )
}

beforeEach(async () => {
  composer()
  buildMenu(['sub/', 'alpha.txt'])
  enhancer = new ReferenceMenuEnhancer()
})

afterEach(() => {
  enhancer.detach()
  document.documentElement.removeAttribute('data-dsh-mobile-form')
  document.body.innerHTML = ''
  window.history.replaceState({}, '', '/')
})

describe('ReferenceMenuEnhancer #169', () => {
  it('移动形态门：宽视口不注入手机专用 chrome（#169-4）', async () => {
    document.documentElement.removeAttribute('data-dsh-mobile-form')
    // jsdom 的 matchMedia 桩不可用时按「非移动」处理，因此这里不需要额外 mock
    enhancer.attach()
    await flush()
    expect(checkboxes().length).toBe(0)
  })

  it('renderBar 幂等：重复渲染复用同一批节点（#169-2 的抖动循环防线）', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    tap(rows()[0])
    const first = document.querySelector('[data-dsh-ref-bar]')
    expect(first).not.toBeNull()
    const firstButton = document.querySelector('[data-dsh-ref-add]')
    // 再触发若干次渲染（模拟 observer 抖动）
    tap(rows()[1])
    tap(rows()[1])
    tap(rows()[1])
    const again = document.querySelector('[data-dsh-ref-bar]')
    expect(again).toBe(first)
    expect(document.querySelector('[data-dsh-ref-add]')).toBe(firstButton)
  })

  it('稳定身份：同名两行各自独立勾选，不塌缩（#169-5）', async () => {
    document.body.innerHTML = ''
    composer()
    buildMenu(['index.ts', 'index.ts'])
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    const list = rows()
    expect(list.length).toBe(2)
    tap(list[0])
    expect(list[0].hasAttribute('data-dsh-ref-on')).toBe(true)
    expect(list[1].hasAttribute('data-dsh-ref-on')).toBe(false)
  })

  it('关菜单即清态：重开不出现幻影「已选 N 项」（#169-6）', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    tap(rows()[0])
    expect(document.querySelector('[data-dsh-ref-bar]')).not.toBeNull()
    // 菜单被上游关闭
    document.querySelector('[data-trigger-menu]')?.remove()
    await flush()
    expect(document.querySelector('[data-dsh-ref-bar]')).toBeNull()
    // 重开菜单：不应带着上次的选择
    buildMenu(['sub/', 'alpha.txt'])
    await flush()
    expect(rows()[0].hasAttribute('data-dsh-ref-on')).toBe(false)
    expect(document.querySelector('[data-dsh-ref-bar]')).toBeNull()
  })

  it('按行去抖：400ms 内点第二行不被吞（#169-6）', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    const list = rows()
    tap(list[0])
    tap(list[1])
    expect(list[0].hasAttribute('data-dsh-ref-on')).toBe(true)
    expect(list[1].hasAttribute('data-dsh-ref-on')).toBe(true)
    expect(document.querySelector('[data-dsh-ref-bar-count]')?.textContent).toContain('2')
  })
})

/**
 * 触发源门（2026-10-11，「+」菜单串味）。
 *
 * 上游把 `/`（指令）和 `@`（引用）渲染进**同一个** [data-trigger-menu] 容器，
 * 旧实现只判「菜单在不在场」，于是普通「+」菜单的每一行都被插上复选框
 * （真机实测 9/9 行：文件/目标/计划/反馈/压缩/权限/模型/下载日志/插件项）。
 * 判别字段是上游行 id 里的源名（optionId(source, index)，MenuView.tsx:38-40）——
 * 实测容器属性、sectionTitle 属性在两种菜单里都拿不到源名，只有行 id 稳定在场。
 */
describe('ReferenceMenuEnhancer 触发源门（「+」菜单不得出现复选框）', () => {
  /** 上游「/」指令菜单形状：同样的容器、同样的 role=option，但行 id 的源名是 'command'。 */
  function buildCommandMenu(): void {
    document.querySelector('[data-trigger-menu]')?.remove()
    const menu = document.createElement('div')
    menu.setAttribute('data-trigger-menu', '')
    const list = document.createElement('div')
    list.setAttribute('role', 'listbox')
    for (const [index, label] of ['文件', '目标', '计划'].entries()) {
      const row = document.createElement('button')
      row.setAttribute('role', 'option')
      row.id = 'dsh-slash-option-command-' + String(index)
      const name = document.createElement('span')
      name.className = 'x_itemName'
      name.textContent = label
      row.appendChild(name)
      list.appendChild(row)
    }
    menu.appendChild(list)
    document.body.appendChild(menu)
  }

  it('普通「+」菜单：0 个复选框、0 个多选条（复现原缺陷的判据）', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    buildCommandMenu()
    enhancer.attach()
    await flush()
    expect(checkboxes().length).toBe(0)
    expect(document.querySelector('[data-dsh-ref-bar]')).toBeNull()
    expect(rows().every(row => !row.hasAttribute('data-dsh-ref-key'))).toBe(true)
  })

  it('@ 菜单：复选框照常注入（引用源不受影响）', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    expect(checkboxes().length).toBe(2)
  })

  it('上游复用同一容器从 @ 切到 / 时，已注入的复选框被主动撤掉', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    expect(checkboxes().length).toBe(2)
    tap(rows()[0])
    expect(document.querySelector('[data-dsh-ref-bar]')).not.toBeNull()

    // 同一个 MenuSurface 节点改承载指令菜单（上游就是复用容器渲染的）
    const menu = document.querySelector('[data-trigger-menu]')!
    const list = menu.querySelector('[role="listbox"]')!
    list.innerHTML = ''
    for (const [index, label] of ['压缩', '权限'].entries()) {
      const row = document.createElement('button')
      row.setAttribute('role', 'option')
      row.id = 'dsh-slash-option-command-' + String(index)
      row.textContent = label
      list.appendChild(row)
    }
    await flush()

    expect(checkboxes().length).toBe(0)
    expect(document.querySelector('[data-dsh-ref-bar]')).toBeNull()
    expect(rows().every(row => !row.hasAttribute('data-dsh-ref-key'))).toBe(true)
  })

  it('候选还在路上（无 option 行）不清态：不清掉用户刚勾的选择', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    tap(rows()[0])
    expect(document.querySelector('[data-dsh-ref-bar-count]')?.textContent).toContain('1')

    // 上游在查询细化时先渲染骨架行（没有 role=option），此时必须保留选择
    document.querySelector('[data-trigger-menu] [role="listbox"]')!.innerHTML = ''
    await flush()
    expect(document.querySelector('[data-dsh-ref-bar]')).not.toBeNull()
    expect(document.querySelector('[data-dsh-ref-bar-count]')?.textContent).toContain('1')
  })

  it('撤掉注入后不留下孤儿方框（菜单关闭再重开是干净的）', async () => {
    document.documentElement.setAttribute('data-dsh-mobile-form', '')
    enhancer.attach()
    await flush()
    tap(rows()[0])
    document.querySelector('[data-trigger-menu]')?.remove()
    await flush()
    expect(checkboxes().length).toBe(0)

    buildCommandMenu()
    await flush()
    expect(checkboxes().length).toBe(0)
    document.querySelector('[data-trigger-menu]')?.remove()
    buildMenu(['sub/', 'alpha.txt'])
    await flush()
    // 回到 @ 菜单后重新注入，且只有一行是干净的一个方框
    expect(checkboxes().length).toBe(2)
  })
})
