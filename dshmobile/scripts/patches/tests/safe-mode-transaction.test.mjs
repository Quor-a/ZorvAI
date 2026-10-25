import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'

const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = join(HERE, 'fixtures/safe-mode-policy.yml')
const EXPECTED = join(HERE, 'fixtures/safe-mode-policy.expected.yml')

test('CLI default web Safe Mode shares flat root, follows a sole scoped legacy marker, and rejects dual markers', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-root-route-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const home = join(temp, 'files', 'home', '.dsh')
  const profile = join(home, 'profiles', 'web')
  const patch = join(profile, 'cordis.patch.yml')
  const original = Buffer.from('original user patch\n')
  mkdirSync(profile, { recursive: true })
  writeOwnershipManifest(join(temp, 'files'))
  writeFileSync(patch, 'safe mode live patch\n')
  const undo = join(home, 'undo-snapshots')
  const scopedAuto = join(undo, 'web', 'auto')
  mkdirSync(scopedAuto, { recursive: true })
  const id = 'legacy-scope'
  const backup = join(scopedAuto, `safe-mode-backup-${id}.yml`)
  writeFileSync(backup, original)
  writeFileSync(join(scopedAuto, 'safe-mode.json'), JSON.stringify({
    active: true, profile: 'web', snapshotId: id, backup, backupSha256: createHash('sha256').update(original).digest('hex'), homeExisted: false,
  }))
  process.env.DSH_HOME = home
  delete process.env.DSH_UNDO_ROOT
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?root-route=${Math.random()}`)
    assert.equal(safeMode('off'), true, 'existing scoped state chooses its own bound backups')
    assert.deepEqual(readFileSync(patch), original)

    writeFileSync(join(scopedAuto, 'safe-mode.json'), 'broken scoped marker')
    const flatAuto = join(undo, 'auto')
    mkdirSync(flatAuto, { recursive: true })
    writeFileSync(join(flatAuto, 'safe-mode-state.json'), 'broken flat marker')
    const before = readFileSync(patch)
    assert.equal(safeMode('on'), false, 'any markers in both locations are a conflict, even malformed markers')
    assert.deepEqual(readFileSync(patch), before)
    assert.equal(existsSync(join(flatAuto, 'safe-mode.json')), false)

    rmSync(join(scopedAuto, 'safe-mode.json'), { force: true })
    rmSync(join(flatAuto, 'safe-mode-state.json'), { force: true })
    writeFileSync(patch, readFileSync(FIXTURE))
    assert.equal(safeMode('on'), true)
    assert.equal(existsSync(join(flatAuto, 'safe-mode.json')), true, 'new default web state uses shell-shared flat root')
    assert.equal(existsSync(join(scopedAuto, 'safe-mode.json')), false)
    assert.equal(safeMode('off'), true)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('CLI Safe Mode removes Soft bundle composition, restores package bytes, and rejects missing factory identities', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-bundle-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files'), home = join(filesDir, 'home', '.dsh'), profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  const patch = join(profile, 'cordis.patch.yml'), pkgPath = join(profile, 'package.json')
  const originalPatch = readFileSync(FIXTURE)
  const factoryPatch = Buffer.from('- config:\n    factory-safe: true\n')
  const factoryBundle = { name: 'factory-good', version: '1.2.3', patchSha256: framedBundleSha('patch.yml', factoryPatch) }
  writeOwnershipManifest(filesDir, [factoryBundle])
  const originalPkg = Buffer.from(JSON.stringify({ name: 'web', 'dsh.profile.bundles': ['dotted-only'], dsh: { profile: { bundles: ['factory-good', 'user-soft-bundle'] } } }, null, 2) + '\n')
  writeFileSync(patch, originalPatch); writeFileSync(pkgPath, originalPkg)
  const factoryDir = join(profile, 'node_modules/factory-good')
  mkdirSync(factoryDir, { recursive: true })
  writeFileSync(join(factoryDir, 'package.json'), JSON.stringify({ name: 'factory-good', version: '1.2.3', dsh: { bundle: { patch: 'patch.yml' } } }))
  writeFileSync(join(factoryDir, 'patch.yml'), factoryPatch)
  const bundleDir = join(profile, 'node_modules/user-soft-bundle')
  mkdirSync(bundleDir, { recursive: true })
  const softPatch = '- insert:\n  - id: user-soft-override\n    name: user-soft-bundle\n- config:\n    endpoint: user-value\n'
  writeFileSync(join(bundleDir, 'package.json'), JSON.stringify({ name: 'user-soft-bundle', version: '1.0.0', dsh: { bundle: { patch: 'cordis.patch.yml' } } }))
  writeFileSync(join(bundleDir, 'cordis.patch.yml'), softPatch)
  process.env.DSH_HOME = home; process.env.DSH_UNDO_ROOT = join(temp, 'undo'); process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?bundle=${Math.random()}`)
    assert.equal(safeMode('on'), true)
    const safeBundles = JSON.parse(readFileSync(pkgPath, 'utf8')).dsh.profile.bundles
    assert.deepEqual(safeBundles, ['factory-good'])
    assert.deepEqual(JSON.parse(readFileSync(pkgPath, 'utf8'))["dsh.profile.bundles"], ['dotted-only'], 'rc2 nested runtime field wins over a dotted literal key')
    const safeComposition = (safeBundles.includes('factory-good') ? factoryPatch.toString('utf8') : '') + (safeBundles.includes('user-soft-bundle') ? softPatch : '') + readFileSync(patch, 'utf8')
    assert.equal(safeComposition.includes('user-soft-override'), false, 'removed bundle cannot contribute its insert/config override')
    assert.equal(safeComposition.includes('factory-safe: true'), true, 'exact factory bundle remains composed')
    assert.equal(safeMode('off'), true)
    assert.deepEqual(readFileSync(pkgPath), originalPkg)
    assert.deepEqual(readFileSync(patch), originalPatch)

    writeFileSync(pkgPath, originalPkg)
    const manifestPath = join(filesDir, '.plugin-hard-manifest.json')
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')); delete manifest.factoryBundles
    writeFileSync(manifestPath, JSON.stringify(manifest))
    const beforePatch = readFileSync(patch), beforePkg = readFileSync(pkgPath)
    assert.equal(safeMode('on'), false, 'active bundle list without trusted factory identities must fail closed')
    assert.deepEqual(readFileSync(patch), beforePatch)
    assert.deepEqual(readFileSync(pkgPath), beforePkg)
    assert.equal(existsSync(join(process.env.DSH_UNDO_ROOT, 'web', 'auto', 'safe-mode.json')), false)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI keeps recovery marker across interrupted writes and round-trips fixture', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const undo = join(temp, 'undo-alias')
  const realUndo = join(temp, 'undo-real')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  writeOwnershipManifest(filesDir)
  const original = readFileSync(FIXTURE)
  const patch = join(profile, 'cordis.patch.yml')
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  mkdirSync(join(realUndo, 'web', 'auto'), { recursive: true })
  symlinkSync(realUndo, undo, 'dir')
  process.env.DSH_UNDO_ROOT = undo
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?test=${Math.random()}`)
    const ok = safeMode('on', { atomicWrite(target, bytes) {
      if (target === patch) throw new Error('injected interruption')
      return (awaitWrite(target, bytes))
    } })
    assert.equal(ok, false)
    const state = join(undo, 'web', 'auto', 'safe-mode.json')
    assert.equal(existsSync(state), true)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(safeMode('off'), true)
    assert.equal(safeMode('on'), true)
    assert.equal(readFileSync(patch, 'utf8'), readFileSync(EXPECTED, 'utf8'))
    assert.equal(safeMode('off'), true)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(existsSync(state), false)
    assert.equal(safeMode('off'), true)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI accepts autoDir symlink aliases and rejects backup paths outside autoDir', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-boundary-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const realUndo = join(temp, 'undo-real')
  const undo = join(temp, 'undo-alias')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  mkdirSync(filesDir, { recursive: true })
  writeOwnershipManifest(filesDir)
  mkdirSync(join(realUndo, 'web', 'auto'), { recursive: true })
  symlinkSync(realUndo, undo, 'dir')
  const patch = join(profile, 'cordis.patch.yml')
  const original = readFileSync(FIXTURE)
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = undo
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?boundary=${Math.random()}`)
    assert.equal(safeMode('on'), true)
    const statePath = join(undo, 'web', 'auto', 'safe-mode.json')
    const state = JSON.parse(readFileSync(statePath, 'utf8'))
    const outside = join(temp, 'outside', basename(state.backup))
    mkdirSync(dirname(outside), { recursive: true })
    writeFileSync(outside, original)
    writeFileSync(statePath, JSON.stringify({ ...state, backup: outside }))
    assert.equal(safeMode('off'), false)
    assert.equal(existsSync(statePath), true)
    writeFileSync(statePath, JSON.stringify(state))
    assert.equal(safeMode('off'), true)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(existsSync(statePath), false)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI refuses Safe Mode when the exact ownership manifest is absent', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-no-manifest-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const home = join(temp, 'files', 'home', '.dsh')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  const original = readFileSync(FIXTURE)
  const patch = join(profile, 'cordis.patch.yml')
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = join(temp, 'undo')
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?no-manifest=${Math.random()}`)
    assert.equal(safeMode('on'), false)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(existsSync(join(temp, 'undo', 'web', 'auto', 'safe-mode.json')), false)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI selects the transaction sidecar during online probation', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-online-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const profileDir = join(home, 'profiles', 'web')
  const autoDir = join(home, 'undo-snapshots', 'auto')
  mkdirSync(profileDir, { recursive: true })
  writeOwnershipManifest(filesDir)
  const base = 'a'.repeat(64), archive = 'b'.repeat(64)
  const cachePath = join(filesDir, '.plugin-hard-manifest.json')
  const cache = JSON.parse(readFileSync(cachePath, 'utf8'))
  const onlineHard = { id: 'online-hard', name: '@vendor/online-hard' }
  writeFileSync(join(filesDir, `.plugin-hard-manifest-online-${archive}.json`), JSON.stringify({
    schema: 2, complete: true, fingerprint: archive, baseFingerprint: base,
    entries: [...cache.entries, onlineHard], profileEntries: cache.profileEntries,
  }))
  writeFileSync(join(filesDir, '.snapshot-transaction'), [
    'phase=SWAPPED', 'purpose=ONLINE_UPDATE', `fingerprint=${archive}`,
    `baseFingerprint=${base}`, `priorFingerprint=${base}`,
  ].join('\n'))
  const patch = join(profileDir, 'cordis.patch.yml')
  writeFileSync(patch, readFileSync(FIXTURE, 'utf8') + `- insert:\n  - id: online-hard\n    name: '@vendor/online-hard'\n`)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = join(temp, 'undo')
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?online=${Math.random()}`)
    assert.equal(safeMode('on'), true)
    assert.match(readFileSync(patch, 'utf8'), /id: online-hard/)
    assert.equal(safeMode('off'), true)
    writeFileSync(join(filesDir, '.snapshot-transaction'), `phase=ONLINE_COMMITTED\npurpose=FACTORY\nfingerprint=${archive}\n`)
    const beforeUnknownMarker = readFileSync(patch)
    assert.equal(safeMode('on'), false, 'CLI must reject an ONLINE_COMMITTED marker with incompatible purpose')
    assert.equal(readFileSync(patch).equals(beforeUnknownMarker), true, 'unknown marker must not mutate user data')
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI restores profile and home from content-addressed recovery copies and fails closed when home copies fail', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-recovery-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const profile = join(home, 'profiles', 'web')
  const undo = join(temp, 'undo')
  mkdirSync(profile, { recursive: true })
  writeOwnershipManifest(filesDir)
  const patch = join(profile, 'cordis.patch.yml')
  const homePatch = join(home, 'cordis.patch.yml')
  const original = readFileSync(FIXTURE)
  const homeOriginal = Buffer.from('- id: user-home\n  name: user-home-plugin\n')
  writeFileSync(patch, original)
  writeFileSync(homePatch, homeOriginal)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = undo
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?recovery=${Math.random()}`)
    assert.equal(safeMode('on'), true)
    const autoDir = join(undo, 'web', 'auto')
    const markerPath = join(autoDir, 'safe-mode.json')
    const marker = JSON.parse(readFileSync(markerPath, 'utf8'))
    const recoveryDir = join(undo, 'web', 'safe-mode-recovery')
    assert.equal(existsSync(join(recoveryDir, `safe-mode-${marker.backupSha256}.yml`)), true)
    assert.equal(existsSync(join(recoveryDir, `safe-mode-${marker.homeBackupSha256}.yml`)), true)
    rmSync(marker.backup)
    writeFileSync(marker.homeBackup, 'damaged-home-primary')
    assert.equal(safeMode('off'), true)
    assert.deepEqual(readFileSync(patch), original)
    assert.deepEqual(readFileSync(homePatch), homeOriginal)

    assert.equal(safeMode('on'), true)
    const second = JSON.parse(readFileSync(markerPath, 'utf8'))
    writeFileSync(second.homeBackup, 'damaged-home-primary')
    writeFileSync(join(recoveryDir, `safe-mode-${second.homeBackupSha256}.yml`), 'damaged-home-copy')
    const safePatch = readFileSync(patch)
    const safeHome = readFileSync(homePatch)
    assert.equal(safeMode('off'), false)
    assert.deepEqual(readFileSync(patch), safePatch)
    assert.deepEqual(readFileSync(homePatch), safeHome)
    assert.equal(existsSync(markerPath), true)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI refuses entry if recovery copy write fails and reuses a valid content object', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-recovery-write-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const profile = join(home, 'profiles', 'web')
  const undo = join(temp, 'undo')
  mkdirSync(profile, { recursive: true })
  writeOwnershipManifest(filesDir)
  const patch = join(profile, 'cordis.patch.yml')
  const original = readFileSync(FIXTURE)
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = undo
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?recovery-write=${Math.random()}`)
    const refused = safeMode('on', { atomicWrite(target, bytes) {
      if (target.includes('safe-mode-recovery')) throw new Error('injected recovery write failure')
      return awaitWrite(target, bytes)
    } })
    assert.equal(refused, false)
    assert.deepEqual(readFileSync(patch), original)
    assert.equal(existsSync(join(undo, 'web', 'auto', 'safe-mode.json')), false)

    assert.equal(safeMode('on'), true)
    const markerPath = join(undo, 'web', 'auto', 'safe-mode.json')
    const marker = JSON.parse(readFileSync(markerPath, 'utf8'))
    rmSync(markerPath)
    writeFileSync(patch, original)
    let recoveryWrites = 0
    assert.equal(safeMode('on', { atomicWrite(target, bytes) {
      if (target.includes('safe-mode-recovery')) recoveryWrites += 1
      return awaitWrite(target, bytes)
    } }), true)
    assert.equal(recoveryWrites, 0, 'an existing valid content-addressed object must be reused')
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

