import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'

const HERE = dirname(fileURLToPath(import.meta.url))
const input = readFileSync(join(HERE, 'fixtures/safe-mode-policy.yml'), 'utf8')
const expected = readFileSync(join(HERE, 'fixtures/safe-mode-policy.expected.yml'), 'utf8')
// 本文件在协调仓与 APK 仓是逐字节镜像，但两仓的相对层级不同：
//   协调仓 <root>/scripts/patches/tests  → <root>/vendor/... 与 <root>/dsh-mobile-apk/vendor/...
//   APK 仓  <root>/scripts/patches/tests  → <root>/vendor/...（再往上没有第二个仓）
// 所以这里**向上逐层探测**，而不是写死三段 ../。写死会让 APK 侧在加载期直接抛错——
// 整个文件被判失败，连它本可以覆盖的那一份实现都测不到（实测：APK 侧 1 个文件、0 通过）。
const vendorCores = (() => {
  const found = []
  let dir = HERE
  for (let depth = 0; depth < 6; depth += 1) {
    for (const rel of ['vendor/dsh-undo-savepoint/lib/core.mjs', 'dsh-mobile-apk/vendor/dsh-undo-savepoint/lib/core.mjs']) {
      const candidate = join(dir, rel)
      if (existsSync(candidate) && !found.includes(candidate)) found.push(candidate)
    }
    dir = dirname(dir)
  }
  return found
})()
if (vendorCores.length === 0) throw new Error('no vendor Safe Mode implementation found from ' + HERE)
if (vendorCores.length === 2 && !readFileSync(vendorCores[0]).equals(readFileSync(vendorCores[1]))) {
  throw new Error('root and APK vendor Safe Mode implementations diverged: ' + vendorCores.join(' vs '))
}
const vendorPolicies = await Promise.all(vendorCores.map(async (path) => ({
  path,
  filter: (await import(pathToFileURL(path).href)).safeModeFilterInserts,
})))
const hardEntries = [
  { id: 'mobile-hard', name: '@dsh-android/dsh-shell-termux' },
  { id: 'upstream-hard', name: '@deepseek-ai/dsh-shipped-core' },
  { id: 'undo', name: 'dsh-undo-savepoint' },
  { id: 'disabled-product', name: '@dsh-android/dsh-host-web-compat' },
]

test('Safe Mode policy uses exact product entry identities; publisher namespace does not grant ownership', () => {
  for (const { path, filter } of vendorPolicies) {
    assert.equal(filter(input, hardEntries), expected, path)
    const duplicateName = input.replace("name: '@deepseek-ai/dsh-mcp-client'", "name: '@deepseek-ai/dsh-shipped-core'")
    assert.ok(filter(duplicateName, hardEntries).includes('id: user-official') === false, path)
    assert.ok(filter(input.replace('id: mobile-hard', 'id: user-created'), hardEntries).includes('id: user-created') === false, path)
  }
})

test('generated engine Safe Mode snippet follows the same golden policy', async () => {
  const snippetPath = join(HERE, '../data/undo-safe-transaction-snippet.mjs')
  const source = readFileSync(snippetPath, 'utf8')
  const helperPath = join(HERE, '../data/undo-safe-filter-helper-snippet.mjs')
  const helperSource = readFileSync(helperPath, 'utf8').replace('export function safeModeFilterInserts', 'function safeModeFilterInserts')
  const filter = new Function(`${helperSource}; return safeModeFilterInserts;`)()
  assert.equal(filter(input, hardEntries), expected)
})

