import assert from 'node:assert/strict'
import test from 'node:test'
import { reconcileImporter } from './reconcile-harness-vendor-lock.mjs'

test('reconciles pinned manifest specifiers while retaining locked resolutions', () => {
  const importer = {
    dependencies: {
      '@deepseek-ai/cordis': { specifier: 'workspace:~', version: 'link:../cordis' },
      '@deepseek-ai/schemastery': { specifier: 'workspace:~', version: 'link:../schemastery' },
      'node-addon-require-builtin': { specifier: '^0.1.6', version: '0.1.6' },
    },
  }
  const manifest = {
    peerDependencies: {
      '@deepseek-ai/cordis': 'workspace:^',
      'node-addon-require-builtin': '^0.1.4',
    },
  }

  const edits = reconcileImporter(importer, manifest, 'vendor/loader')

  assert.equal(edits.length, 3)
  assert.deepEqual(importer.dependencies, {
    '@deepseek-ai/cordis': { specifier: 'workspace:^', version: 'link:../cordis' },
    'node-addon-require-builtin': { specifier: '^0.1.4', version: '0.1.6' },
  })
  assert.deepEqual(edits.find((edit) => edit.name === '@deepseek-ai/schemastery'), {
    section: 'dependencies', name: '@deepseek-ai/schemastery', oldSpecifier: 'workspace:~',
    newSpecifier: null, lockedVersion: 'link:../schemastery',
  })
})

test('rejects a pinned dependency without an existing locked resolution', () => {
  const importer = { dependencies: {} }
  const manifest = { dependencies: { missing: '^1.0.0' } }
  assert.throws(() => reconcileImporter(importer, manifest, 'vendor/group'), /lacks a locked resolution/)
})

test('rejects malformed lock entries before changing them', () => {
  const importer = { dependencies: { cordis: { specifier: 'workspace:~' } } }
  const manifest = { dependencies: { cordis: 'workspace:^' } }
  assert.throws(() => reconcileImporter(importer, manifest, 'vendor/group'), /invalid locked resolution/)
  assert.equal(importer.dependencies.cordis.specifier, 'workspace:~')
})

test('keeps the effective local link from a workspace override', () => {
  const importer = { dependencies: { '@deepseek-ai/cosmokit': { specifier: 'link:../cosmokit', version: 'link:../cosmokit' } } }
  const manifest = { dependencies: { '@deepseek-ai/cosmokit': 'workspace:^' } }
  const overrides = { '@deepseek-ai/cosmokit': 'link:vendor/cosmokit' }
  const edits = reconcileImporter(importer, manifest, 'vendor/include', overrides)
  assert.deepEqual(edits, [])
  assert.equal(importer.dependencies['@deepseek-ai/cosmokit'].specifier, 'link:../cosmokit')
})
