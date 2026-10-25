import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, writeFile, rm, access, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { safeModeSet } from '../lib/core.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = join(HERE, '../../../scripts/patches/tests/fixtures/cordis.patch.yml')
function framedBundleSha(relativePath, bytes) {
  const hash = createHash('sha256').update(Buffer.from('DSHBNDL1', 'ascii'))
  const path = Buffer.from(relativePath, 'utf8'), pathLength = Buffer.alloc(4), contentLength = Buffer.alloc(8)
  pathLength.writeUInt32BE(path.length); contentLength.writeBigUInt64BE(BigInt(bytes.length))
  return hash.update(pathLength).update(path).update(contentLength).update(bytes).digest('hex')
}
const exists = async (p) => { try { await access(p); return true } catch { return false } }
async function writeOwnershipFilesRoot(homeDir, factoryBundles = []) {
  const filesRoot = dirname(dirname(homeDir))
  const fingerprint = 'a'.repeat(64)
  const entry = { id: 'test-hard', name: '@dsh-android/test-hard' }
  await mkdir(filesRoot, { recursive: true })
  await writeFile(join(filesRoot, '.snapshot-fingerprint'), fingerprint)
  await writeFile(join(filesRoot, '.plugin-hard-manifest.json'), JSON.stringify({
    schema: 2, complete: true, fingerprint, entries: [entry], profileEntries: [entry], factoryBundles,
  }))
}

test('vendor safe mode validates package backup, retains marker on interruption, and restores bytes', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-core-'))
  const homeDir = join(temp, 'files', 'home', '.dsh')
  const profileDir = join(homeDir, 'profiles', 'web')
  const autoDir = join(temp, 'undo', 'auto')
  const manualDir = join(temp, 'undo', 'manual')
  await mkdir(profileDir, { recursive: true })
  await writeOwnershipFilesRoot(homeDir)
  const patch = join(profileDir, 'cordis.patch.yml')
  const pkg = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  const originalPkg = Buffer.from('{\n  "dsh": { "profile": { "bundles": ["missing-safe-mode-test-bundle"] } }\n}\n')
  await writeFile(patch, originalPatch)
  await writeFile(pkg, originalPkg)
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  try {
    const on = await safeModeSet(cfg, true, { async atomicWrite(target, bytes) {
      if (target === pkg) throw new Error('injected interruption')
      const { writeFile, rename, rm } = await import('node:fs/promises')
      const tmp = target + '.test-tmp'
      await writeFile(tmp, bytes)
      await rename(tmp, target)
      await rm(tmp, { force: true })
    } })
    assert.equal(on.ok, false)
    const statePath = join(autoDir, 'safe-mode.json')
    assert.equal(await exists(statePath), true)
    const state = JSON.parse(await readFile(statePath, 'utf8'))
    assert.equal(await exists(state.backup), true)
    assert.equal(await exists(state.pkgBackup), true)
    const refused = await safeModeSet(cfg, false, { async atomicWrite(target, bytes) {
      const { writeFile, rename } = await import('node:fs/promises')
      const tmp = target + '.test-tmp'
      await writeFile(tmp, bytes); await rename(tmp, target)
    } })
    assert.equal(refused.ok, true)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.deepEqual(await readFile(pkg), originalPkg)
    assert.equal(await exists(statePath), false)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})