test('generated transaction snippet refuses missing or invalid ownership manifest', async () => {
  const snippetPath = join(HERE, '../data/undo-safe-transaction-snippet.mjs')
  const source = readFileSync(snippetPath, 'utf8')
  const helperStart = source.indexOf('async function safeModeOwnershipManifest(filesRoot)')
  const helperEnd = source.indexOf('function safeModeSha256(bytes)', helperStart)
  assert.ok(helperStart >= 0 && helperEnd > helperStart, 'expected transaction-aware ownership manifest helper')
  const body = source.slice(helperStart, helperEnd) + `return (await safeModeOwnershipManifest('files')).entries`
  const evaluate = async (manifest) => {
    const fs = { async readFile() {
      if (manifest === null) throw new Error('missing manifest')
      return JSON.stringify(manifest)
    } }
    const fakeJoin = (_base, name) => name
    fs.readFile = async (path) => {
      if (path === '.plugin-hard-manifest.json') {
        if (manifest === null) throw new Error('missing manifest')
        return JSON.stringify(manifest)
      }
      if (path === '.snapshot-fingerprint') return 'f'.repeat(64)
      throw new Error('ENOENT')
    }
    return new Function('fs', 'join', `return (async () => { ${body} })()`)(fs, fakeJoin)
  }
  const manifest = { schema: 2, complete: true, fingerprint: 'f'.repeat(64), entries: hardEntries, profileEntries: hardEntries }
  assert.deepEqual(await evaluate(manifest), hardEntries)
  await assert.rejects(evaluate(null), /ownership cache\/fingerprint unavailable/)
  await assert.rejects(evaluate({ ...manifest, fingerprint: 'wrong' }), /ownership cache\/fingerprint unavailable/)
})

test('generated align Safe Mode snippet uses exact manifest ownership and refuses missing ownership data', async () => {
  const snippetPath = join(HERE, '../data/undo-safe-align-snippet.mjs')
  const source = readFileSync(snippetPath, 'utf8')
  const marker = '    const dshMobileSafePatchText = await fs.readFile(patch, "utf8");'
  const start = source.indexOf('    const filesRoot =')
  const end = source.indexOf(marker, start)
  assert.ok(start >= 0 && end > start, 'expected manifest-backed policy in the authoritative align snippet')
  const body = source.slice(start, end) + marker + '\n    const minimal = dshMobileSafeFilterInserts(dshMobileSafePatchText);\n    await fs.writeFile(patch, minimal, \'utf8\');'
  const evaluate = async (manifest) => {
    const result = { written: null }
    const fs = {
      async readFile(path) {
        if (path === '.plugin-hard-manifest.json') {
          if (manifest === null) throw new Error('missing manifest')
          return JSON.stringify(manifest)
        }
        if (path === '.snapshot-fingerprint') return 'f'.repeat(64)
        return input
      },
      async writeFile(_path, value) { result.written = value },
    }
    await new Function('fs', 'dirname', 'join', 'DSH_HOME', 'patch', 'result', `return (async () => { ${body}; return result.written; })()`)(
      fs, () => 'files', (_base, name) => name,
      'files/home/.dsh', 'patch', result,
    )
    return result.written
  }
  assert.equal(await evaluate({ schema: 2, complete: true, fingerprint: 'f'.repeat(64), entries: hardEntries, profileEntries: hardEntries }), expected)
  await assert.rejects(evaluate(null), /missing manifest/)
})

