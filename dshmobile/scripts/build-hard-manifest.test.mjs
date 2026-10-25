import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
const generator = join(root, 'scripts', 'build-hard-manifest.mjs')
const factoryName = '@vendor/factory'
const factoryPatches = [['patches/a.yml', 'first patch\n'], ['patches/b.yml', 'second patch\n']]

function fixture(profileText = '', { profile = { dsh: { profile: { bundles: [factoryName] } } }, bundlePackage = { name: factoryName, version: '1.2.3', dsh: { bundle: { patch: factoryPatches.map(([path]) => path) } } }, patchFiles = Object.fromEntries(factoryPatches), profileBundlePackage = null, installBundleSymlink = false } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-hard-manifest-'))
  const archive = join(dir, 'snapshot.tar.xz')
  const output = join(dir, 'manifest.json')
  const py = String.raw`import io,sys,tarfile,json
texts={
 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml': b"- insert:\n  - id: base-entry\n    name: '@vendor/dsh-base'\n",
 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml': b"- insert:\n  - id: web-entry\n    name: '@vendor/dsh-web-app'\n",
 'home/.dsh/profiles/web/cordis.patch.yml': sys.argv[2].encode(),
 'home/.dsh/profiles/web/package.json': json.dumps(json.loads(sys.argv[3])).encode(),
}
if sys.argv[4] != 'null':
  texts['usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@vendor/factory/package.json']=json.dumps(json.loads(sys.argv[4])).encode()
if sys.argv[6] != 'null':
  texts['home/.dsh/profiles/web/node_modules/@vendor/factory/package.json']=json.dumps(json.loads(sys.argv[6])).encode()
for path,text in json.loads(sys.argv[5]).items():
  if sys.argv[4] != 'null': texts['usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@vendor/factory/'+path]=text.encode()
with tarfile.open(sys.argv[1],'w:xz') as archive:
  for path,text in texts.items():
    item=tarfile.TarInfo(path)
    item.size=len(text)
    archive.addfile(item,io.BytesIO(text))
  if sys.argv[7] == 'true':
    item=tarfile.TarInfo('usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@vendor/factory')
    item.type=tarfile.SYMTYPE
    item.linkname='../../../../profile-copy'
    archive.addfile(item)`
  const made = spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', py, archive, profileText, JSON.stringify(profile), JSON.stringify(bundlePackage), JSON.stringify(patchFiles), JSON.stringify(profileBundlePackage), String(installBundleSymlink)], { encoding: 'utf8' })
  assert.equal(made.status, 0, made.stderr)
  return { dir, archive, output }
}

function bundleHash(files) {
  const hash = createHash('sha256').update('DSHBNDL1', 'ascii')
  for (const [path, content] of files) {
    const pathBytes = Buffer.from(path, 'utf8')
    const bytes = Buffer.from(content, 'utf8')
    const pathLen = Buffer.alloc(4); pathLen.writeUInt32BE(pathBytes.length)
    const contentLen = Buffer.alloc(8); contentLen.writeBigUInt64BE(BigInt(bytes.length))
    hash.update(pathLen).update(pathBytes).update(contentLen).update(bytes)
  }
  return hash.digest('hex')
}

test('hard manifest binds exact inserts and selected factory bundle patches to final snapshot', () => {
  const f = fixture("- insert:\n  - id: factory-one\n    name: '@vendor/factory'\n    config:\n      name: display-only\n- insert:\n  - id: factory-two\n    name: local-hard\n")
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.equal(run.status, 0, run.stderr)
    const manifest = JSON.parse(readFileSync(f.output, 'utf8'))
    assert.equal(manifest.schema, 2)
    assert.equal(manifest.complete, true)
    assert.equal(manifest.fingerprint.length, 64)
    assert.deepEqual(manifest.entries, [
      { id: 'base-entry', name: '@vendor/dsh-base' },
      { id: 'web-entry', name: '@vendor/dsh-web-app' },
      { id: 'factory-one', name: '@vendor/factory' },
      { id: 'factory-two', name: 'local-hard' },
    ])
    assert.deepEqual(manifest.profileEntries, [
      { id: 'factory-one', name: '@vendor/factory' },
      { id: 'factory-two', name: 'local-hard' },
    ])
    assert.deepEqual(manifest.names, ['@vendor/dsh-base', '@vendor/dsh-web-app', '@vendor/factory', 'local-hard'])
    assert.deepEqual(manifest.factoryBundles, [{ name: factoryName, version: '1.2.3', patchSha256: bundleHash(factoryPatches) }])
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})

test('factory digest uses a golden, ordered, path-and-content-bound encoding', () => {
  const expected = bundleHash(factoryPatches)
  assert.equal(expected, 'bdcb30e4206cb3058c8f762907fa3044b95374d9dcc988c353e824e54f0db709')
  assert.notEqual(bundleHash([...factoryPatches].reverse()), expected)
  assert.notEqual(bundleHash([[factoryPatches[0][0], 'changed\n'], factoryPatches[1]]), expected)
  assert.notEqual(bundleHash([['renamed/a.yml', factoryPatches[0][1]], factoryPatches[1]]), expected)
})

test('factory ownership rejects same-name metadata mismatch and missing patch bytes', () => {
  for (const options of [
    { bundlePackage: { name: '@vendor/impostor', version: '1.2.3', dsh: { bundle: { patch: 'patches/a.yml' } } } },
    { bundlePackage: { name: factoryName, version: '1.2.3', dsh: { bundle: { patch: 'patches/missing.yml' } } } },
  ]) {
    const f = fixture('', options)
    try {
      const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
      assert.notEqual(run.status, 0)
      assert.match(run.stderr, /identity mismatch|patch file missing/)
    } finally { rmSync(f.dir, { recursive: true, force: true }) }
  }
})

test('bundle identity follows rc2 install-first resolution when profile has an impostor duplicate', () => {
  const f = fixture('', { profileBundlePackage: { name: factoryName, version: '999.0.0', dsh: { bundle: { patch: 'wrong.yml' } } } })
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.equal(run.status, 0, run.stderr)
    const manifest = JSON.parse(readFileSync(f.output, 'utf8'))
    assert.deepEqual(manifest.factoryBundles, [{ name: factoryName, version: '1.2.3', patchSha256: bundleHash(factoryPatches) }])
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})

test('an unresolved install symlink cannot silently fall back to a profile duplicate', () => {
  const f = fixture('', {
    bundlePackage: null,
    profileBundlePackage: { name: factoryName, version: '999.0.0', dsh: { bundle: { patch: 'patch.yml' } } },
    installBundleSymlink: true,
  })
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.notEqual(run.status, 0)
    assert.match(run.stderr, /crosses archive symlink/)
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})

test('an explicitly empty factory selection is preserved as known empty', () => {
  const f = fixture('', { profile: { dsh: { profile: { bundles: [] } } } })
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.equal(run.status, 0, run.stderr)
    assert.deepEqual(JSON.parse(readFileSync(f.output, 'utf8')).factoryBundles, [])
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})

test('hard manifest refuses a product insert that lacks exact id identity', () => {
  const f = fixture("- insert:\n  - name: '@vendor/factory'\n")
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.notEqual(run.status, 0)
    assert.match(run.stderr, /no exact id/)
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})
