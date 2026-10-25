// @vitest-environment node
// 外观组压缩样式契约。
//
// 上游 AppearanceRow 的类名是 CSS Module 哈希，样式只能按结构命中：设置面板的
// data-shortcut-modal="settings" + 按钮的 aria-pressed。设置页里还有 1–2 个
// aria-pressed 按钮的其它组（模型、市场等），一旦选择器放宽就会把别处一起压扁。
// 这里在源码层钉住三件事：
//   1. 所有规则都 gate 在手机形态 + 设置面板下；
//   2. 按钮规则必须带「恰好三个按钮」的约束，不允许出现裸的 aria-pressed 选择器；
//   3. 按钮压缩不依赖 :has()（老内核可用），:has() 只用于组容器的行布局（不支持时退化）。
import { describe, it, expect } from 'vitest'
import { APPEARANCE_ROW_CSS } from '../src/client/appearance-row.css.ts'

const SCOPE = "html[data-dsh-mobile-form] [data-shortcut-modal='settings']"

/** Comments mention selectors by name; strip them before any structural assertion. */
const SHEET = APPEARANCE_ROW_CSS.replace(/\/\*[\s\S]*?\*\//g, '')

/** Split the sheet into selector blocks. */
function rules(): string[] {
  return SHEET.split('}').map(rule => rule.trim()).filter(rule => rule !== '')
}

describe('外观组压缩样式', () => {
  it('每条规则都 gate 在手机形态 + 设置面板下', () => {
    for (const rule of rules()) {
      const selectors = rule.split('{')[0] ?? ''
      for (const selector of selectors.split(',')) {
        expect(selector, '选择器必须限定作用域：' + selector).toContain(SCOPE)
      }
    }
  })

  it('按钮规则要求恰好三个按钮，不会命中 1–2 个按钮的组', () => {
    expect(SHEET).toContain(':first-child:nth-last-child(3)')
    const bare = rules()
      .map(rule => rule.split('{')[0] ?? '')
      .flatMap(selectors => selectors.split(','))
      .filter(selector => selector.includes('button[aria-pressed]') && !selector.includes(':nth-last-child(3)'))
    expect(bare, '出现了没有三按钮约束的 aria-pressed 选择器').toEqual([])
  })

  it('按钮压缩不依赖 :has()，:has() 只出现在组容器规则里', () => {
    const all = rules()
    const withHas = all.filter(rule => (rule.split('{')[0] ?? '').includes(':has('))
    expect(withHas.length).toBeGreaterThan(0)
    for (const rule of withHas) {
      const selector = rule.split('{')[0] ?? ''
      expect(selector, ':has() 只允许用于组容器').toContain('div:has(')
      // 规则主体必须是容器本身（选择器以 :has(...) 结尾），而不是里面的按钮。
      expect(selector.trim().endsWith(')'), ':has() 规则应作用于容器本身：' + selector).toBe(true)
    }
    // 按钮规则仍然存在，且不含 :has()：老内核照常压缩成一行。
    const buttonRules = all.filter(rule => {
      const selector = rule.split('{')[0] ?? ''
      return selector.includes('button[aria-pressed]') && !selector.includes(':has(')
    })
    expect(buttonRules.length).toBeGreaterThan(0)
  })

  it('颜色与圆角走主题令牌，不硬编码', () => {
    expect(APPEARANCE_ROW_CSS).toContain('var(--dsw-radius-md)')
    expect(APPEARANCE_ROW_CSS).not.toMatch(/#[0-9a-fA-F]{3,8}/)
    expect(APPEARANCE_ROW_CSS).not.toMatch(/rgba?\(/)
  })
})