function awaitWrite(target, bytes) { writeFileSync(target, bytes) }

function framedBundleSha(relativePath, bytes) {
  const hash = createHash('sha256').update(Buffer.from('DSHBNDL1', 'ascii'))
  const path = Buffer.from(relativePath, 'utf8'), pathLength = Buffer.alloc(4), contentLength = Buffer.alloc(8)
  pathLength.writeUInt32BE(path.length); contentLength.writeBigUInt64BE(BigInt(bytes.length))
  return hash.update(pathLength).update(path).update(contentLength).update(bytes).digest('hex')
}

function writeOwnershipManifest(filesDir, factoryBundles = []) {
  const fingerprint = 'a'.repeat(64)
  mkdirSync(filesDir, { recursive: true })
  writeFileSync(join(filesDir, '.snapshot-fingerprint'), fingerprint)
  writeFileSync(join(filesDir, '.plugin-hard-manifest.json'), JSON.stringify({
    schema: 2, complete: true, fingerprint,
    entries: [
      { id: 'mobile-hard', name: '@dsh-android/dsh-shell-termux' },
      { id: 'upstream-hard', name: '@deepseek-ai/dsh-shipped-core' },
      { id: 'undo', name: 'dsh-undo-savepoint' },
      { id: 'disabled-product', name: '@dsh-android/dsh-host-web-compat' },
    ],
    profileEntries: [
      { id: 'mobile-hard', name: '@dsh-android/dsh-shell-termux' },
      { id: 'disabled-product', name: '@dsh-android/dsh-host-web-compat' },
    ],
    factoryBundles,
  }))
}
