// 源码级纪律门禁（ST-09 / ST-27）：组件里禁止裸写一次性桥读。
// 判据（计划 §4.3 ST-09）：grep "useState(() => window.androidBridge" 命中数 = 0。
import { test, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('../src', import.meta.url))

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry)
    if (statSync(p).isDirectory()) out.push(...walk(p))
    else if (p.endsWith('.ts') || p.endsWith('.tsx')) out.push(p)
  }
  return out
}

test('ST-09/ST-27：src 内不得存在 useState(() => window.androidBridge 这类裸写一次性桥读', () => {
  const hits: string[] = []
  for (const file of walk(SRC)) {
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      // 与计划 §4.3/ST-27 的判据同形：字面子串命中（注释里出现也算，避免门禁被注释糊过去）
      if (line.includes('useState(() => window.androidBridge')) hits.push(file + ':' + (i + 1))
    })
  }
  expect(hits, '裸写一次性桥读必须改用 useShellState').toEqual([])
})

// #418 起 DevSection.tsx（开发者选项分区）已整体删除，功能迁入 ZorvAI 壳侧
// 「清理存储」页；#429 起 notify-settings.tsx（通知分区）随之删除，功能迁入 ZorvAI 设置
// 「通知 → Harness 通知」。本文件的点面门禁随之收刈到唯一仍在场的使用方（通用设置）。
test('ST-09：壳体状态读点都经 useShellState（通用设置）', () => {
  const files = walk(SRC)
  // 路径分隔符跨平台（Windows 是 \\）：只按文件名判定
  const general = files.find((f) => f.endsWith('GeneralSettings.tsx'))
  const hook = files.find((f) => f.endsWith('use-shell-state.ts'))
  expect(general && hook, '两个文件必须存在').toBeTruthy()
  const genText = readFileSync(general as string, 'utf8')
  expect(genText).toContain("from '../mobile/use-shell-state.ts'")
  expect((genText.match(/useShellState</g) ?? []).length).toBeGreaterThanOrEqual(1)
})
