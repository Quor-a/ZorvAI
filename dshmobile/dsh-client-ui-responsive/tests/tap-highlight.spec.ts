// @vitest-environment jsdom
// A 项：Android WebView 点按蓝色蒙层（0.14.7 / Spec 2.3）。
//
// 真机实测（MuMu PGBM10，WebView Chrome/110，600px 视口）：
//   · 我方源码与上游都没有任何 tap-highlight 声明；
//   · 770/770 个元素（含 HTML/HEAD/BODY 与深层 button）的
//     getComputedStyle().webkitTapHighlightColor 全 = rgba(51, 181, 229, 0.4)，
//     即 #33b5e5 —— WebView 的 UA 默认值（Android 5.0 前的 ICS 蓝）；
//   · 该属性可继承：设备上只在 :root 写一次，body 与深层 button 的解析值同时归零。
//
// 本文件守三条判据，每条都配反证：
//   ① 抹掉 UA 蓝；② 声明在 :root（靠继承全站覆盖，不是逐元素补丁）；
//   ③ 只动这一条属性，不碰 opacity/outline/transition（保留正常点击反馈与焦点环）。
import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { TAP_HIGHLIGHT_CSS } from '../src/client/mobile/tap-highlight.css.ts'

/** 剥掉注释，避免断言被注释里的散文满足。 */
const rule = TAP_HIGHLIGHT_CSS.replace(/\/\*[\s\S]*?\*\//g, '')
/** 规则体（第一条 { 与最后一条 } 之间）。 */
const body = rule.slice(rule.indexOf('{') + 1, rule.lastIndexOf('}'))
/** 选择器列表（第一条 { 之前）。 */
const selectors = rule.slice(0, rule.indexOf('{')).trim()

describe('A 项：点按蓝色蒙层在公共层被抹掉', () => {
  it('① 声明了 -webkit-tap-highlight-color: transparent', () => {
    expect(/^-webkit-tap-highlight-color:\s*transparent\s*;?$/.test(body.trim())).toBe(true)
  })

  it('① 反证：任何非 transparent 的取值都必须判红（UA 蓝正是这样活下来的）', () => {
    const wrong = TAP_HIGHLIGHT_CSS.replace('transparent', 'rgba(51, 181, 229, 0.4)')
    const wrongBody = wrong.slice(wrong.indexOf('{') + 1, wrong.lastIndexOf('}')).trim()
    expect(/^-webkit-tap-highlight-color:\s*transparent\s*;?$/.test(wrongBody)).toBe(false)
  })

  it('② 声明落在 :root 上，靠继承覆盖全站（不是逐元素补丁）', () => {
    expect(selectors).toBe(':root')
    // 反证：通配符选择器不是本方案要的形态
    expect(selectors).not.toContain('*')
  })

  it('③ 只抹掉 UA 高亮，不碰其它反馈属性（保留点击反馈与焦点环）', () => {
    for (const property of ['opacity:', 'outline:', 'transition:', 'pointer-events:', 'user-select:']) {
      expect(rule).not.toContain(property)
    }
    // 规则体除该属性外应为空
    expect(body.replace(/-webkit-tap-highlight-color:\s*transparent\s*;?/, '').trim()).toBe('')
  })

  it('全文件只有一条规则（不做多套覆盖）', () => {
    expect(rule.match(/\{/g) ?? []).toHaveLength(1)
  })
})

describe('A 项：装配一致性（index.ts 由 Lead 集成）', () => {
  it('index.ts 一旦引入 TAP_HIGHLIGHT_CSS，就必须同时注入它（防半截集成）', () => {
    const index = readFileSync(join(process.cwd(), 'src', 'client', 'index.ts'), 'utf8')
    if (index.includes('TAP_HIGHLIGHT_CSS')) {
      expect(index.includes("injectStyle('tap-highlight'") || index.includes('injectStyle("tap-highlight"')).toBe(true)
    }
  })

  it('样式表文件存在于 src/client/mobile/ 下（装配点的前提）', () => {
    expect(existsSync(join(process.cwd(), 'src', 'client', 'mobile', 'tap-highlight.css.ts'))).toBe(true)
  })
})
