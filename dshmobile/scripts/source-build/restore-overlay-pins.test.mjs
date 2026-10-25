#!/usr/bin/env node
// restore-overlay-pins 的判别力用例（坑 201）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { mergeFirstPartyPins } from './restore-overlay-pins.mjs'

const omitted = [
  { name: '@deepseek-ai/dsh-app-boot', version: '0.1.7-rc.2' },
  { name: '@deepseek-ai/dsh-agent-default-model', version: '0.1.7-rc.2' },
]

test('摘除的钉并回 packages，其余字段原样保留', () => {
  const overlay = {
    engineVersion: '0.1.7-rc.2',
    packages: { '@deepseek-ai/dsh-web-app': '0.1.7-rc.2' },
    vendorTop: { 'partial-json': '0.1.7' },
    pins: { semver: '7.6.0' },
  }
  const merged = mergeFirstPartyPins(overlay, omitted)
  assert.equal(merged.packages['@deepseek-ai/dsh-app-boot'], '0.1.7-rc.2')
  assert.equal(merged.packages['@deepseek-ai/dsh-agent-default-model'], '0.1.7-rc.2')
  assert.equal(merged.packages['@deepseek-ai/dsh-web-app'], '0.1.7-rc.2')
  assert.equal(merged.engineVersion, '0.1.7-rc.2')
  assert.deepEqual(merged.vendorTop, { 'partial-json': '0.1.7' })
  assert.deepEqual(merged.pins, { semver: '7.6.0' })
})

test('不改动入参（纯函数）', () => {
  const overlay = { packages: {} }
  mergeFirstPartyPins(overlay, omitted)
  assert.deepEqual(overlay.packages, {})
})

test('重复归还同一版本幂等', () => {
  const once = mergeFirstPartyPins({ packages: {} }, omitted)
  const twice = mergeFirstPartyPins(once, omitted)
  assert.deepEqual(twice, once)
})

test('同名不同版判红（登记表内部不自洽不得静默择一）', () => {
  const overlay = { packages: { '@deepseek-ai/dsh-app-boot': '0.1.6' } }
  assert.throws(() => mergeFirstPartyPins(overlay, omitted), /第一方 overlay 钉冲突/)
})

test('摘除清单缺席或为空判红（不得在缺钉状态下静默放行）', () => {
  assert.throws(() => mergeFirstPartyPins({ packages: {} }, undefined), /未记录被摘除的第一方 overlay 钉/)
  assert.throws(() => mergeFirstPartyPins({ packages: {} }, []), /未记录被摘除的第一方 overlay 钉/)
})

test('钉形态不合法判红', () => {
  assert.throws(() => mergeFirstPartyPins({ packages: {} }, [{ name: '@deepseek-ai/x' }]), /形态不合法/)
})

test('CLI 落地：文件被改写且 policy 记下还原 provenance', () => {
  const root = mkdtempSync(join(tmpdir(), 'restore-pins-'))
  try {
    const overlayPath = join(root, 'engine-overlay.json')
    const policyPath = join(root, 'source-build-policy.json')
    writeFileSync(overlayPath, JSON.stringify({ engineVersion: '0.1.7-rc.2', packages: {} }, null, 2) + '\n')
    writeFileSync(policyPath, JSON.stringify({ omittedFirstPartyPackageOverlays: omitted }, null, 2) + '\n')
    const stdout = execFileSync(process.execPath, ['scripts/source-build/restore-overlay-pins.mjs', overlayPath, policyPath], { encoding: 'utf8' })
    assert.match(stdout, /restored 2 first-party overlay pins/)
    const overlay = JSON.parse(readFileSync(overlayPath, 'utf8'))
    assert.equal(overlay.packages['@deepseek-ai/dsh-app-boot'], '0.1.7-rc.2')
    const policy = JSON.parse(readFileSync(policyPath, 'utf8'))
    assert.equal(policy.contractOverlayRestoration.packages, 2)
    assert.deepEqual(policy.contractOverlayRestoration.packagesRestored, [
      '@deepseek-ai/dsh-app-boot@0.1.7-rc.2',
      '@deepseek-ai/dsh-agent-default-model@0.1.7-rc.2',
    ])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