test('vendor safe mode restores through an autoDir symlink and rejects an out-of-root backup', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-symlink-'))
  const homeDir = join(temp, 'files', 'home', '.dsh')
  const profileDir = join(homeDir, 'profiles', 'web')
  const realAutoDir = join(temp, 'undo-real', 'auto')
  const autoDir = join(temp, 'undo-alias', 'auto')
  const manualDir = join(temp, 'undo-alias', 'manual')
  await mkdir(profileDir, { recursive: true })
  await writeOwnershipFilesRoot(homeDir)
  await mkdir(realAutoDir, { recursive: true })
  await mkdir(dirname(autoDir), { recursive: true })
  await symlink(realAutoDir, autoDir, 'dir')
  const patch = join(profileDir, 'cordis.patch.yml')
  const pkg = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  const originalPkg = Buffer.from('{\n  "dsh": { "profile": { "bundles": ["missing-safe-mode-test-bundle"] } }\n}\n')
  await writeFile(patch, originalPatch)
  await writeFile(pkg, originalPkg)
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  try {
    const on = await safeModeSet(cfg, true, { async atomicWrite(target, bytes) {
      if (target === pkg) throw new Error('injected interruption')
      await writeFile(target, bytes)
    } })
    assert.equal(on.ok, false)
    const statePath = join(autoDir, 'safe-mode.json')
    const state = JSON.parse(await readFile(statePath, 'utf8'))
    assert.equal(dirname(state.backup), autoDir)

    // A symlink alias is accepted for the parent, but the stored filename and
    // real file must remain directly inside the configured auto directory.
    const refusedState = { ...state, backup: join(temp, 'outside', basename(state.backup)) }
    await mkdir(dirname(refusedState.backup), { recursive: true })
    await writeFile(refusedState.backup, originalPatch)
    await writeFile(statePath, JSON.stringify(refusedState))
    const refused = await safeModeSet(cfg, false)
    assert.equal(refused.ok, false)
    assert.equal(await exists(statePath), true)

    await writeFile(statePath, JSON.stringify(state))
    const restored = await safeModeSet(cfg, false)
    assert.equal(restored.ok, true)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.deepEqual(await readFile(pkg), originalPkg)
    assert.equal(await exists(statePath), false)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})

test('vendor safe mode recovers patch, home patch, and package from shared content-addressed copies', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-core-recovery-'))
  const homeDir = join(temp, 'files', 'home', '.dsh')
  const profileDir = join(homeDir, 'profiles', 'web')
  const autoDir = join(temp, 'undo', 'auto')
  const manualDir = join(temp, 'undo', 'manual')
  await mkdir(profileDir, { recursive: true })
  await writeOwnershipFilesRoot(homeDir)
  const patch = join(profileDir, 'cordis.patch.yml')
  const homePatch = join(homeDir, 'cordis.patch.yml')
  const pkg = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  const originalHome = Buffer.from('- id: user-home\n  name: user-home-plugin\n')
  const originalPkg = Buffer.from('{"dsh":{"profile":{"bundles":[]}}}\n')
  await writeFile(patch, originalPatch)
  await writeFile(homePatch, originalHome)
  await writeFile(pkg, originalPkg)
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  try {
    assert.equal((await safeModeSet(cfg, true)).ok, true)
    const statePath = join(autoDir, 'safe-mode.json')
    const state = JSON.parse(await readFile(statePath, 'utf8'))
    const recoveryDir = join(temp, 'undo', 'safe-mode-recovery')
    for (const sha of [state.backupSha256, state.homeBackupSha256, state.pkgBackupSha256]) {
      assert.equal(await exists(join(recoveryDir, `safe-mode-${sha}.yml`)), true)
    }
    await rm(state.backup)
    await writeFile(state.homeBackup, 'damaged home primary')
    await writeFile(state.pkgBackup, 'damaged package primary')
    const off = await safeModeSet(cfg, false)
    assert.equal(off.ok, true, off.error)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.deepEqual(await readFile(homePatch), originalHome)
    assert.deepEqual(await readFile(pkg), originalPkg)

    assert.equal((await safeModeSet(cfg, true)).ok, true)
    const second = JSON.parse(await readFile(statePath, 'utf8'))
    await writeFile(second.homeBackup, 'damaged home primary')
    await writeFile(join(recoveryDir, `safe-mode-${second.homeBackupSha256}.yml`), 'damaged home recovery')
    const safePatch = await readFile(patch)
    const safeHome = await readFile(homePatch)
    const safePkg = await readFile(pkg)
    const refused = await safeModeSet(cfg, false)
    assert.equal(refused.ok, false)
    assert.deepEqual(await readFile(patch), safePatch)
    assert.deepEqual(await readFile(homePatch), safeHome)
    assert.deepEqual(await readFile(pkg), safePkg)
    assert.equal(await exists(statePath), true)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})