test('S1 patch gate rejects a stale marker and repairs its exact helper without weakening checks', () => {
  const temp = mkdtempSync(join(tmpdir(), 'safe-mode-s1-'))
  try {
    const source = readFileSync(vendorCores[0], 'utf8')
    const stale = source.replace('entry.id === id && entry.name === name', 'entry.name === name && (entry.id == null || entry.id === id)')
    const core = join(temp, 'dsh-undo-savepoint/lib/core.mjs')
    const runner = join(HERE, '../apply-patches.mjs')
    mkdirSync(dirname(core), { recursive: true })
    writeFileSync(core, stale)
    const run = (mode) => spawnSync(process.execPath, [runner, temp, mode, '--only', 'undo-safe-align-S1'], { encoding: 'utf8' })
    assert.equal(run('--check').status, 1, 'marker with nullable-id ownership must be rejected')
    assert.equal(run('--apply').status, 0)
    const repaired = readFileSync(core, 'utf8')
    assert.match(repaired, /entry\.id === id && entry\.name === name/)
    assert.doesNotMatch(repaired, /entry\.id == null \|\| entry\.id === id/)
    assert.equal(run('--check').status, 0)
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
})

test('S2 patch gate upgrades legacy transaction code without web-store resolver before allowing check', () => {
  const temp = mkdtempSync(join(tmpdir(), 'safe-mode-s2-legacy-'))
  try {
    const current = readFileSync(vendorCores[0], 'utf8')
    const legacy = current
      .replace('dsh-mobile safe mode transaction (S2) with CAS recovery failover and web-store resolver (S4).', 'dsh-mobile safe mode transaction (S2) with CAS recovery failover (S3).')
      .replace('safeModeRecoveryPath(autoDir, sha, create = false)', 'safeModeRecoveryPathLegacy(autoDir, sha, create = false)')
      .replace('safeModeLoadBackup(cfg, primary, expectedSha)', 'safeModeLoadBackupLegacy(cfg, primary, expectedSha)')
    const core = join(temp, 'dsh-undo-savepoint/lib/core.mjs')
    const runner = join(HERE, '../apply-patches.mjs')
    mkdirSync(dirname(core), { recursive: true })
    writeFileSync(core, legacy)
    const run = (mode) => spawnSync(process.execPath, [runner, temp, mode, '--only', 'undo-safe-transaction-S2'], { encoding: 'utf8' })
    assert.equal(run('--check').status, 1, 'legacy S2 without the CAS contract must fail the gate')
    assert.equal(run('--apply').status, 0)
    const upgraded = readFileSync(core, 'utf8')
    assert.match(upgraded, /factory-bundle filter \(S5\)/)
    assert.match(upgraded, /async function safeModeLoadBackup\(cfg, primary, expectedSha\)/)
    assert.equal(run('--check').status, 0)
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
})

test('S2 gate upgrades old S4 cores that lack trusted factory-bundle isolation', () => {
  const temp = mkdtempSync(join(tmpdir(), 'safe-mode-s4-bundle-'))
  try {
    const current = readFileSync(vendorCores[0], 'utf8')
    const legacy = current
      .replace('with CAS recovery failover, web-store resolver and factory-bundle filter (S5).', 'with CAS recovery failover and web-store resolver (S4).')
      .replace('safeModeSelectFactoryBundles(cfg, pkg, pkgRaw, hardManifest.factoryBundles)', 'safeModeSelectFactoryBundlesLegacy(cfg, pkg, pkgRaw, hardManifest.factoryBundles)')
      .replace('async function safeModeBundlePatchSha(packageRoot, declared)', 'async function safeModeBundlePatchShaLegacy(packageRoot, declared)')
    const core = join(temp, 'dsh-undo-savepoint/lib/core.mjs')
    const runner = join(HERE, '../apply-patches.mjs')
    mkdirSync(dirname(core), { recursive: true })
    writeFileSync(core, legacy)
    const run = (mode) => spawnSync(process.execPath, [runner, temp, mode, '--only', 'undo-safe-transaction-S2'], { encoding: 'utf8' })
    assert.equal(run('--check').status, 1, 'S4 core without the CAS-filter contract must fail the gate')
    assert.equal(run('--apply').status, 0)
    const upgraded = readFileSync(core, 'utf8')
    assert.match(upgraded, /factory-bundle filter \(S5\)/)
    assert.match(upgraded, /safeModeSelectFactoryBundles\(cfg, pkg, pkgRaw, hardManifest\.factoryBundles\)/)
    assert.equal(run('--check').status, 0)
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
})

test('CLI and generated vendor core share the byte-identical recovery object-name helper', () => {
  const transaction = readFileSync(join(HERE, '../data/undo-safe-transaction-snippet.mjs'), 'utf8')
  const cli = readFileSync(join(HERE, '../../dsh-undo-emergency.mjs'), 'utf8')
  const helper = /function safeModeRecoveryObjectName\(sha\) \{\n[\s\S]*?\n\}/
  const extract = (source) => helper.exec(source.replace(/\r\n/g, '\n'))?.[0]
  const canonical = extract(transaction)
  assert.ok(canonical, 'canonical recovery object-name helper is present in the generated transaction snippet')
  assert.equal(extract(cli), canonical, 'single-file emergency CLI keeps the exact shared helper body')
  for (const core of vendorCores) assert.equal(extract(readFileSync(core, 'utf8')), canonical)
})

test('transaction-aware selector follows online swap, committed, staged and rollback ownership', async () => {
  const source = readFileSync(join(HERE, '../data/undo-safe-transaction-snippet.mjs'), 'utf8')
  const start = source.indexOf('async function safeModeOwnershipManifest(filesRoot)')
  const end = source.indexOf('function safeModeSha256(bytes)', start)
  const selectorSource = source.slice(start, end)
  const base = 'a'.repeat(64), archive = 'b'.repeat(64)
  const profile = { id: 'profile', name: '@dsh/profile' }
  const bundle = { id: 'bundle', name: '@upstream/bundle' }
  const cache = { schema: 2, complete: true, fingerprint: base, entries: [profile], profileEntries: [profile] }
  const sidecar = { schema: 2, complete: true, fingerprint: archive, baseFingerprint: base,
    entries: [profile, bundle], profileEntries: [profile] }
  const resolve = async ({ installed = base, marker = null, online = null, side = sidecar } = {}) => {
    const files = {
      '.plugin-hard-manifest.json': JSON.stringify(cache),
      '.snapshot-fingerprint': installed,
      [`.plugin-hard-manifest-online-${archive}.json`]: JSON.stringify(side),
      '.snapshot-transaction': marker && Object.entries(marker).map(([k, v]) => `${k}=${v}`).join('\n'),
      '.online-snapshot': online && Object.entries(online).map(([k, v]) => `${k}=${v}`).join('\n'),
    }
    const fs = { async readFile(path) {
      if (files[path] == null) throw new Error('ENOENT')
      return files[path]
    } }
    return new Function('fs', 'join', `${selectorSource}; return (filesRoot) => safeModeOwnershipManifest(filesRoot)`)(fs, (_base, name) => name)('files')
  }
  assert.deepEqual((await resolve({ marker: { phase: 'SWAPPED', purpose: 'ONLINE_UPDATE', fingerprint: archive, baseFingerprint: base } })).entries, [profile, bundle])
  await assert.rejects(resolve({ marker: { phase: 'SWAPPING', purpose: 'ONLINE_UPDATE', fingerprint: archive, baseFingerprint: base } }), /unknown or incomplete/)
  await assert.rejects(resolve({ marker: { phase: 'UNKNOWN', purpose: 'ONLINE_UPDATE', fingerprint: archive, baseFingerprint: base } }), /unknown or incomplete/)
  await assert.rejects(resolve({ marker: { phase: 'SWAPPED', purpose: 'FUTURE_PURPOSE', fingerprint: archive, baseFingerprint: base } }), /unknown or incomplete/)
  await assert.rejects(resolve({ marker: { phase: 'ONLINE_COMMITTED', purpose: 'FACTORY', fingerprint: archive, baseFingerprint: base } }), /unknown or incomplete/)
  assert.deepEqual((await resolve({ marker: { phase: 'STAGED', purpose: 'ONLINE_UPDATE', fingerprint: archive, baseFingerprint: base, priorFingerprint: base } })).entries, [profile])
  assert.deepEqual((await resolve({ marker: { phase: 'STAGED', fingerprint: base } })).entries, [profile], 'legacy marker without purpose is factory')
  assert.deepEqual((await resolve({ installed: archive, online: { base, archive } })).entries, [profile, bundle])
  await assert.rejects(resolve({ marker: { phase: 'SWAPPED', purpose: 'ONLINE_UPDATE', fingerprint: archive, baseFingerprint: 'c'.repeat(64) } }), /base ownership/)
  await assert.rejects(resolve({ marker: { phase: 'SWAPPED', purpose: 'ONLINE_UPDATE', fingerprint: archive, baseFingerprint: base }, side: { ...sidecar, baseFingerprint: 'c'.repeat(64) } }), /sidecar missing/)
})
