import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { checkPackageLockRoot } from './check-package-lock-roots.mjs'

test('accepts matching root dependencies regardless of key order', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-lock-root-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  fs.writeFileSync(path.join(directory, 'package.json'), JSON.stringify({
    name: 'fixture', version: '1.0.0', dependencies: { a: '1', b: '2' },
  }))
  fs.writeFileSync(path.join(directory, 'package-lock.json'), JSON.stringify({
    packages: { '': { name: 'fixture', version: '1.0.0', dependencies: { b: '2', a: '1' } } },
  }))
  assert.deepEqual(checkPackageLockRoot(directory), [])
})

test('rejects a stale dependency pin in the lock root', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-lock-root-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  fs.writeFileSync(path.join(directory, 'package.json'), JSON.stringify({
    name: 'fixture', version: '1.0.0', dependencies: { cordis: '4.0.4' },
  }))
  fs.writeFileSync(path.join(directory, 'package-lock.json'), JSON.stringify({
    packages: { '': { name: 'fixture', version: '1.0.0', dependencies: { cordis: '4.0.1' } } },
  }))
  assert.match(checkPackageLockRoot(directory).join('\n'), /dependencies.*不一致/)
})
