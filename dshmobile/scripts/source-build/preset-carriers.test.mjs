#!/usr/bin/env node
// preset-carriers 的判别力与漂移守卫。
//
// 漂移守卫为什么必要：来源审计链的检查器是**权威门禁的等价实现**，权威源一旦重锚载体
// （0.14.2 就重锚过一次：`dsh-agent-presets/presets` → `agent-preset/skills` + `web-app/presets`），
// 等价实现若没跟上，表现是云端构建跑到第 40 分钟才判红一句「预设载体为空」，
// 而真因是门禁自身过期（坑 200）。这里把该漂移提前到 PR 门禁的秒级步骤。
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { PRESET_CARRIERS, checkPresetCarriers, countCarrierFiles } from './preset-carriers.mjs'

const AUTHORITY = 'scripts/check-engine-overlay.mjs'

/** 从权威门的 CARRIERS 数组字面量里抽出全部 `node_modules/...` 引号字面量。 */
function authorityCarriers(text) {
  const block = /const CARRIERS = \[([\s\S]*?)\n\]/.exec(text)
  assert.ok(block, `${AUTHORITY} 里找不到 CARRIERS 数组——权威源结构已变，请同步本守卫`)
  return [...block[1].matchAll(/'([^']*node_modules\/[^']*)'/g)].map((m) => m[1].replace(/\/+$/, ''))
}

test('载体清单与权威门禁 check-engine-overlay.mjs 逐条一致', () => {
  const mine = PRESET_CARRIERS.map((carrier) => carrier.path).sort()
  const theirs = authorityCarriers(readFileSync(AUTHORITY, 'utf8')).sort()
  assert.deepEqual(mine, theirs,
    '来源审计链的内置预设载体与权威门禁脱钩（权威源重锚后必须同步 scripts/source-build/preset-carriers.mjs）')
})

test('旧载体 dsh-agent-presets 不得回流（0.1.7 已拆包）', () => {
  for (const carrier of PRESET_CARRIERS) {
    assert.ok(!/dsh-agent-presets\//.test(carrier.path), `载体指向已被拆分的旧包: ${carrier.path}`)
  }
})

test('递归计数只数文件、不数目录', () => {
  const root = mkdtempSync(join(tmpdir(), 'preset-carriers-'))
  try {
    mkdirSync(join(root, 'a', 'nested'), { recursive: true })
    mkdirSync(join(root, 'empty-dir'), { recursive: true })
    writeFileSync(join(root, 'a', 'one.md'), '1')
    writeFileSync(join(root, 'a', 'nested', 'two.md'), '2')
    assert.equal(countCarrierFiles(root), 2)
    assert.equal(countCarrierFiles(join(root, 'empty-dir')), 0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('载体非空时放行并逐条回报计数', () => {
  const root = mkdtempSync(join(tmpdir(), 'preset-carriers-'))
  try {
    for (const carrier of PRESET_CARRIERS) {
      const dir = join(root, carrier.path)
      mkdirSync(dir, { recursive: true })
      writeFileSync(join(dir, 'content.txt'), 'x')
    }
    const report = checkPresetCarriers(root)
    assert.equal(report.length, PRESET_CARRIERS.length)
    for (const item of report) assert.equal(item.fileCount, 1)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('载体目录缺席判红（包没进快照）', () => {
  const root = mkdtempSync(join(tmpdir(), 'preset-carriers-'))
  try {
    const present = PRESET_CARRIERS[0].path
    mkdirSync(join(root, present), { recursive: true })
    writeFileSync(join(root, present, 'content.txt'), 'x')
    assert.throws(() => checkPresetCarriers(root), /preset carriers are empty/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('载体目录在场但为空判红（包在了、files 漏项）', () => {
  const root = mkdtempSync(join(tmpdir(), 'preset-carriers-'))
  try {
    for (const carrier of PRESET_CARRIERS) mkdirSync(join(root, carrier.path), { recursive: true })
    assert.throws(() => checkPresetCarriers(root), /files 漏项/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