test('vendor Safe Mode refuses entry when recovery write fails and reuses valid content objects', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-core-recovery-write-'))
  const homeDir = join(temp, 'files', 'home', '.dsh')
  const profileDir = join(homeDir, 'profiles', 'web')
  const autoDir = join(temp, 'undo', 'auto')
  const manualDir = join(temp, 'undo', 'manual')
  await mkdir(profileDir, { recursive: true })
  await writeOwnershipFilesRoot(homeDir)
  const patch = join(profileDir, 'cordis.patch.yml')
  const pkg = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  await writeFile(patch, originalPatch)
  await writeFile(pkg, '{"dsh":{"profile":{"bundles":[]}}}\n')
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  const write = async (target, bytes) => {
    const { writeFile, rename } = await import('node:fs/promises')
    const tmp = target + '.test-tmp'
    await writeFile(tmp, bytes)
    await rename(tmp, target)
  }
  try {
    const refused = await safeModeSet(cfg, true, { async atomicWrite(target, bytes) {
      if (target.includes(`${join('undo', 'safe-mode-recovery')}`)) throw new Error('injected recovery write failure')
      await write(target, bytes)
    } })
    assert.equal(refused.ok, false)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.equal(await exists(join(autoDir, 'safe-mode.json')), false)

    assert.equal((await safeModeSet(cfg, true)).ok, true)
    const statePath = join(autoDir, 'safe-mode.json')
    await rm(statePath)
    await writeFile(patch, originalPatch)
    let recoveryWrites = 0
    const reused = await safeModeSet(cfg, true, { async atomicWrite(target, bytes) {
      if (target.includes(`${join('undo', 'safe-mode-recovery')}`)) recoveryWrites += 1
      await write(target, bytes)
    } })
    assert.equal(reused.ok, true, reused.error)
    assert.equal(recoveryWrites, 0)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})

test('default web Safe Mode routes new state to flat root, adopts scoped legacy state, and rejects dual markers', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-core-root-route-'))
  const homeDir = join(temp, 'files', 'home', '.dsh')
  const profileDir = join(homeDir, 'profiles', 'web')
  const root = join(temp, 'undo')
  const autoDir = join(root, 'auto')
  const manualDir = join(root, 'manual')
  await mkdir(profileDir, { recursive: true })
  await writeOwnershipFilesRoot(homeDir)
  const patch = join(profileDir, 'cordis.patch.yml')
  const homePatch = join(homeDir, 'cordis.patch.yml')
  const originalPatch = await readFile(FIXTURE)
  const originalHome = Buffer.from('home-level original\n')
  await writeFile(patch, originalPatch)
  await writeFile(homePatch, originalHome)
  await mkdir(join(root, 'web', 'auto'), { recursive: true }) // Existing scoped store alone is not authority.
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', safeModeDefaultWebStore: true, autoEnabled: false, pluginDirs: [] }
  try {
    assert.equal((await safeModeSet(cfg, true)).ok, true)
    const flatState = join(autoDir, 'safe-mode.json')
    assert.equal(await exists(flatState), true, 'fresh default web state is flat for Kotlin/CLI sharing')
    const state = JSON.parse(await readFile(flatState, 'utf8'))
    const scopedRoot = join(root, 'web')
    const scopedAuto = join(scopedRoot, 'auto')
    await mkdir(scopedAuto, { recursive: true })
    const movedPatchBackup = join(scopedAuto, 'safe-mode-backup-legacy.yml')
    const movedHomeBackup = join(scopedAuto, 'safe-mode-home-backup-legacy.yml')
    await writeFile(movedPatchBackup, await readFile(state.backup))
    await writeFile(movedHomeBackup, await readFile(state.homeBackup))
    state.snapshotId = 'legacy'
    state.backup = movedPatchBackup
    state.homeBackup = movedHomeBackup
    await writeFile(join(scopedAuto, 'safe-mode.json'), JSON.stringify(state))
    await rm(flatState)
    const off = await safeModeSet(cfg, false)
    assert.equal(off.ok, true, off.error)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.deepEqual(await readFile(homePatch), originalHome)

    await writeFile(flatState, 'malformed flat marker')
    await writeFile(join(scopedAuto, 'safe-mode.json'), 'malformed scoped marker')
    const beforePatch = await readFile(patch)
    const beforeHome = await readFile(homePatch)
    const refused = await safeModeSet(cfg, true)
    assert.equal(refused.ok, false)
    assert.match(refused.error, /state conflict/)
    assert.deepEqual(await readFile(patch), beforePatch)
    assert.deepEqual(await readFile(homePatch), beforeHome)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})

