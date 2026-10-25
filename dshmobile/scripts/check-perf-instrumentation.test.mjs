#!/usr/bin/env node
// check-perf-instrumentation.test.mjs — 真实 CLI 快照产物回归。
// 用微型 ustar 档案覆盖旧 P1、当前 P1、缺成员、坏档案和 phase 输出签名缺项，
// 避免每个负向用例都复制/解压完整 snapshot.tar.xz。
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './patches/tests/lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const checker = join(here, 'check-perf-instrumentation.mjs')
const applyPatches = join(here, 'patches', 'apply-patches.mjs')
const target = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js'
const fixture = versionedFixture('dsh-client-modules', 'lib', 'index.js')
const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

// Write the small subset the CLI reads. USTAR is intentionally uncompressed so the test
// exercises the real `tar -xO -f ... <member>` path without paying full snapshot decompression.
function writeTar(path, files) {
  const blocks = []
  const octal = (value, width) => value.toString(8).padStart(width - 1, '0') + '\0'
  for (const [name, value] of Object.entries(files)) {
    const body = Buffer.from(value)
    const h = Buffer.alloc(512)
    h.write(name, 0, 100, 'utf8')
    h.write(octal(0o644, 8), 100, 8, 'ascii')
    h.write(octal(0, 8), 108, 8, 'ascii')
    h.write(octal(0, 8), 116, 8, 'ascii')
    h.write(octal(body.length, 12), 124, 12, 'ascii')
    h.write(octal(0, 12), 136, 12, 'ascii')
    h.fill(0x20, 148, 156)
    h[156] = 0x30
    h.write('ustar\0', 257, 6, 'ascii')
    h.write('00', 263, 2, 'ascii')
    const checksum = h.reduce((sum, byte) => sum + byte, 0)
    h.write(checksum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'ascii')
    blocks.push(h, body)
    const padding = (512 - (body.length % 512)) % 512
    if (padding) blocks.push(Buffer.alloc(padding))
  }
  blocks.push(Buffer.alloc(1024))
  writeFileSync(path, Buffer.concat(blocks))
}

const profiles = {
  'home/.dsh/profiles/web/package.json': JSON.stringify({ name: 'web', dsh: { profile: { bundles: ['@deepseek-ai/dsh-web-app'] } } }),
  'home/.dsh/profiles/headless/package.json': JSON.stringify({ name: 'headless', dsh: { profile: { bundles: ['@deepseek-ai/dsh-headless'] } } }),
}
const scratch = mkdtempSync(join(tmpdir(), 'perf-instrumentation-cli-'))
try {
  const fixtureRoot = join(scratch, 'current-tree')
  const targetPath = join(fixtureRoot, target)
  mkdirSync(dirname(targetPath), { recursive: true })
  copyFileSync(fixture, targetPath)
  const applied = spawnSync(process.execPath,
    [applyPatches, fixtureRoot, '--apply', '--scope', 'engine', '--only', 'combo-probe-P1'], { encoding: 'utf8' })
  check('当前引擎夹具可由正式 patcher 生成完整 P1 phase 产物', applied.status === 0,
    (applied.stdout + applied.stderr).trim().split('\n').slice(-2).join(' | '))
  const currentText = readFileSync(targetPath, 'utf8')
  const currentTar = join(scratch, 'current-phase.tar')
  writeTar(currentTar, { ...profiles, [target]: currentText })

  const oldTar = join(scratch, 'old-p1.tar')
  writeTar(oldTar, { ...profiles, [target]: [
    '/* dsh-mobile combo probe (P1) */',
    'function dshMobileComboProbeEmit() {}',
    'console.log(`[perf] TOTAL calls=1 totalMs=5 loopP99Ms=-1 loopSamples=0`);',
  ].join('\n') })

  const missingTargetTar = join(scratch, 'missing-target.tar')
  writeTar(missingTargetTar, profiles)
  const missingOutputTar = join(scratch, 'missing-phase-output.tar')
  writeTar(missingOutputTar, { ...profiles, [target]: currentText.replace('[perf] phase name=${phase.name}', '[perf] phase label=${phase.name}') })
  const badTar = join(scratch, 'bad.tar')
  writeFileSync(badTar, 'not a tar archive\n')

  const run = (tar) => spawnSync(process.execPath,
    [checker, '--require', '--snapshot', tar, '--abi', 'x86_64'], { encoding: 'utf8' })
  const current = run(currentTar)
  check('完整当前 phase tar 经 --require 真 CLI 接受', current.status === 0,
    (current.stdout + current.stderr).trim().split('\n').slice(-3).join(' | '))

  const stale = run(oldTar)
  check('旧 P1/TOTAL tar 被真 CLI 拒绝并点名 phase 签名缺失', stale.status !== 0
    && /快照内产品 index\.js 携带当前 phase 探针/.test(stale.stdout)
    && /phase 签名缺失/.test(stale.stdout), (stale.stdout + stale.stderr).trim().split('\n').slice(-4).join(' | '))

  const absent = run(missingTargetTar)
  check('缺 target tar 被真 CLI 拒绝并点名目标成员', absent.status !== 0
    && /快照内产品 index\.js 携带当前 phase 探针/.test(absent.stdout)
    && absent.stdout.includes(target), (absent.stdout + absent.stderr).trim().split('\n').slice(-4).join(' | '))

  const malformed = run(badTar)
  check('坏 tar 被真 CLI 拒绝（不把不可读档案当成无关 SKIP）', malformed.status !== 0
    && /快照内产品 index\.js 携带当前 phase 探针/.test(malformed.stdout),
  (malformed.stdout + malformed.stderr).trim().split('\n').slice(-4).join(' | '))

  const noPhaseOutput = run(missingOutputTar)
  check('缺关键 phase 输出格式的 tar 被真 CLI 拒绝', noPhaseOutput.status !== 0
    && /快照内产品 index\.js 携带当前 phase 探针/.test(noPhaseOutput.stdout)
    && noPhaseOutput.stdout.includes('[perf] phase name=${phase.name}'),
  (noPhaseOutput.stdout + noPhaseOutput.stderr).trim().split('\n').slice(-4).join(' | '))
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

if (failures.length) {
  console.error(`check-perf-instrumentation.test: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('check-perf-instrumentation.test: 全部 CLI 产物反例通过')
