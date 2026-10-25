import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const checker = fileURLToPath(new URL('./check-third-party.mjs', import.meta.url))
const sourceMatrix = JSON.parse(readFileSync(new URL('./third-party-licenses.json', import.meta.url)))
const sdkLicense = readFileSync(new URL('../LICENSES/Shizuku-API-MIT.txt', import.meta.url))

function fixture(run) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-third-party-'))
  for (const sub of ['scripts', 'LICENSES', 'app', 'usr/var/lib/dpkg']) mkdirSync(join(dir, sub), { recursive: true })
  const matrix = { ...sourceMatrix, packages: { fixture: 'MIT' } }
  writeFileSync(join(dir, 'scripts/third-party-licenses.json'), JSON.stringify(matrix))
  writeFileSync(join(dir, 'LICENSES/Shizuku-API-MIT.txt'), sdkLicense)
  writeFileSync(join(dir, 'LICENSES/GPL-2.0.txt'), readFileSync(new URL('../LICENSES/GPL-2.0.txt', import.meta.url)))
  writeFileSync(join(dir, 'usr/var/lib/dpkg/status'), 'Package: fixture\nVersion: 1.0\n\n')
  writeFileSync(join(dir, 'app/build.gradle.kts'), 'implementation("dev.rikka.shizuku:api:13.1.5")\nimplementation("dev.rikka.shizuku:provider:13.1.5")\n')
  const check = () => spawnSync(process.execPath, [checker, join(dir, 'usr'), '--write-notices', join(dir, 'notices.md')], { cwd: dir, encoding: 'utf8' })
  try { run({ dir, matrix, check }) } finally { rmSync(dir, { recursive: true, force: true }) }
}

test('notices retain dpkg and include all four fixed Shizuku artifacts and MIT text', () => fixture(({ dir, check }) => {
  const result = check()
  assert.equal(result.status, 0, result.stderr)
  const notices = readFileSync(join(dir, 'notices.md'), 'utf8')
  assert.match(notices, /fixture \| 1\.0 \| MIT/)
  assert.match(notices, /dev\.rikka\.shizuku:\{api, provider, aidl, shared\} \| 13\.1\.5 \| MIT/)
  assert.match(notices, /Shizuku-API-MIT\.txt/)
}))

test('missing SDK license refuses packaging', () => fixture(({ dir, check }) => {
  rmSync(join(dir, 'LICENSES/Shizuku-API-MIT.txt'))
  const result = check()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /许可全文缺失/)
}))

test('altered SDK license refuses packaging', () => fixture(({ dir, check }) => {
  writeFileSync(join(dir, 'LICENSES/Shizuku-API-MIT.txt'), 'not the pinned MIT text')
  const result = check()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /SHA256 不符/)
}))

test('unregistered Gradle version refuses packaging', () => fixture(({ dir, check }) => {
  writeFileSync(join(dir, 'app/build.gradle.kts'), 'implementation("dev.rikka.shizuku:api:99.0.0")')
  const result = check()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /许可版本未登记/)
}))

test('missing SDK matrix refuses packaging', () => fixture(({ dir, matrix, check }) => {
  delete matrix.androidComponents
  writeFileSync(join(dir, 'scripts/third-party-licenses.json'), JSON.stringify(matrix))
  const result = check()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /SDK 许可矩阵缺失/)
}))

test('unverifiable Gradle dependencies refuse packaging', () => fixture(({ dir, check }) => {
  writeFileSync(join(dir, 'app/build.gradle.kts'), '// dependency declaration missing')
  const result = check()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /依赖不可核实/)
}))

test('direct usr mode accepts real copyright under usr/share/doc', () => fixture(({ dir, matrix, check }) => {
  matrix.packages.fixture = 'GPL-2.0'
  writeFileSync(join(dir, 'scripts/third-party-licenses.json'), JSON.stringify(matrix))
  mkdirSync(join(dir, 'usr/share/doc/fixture'), { recursive: true })
  writeFileSync(join(dir, 'usr/share/doc/fixture/copyright'), 'fixture license text')
  const result = check()
  assert.equal(result.status, 0, result.stderr)
}))

test('direct usr mode refuses missing copyleft copyright', () => fixture(({ dir, matrix, check }) => {
  matrix.packages.fixture = 'GPL-2.0'
  writeFileSync(join(dir, 'scripts/third-party-licenses.json'), JSON.stringify(matrix))
  const result = check()
  assert.equal(result.status, 1)
  assert.match(result.stderr, /copyleft 包缺 copyright 全文/)
}))