test('vendor Safe Mode removes Soft bundle composition, restores package bytes, and rejects missing factory identities', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-core-bundle-'))
  const homeDir = join(temp, 'files', 'home', '.dsh')
  const profileDir = join(homeDir, 'profiles', 'web')
  const autoDir = join(temp, 'undo', 'auto')
  const manualDir = join(temp, 'undo', 'manual')
  await mkdir(profileDir, { recursive: true })
  const patch = join(profileDir, 'cordis.patch.yml')
  const pkgPath = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  const factoryPatch = Buffer.from('- config:\n    factory-safe: true\n')
  const factoryBundle = { name: 'factory-good', version: '1.2.3', patchSha256: framedBundleSha('patch.yml', factoryPatch) }
  await writeOwnershipFilesRoot(homeDir, [factoryBundle])
  const originalPkg = Buffer.from(JSON.stringify({ name: 'web', 'dsh.profile.bundles': ['dotted-only'], dsh: { profile: { bundles: ['factory-good', 'user-soft-bundle'] } } }, null, 2) + '\n')
  await writeFile(patch, originalPatch)
  await writeFile(pkgPath, originalPkg)
  const factoryDir = join(profileDir, 'node_modules/factory-good')
  await mkdir(factoryDir, { recursive: true })
  await writeFile(join(factoryDir, 'package.json'), JSON.stringify({ name: 'factory-good', version: '1.2.3', dsh: { bundle: { patch: 'patch.yml' } } }))
  await writeFile(join(factoryDir, 'patch.yml'), factoryPatch)
  const bundleDir = join(profileDir, 'node_modules/user-soft-bundle')
  await mkdir(bundleDir, { recursive: true })
  const softPatch = '- insert:\n  - id: user-soft-override\n    name: user-soft-bundle\n- config:\n    endpoint: user-value\n'
  await writeFile(join(bundleDir, 'package.json'), JSON.stringify({ name: 'user-soft-bundle', version: '1.0.0', dsh: { bundle: { patch: 'cordis.patch.yml' } } }))
  await writeFile(join(bundleDir, 'cordis.patch.yml'), softPatch)
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  try {
    assert.equal((await safeModeSet(cfg, true)).ok, true)
    const safeBundles = JSON.parse(await readFile(pkgPath, 'utf8')).dsh.profile.bundles
    assert.deepEqual(safeBundles, ['factory-good'])
    assert.deepEqual(JSON.parse(await readFile(pkgPath, 'utf8'))["dsh.profile.bundles"], ['dotted-only'], 'rc2 nested runtime field wins over a dotted literal key')
    const safeComposition = (safeBundles.includes('factory-good') ? factoryPatch.toString('utf8') : '') + (safeBundles.includes('user-soft-bundle') ? softPatch : '') + (await readFile(patch, 'utf8'))
    assert.equal(safeComposition.includes('user-soft-override'), false)
    assert.equal(safeComposition.includes('factory-safe: true'), true, 'exact factory bundle remains composed')
    assert.equal((await safeModeSet(cfg, false)).ok, true)
    assert.deepEqual(await readFile(pkgPath), originalPkg)
    assert.deepEqual(await readFile(patch), originalPatch)

    const manifestPath = join(dirname(dirname(homeDir)), '.plugin-hard-manifest.json')
    const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
    delete manifest.factoryBundles
    await writeFile(manifestPath, JSON.stringify(manifest))
    const beforePatch = await readFile(patch), beforePkg = await readFile(pkgPath)
    const refused = await safeModeSet(cfg, true)
    assert.equal(refused.ok, false)
    assert.match(refused.error, /factory bundle identities unavailable/)
    assert.deepEqual(await readFile(patch), beforePatch)
    assert.deepEqual(await readFile(pkgPath), beforePkg)
    assert.equal(await exists(join(autoDir, 'safe-mode.json')), false)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})
