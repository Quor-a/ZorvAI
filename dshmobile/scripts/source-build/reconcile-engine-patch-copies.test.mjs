#!/usr/bin/env node
// reconcile-engine-patch-copies 的判别力用例。
//
// 成因回顾（设备实锤）：pnpm 布局下同一包有顶层物化副本与 `.pnpm/**` store 副本，
// 引擎补丁只按登记表的顶层 target 写入 ⇒ store 副本保持原样；运行时若从 store 解析
// （`node-addon-require-builtin` 就是这样），加载到的是**未打补丁**的代码 ⇒ 引擎 boot 硬崩。
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { collectPhysicalFiles, matchesPatchTarget, reconcileEnginePatchCopies } from './reconcile-engine-patch-copies.mjs'

const PREFIX = 'usr/lib/node_modules/@deepseek-ai/dsh'
const MARKER = 'dsh-mobile native-binding fallback (N1)'
const registry = {
  patches: [{
    id: 'narb-android-N1',
    scope: 'engine',
    target: `${PREFIX}/node_modules/node-addon-require-builtin/lib/index.js`,
    marker: MARKER,
  }],
}

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'reconcile-copies-'))
  const engine = join(root, PREFIX)
  // 顶层物化副本：已打补丁
  mkdirSync(join(engine, 'node_modules/node-addon-require-builtin/lib'), { recursive: true })
  writeFileSync(join(engine, 'node_modules/node-addon-require-builtin/lib/index.js'),
    `const x = 1;\n// ${MARKER}\n`)
  // store 副本：未打补丁（与设备实测同形）
  mkdirSync(join(engine, 'node_modules/.pnpm/node-addon-require-builtin@0.1.6/node_modules/node-addon-require-builtin/lib'), { recursive: true })
  writeFileSync(join(engine, 'node_modules/.pnpm/node-addon-require-builtin@0.1.6/node_modules/node-addon-require-builtin/lib/index.js'), 'const x = 1;\n')
  return { root, engine }
}

test('只按「同包 + 同包内相对路径」识别副本，不误伤同名不同包', () => {
  // 匹配器只吃「相对引擎根的路径」（引擎根 = @deepseek-ai/dsh 目录本身）
  const target = 'node_modules/node-addon-require-builtin/lib/index.js'
  assert.equal(matchesPatchTarget(target, target), true, '顶层副本必须算')
  assert.equal(matchesPatchTarget('node_modules/.pnpm/node-addon-require-builtin@0.1.6/node_modules/node-addon-require-builtin/lib/index.js', target), true, 'store 副本必须算')
  assert.equal(matchesPatchTarget('node_modules/dsh-experimental-webworker-packer/lib/index.js', target), false, '别的包不得算进来')

  // 引擎根包自身的短后缀目标（lib/bin.js）只认那一个文件——这里曾把别的包误报成未打补丁副本
  const selfTarget = 'lib/bin.js'
  assert.equal(matchesPatchTarget('lib/bin.js', selfTarget), true)
  assert.equal(matchesPatchTarget('node_modules/dsh-experimental-webworker-packer/lib/bin.js', selfTarget), false)
})

test('store 副本被补上，且与已打补丁那份逐字节一致', () => {
  const { root, engine } = fixture()
  try {
    const report = reconcileEnginePatchCopies(engine, registry)
    assert.equal(report.reconciledCopyCount, 1, '应恰好收敛 1 份副本')
    const store = join(engine, 'node_modules/.pnpm/node-addon-require-builtin@0.1.6/node_modules/node-addon-require-builtin/lib/index.js')
    const top = join(engine, 'node_modules/node-addon-require-builtin/lib/index.js')
    assert.equal(readFileSync(store, 'utf8'), readFileSync(top, 'utf8'))
    assert.match(readFileSync(store, 'utf8'), new RegExp(MARKER.replace(/[()]/g, '\\$&')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('已经一致时幂等：不改写、报告为零', () => {
  const { root, engine } = fixture()
  try {
    reconcileEnginePatchCopies(engine, registry)
    const second = reconcileEnginePatchCopies(engine, registry)
    assert.equal(second.reconciledCopyCount, 0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('所有副本都缺 marker 即判红（补丁根本没打上）', () => {
  const { root, engine } = fixture()
  try {
    for (const file of collectPhysicalFiles(engine)) writeFileSync(file, 'const x = 1;\n')
    assert.throws(() => reconcileEnginePatchCopies(engine, registry), /补丁完全没打上/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('补丁目标在引擎树里找不到即判红', () => {
  const root = mkdtempSync(join(tmpdir(), 'reconcile-missing-'))
  try {
    const engine = join(root, PREFIX)
    mkdirSync(engine, { recursive: true })
    assert.throws(() => reconcileEnginePatchCopies(engine, registry), /找不到/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
