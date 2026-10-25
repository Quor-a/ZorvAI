// arkweb-resource-protocol.test.mjs — #221 ArkWeb authority fallback regression.
//
// The standard URL path must remain unchanged. Only after a dsh-resource URL parsed with an empty
// hostname does the patch recover the narrowly defined authority grammar from the original address.
// `--target <client.js>` runs the same behavior checks against a patched snapshot artifact.
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const targetArg = process.argv.indexOf('--target')
const stageTarget = targetArg >= 0 ? process.argv[targetArg + 1] : undefined
const targetRel = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-resources/lib/client.js'
const fixture = versionedFixture('dsh-client-resources', 'lib', 'client.js')
const patchRunner = join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs')
const failures = []

function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

function extractFunction(source, signature) {
  const start = source.indexOf(signature)
  if (start < 0) throw new Error('function not found: ' + signature)
  let depth = 0
  for (let index = source.indexOf('{', start); index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    else if (source[index] === '}') {
      depth -= 1
      if (depth === 0) return source.slice(start, index + 1)
    }
  }
  throw new Error('unbalanced braces for ' + signature)
}

function protocolFunction(source, UrlImplementation) {
  const body = extractFunction(source, 'function protocolOf(address)')
  return new Function('URL', body + '\nreturn protocolOf;')(UrlImplementation)
}

class ArkWebLikeUrl {
  constructor(address) {
    const scheme = /^([A-Za-z][A-Za-z0-9+.-]*):/.exec(address)
    if (scheme === null) throw new TypeError('invalid URL')
    this.protocol = scheme[1].toLowerCase() + ':'
    // HarmonyOS/ArkWeb issue #221 behavior: non-special dsh-resource authority is lost.
    this.hostname = ''
  }
}

function exercise(source) {
  const standard = protocolFunction(source, URL)
  check('standard Chromium resource authority remains file', standard('dsh-resource://file/session/s1/a.txt') === 'file')
  check('standard Chromium preserves case-insensitive protocol host', standard('DSH-RESOURCE://File/session/s1/a.txt') === 'file')
  check('non-resource schemes remain unsupported', standard('sidebar://guide') === undefined)
  check('resource URL with no authority remains unsupported', standard('dsh-resource:///no-host') === undefined)
  check('unparseable path remains unsupported', standard('/a/b.txt') === undefined)

  const arkWeb = protocolFunction(source, ArkWebLikeUrl)
  check('ArkWeb empty hostname recovers file authority', arkWeb('dsh-resource://file/session/s1/a.txt') === 'file')
  check('ArkWeb fallback lower-cases the authority', arkWeb('DSH-RESOURCE://File/session/s1/a.txt') === 'file')
  check('ArkWeb fallback rejects missing authority', arkWeb('dsh-resource:///no-host') === undefined)
  check('ArkWeb fallback rejects userinfo-like authority', arkWeb('dsh-resource://file@unexpected/session/s1/a.txt') === undefined)
  check('ArkWeb fallback never accepts a different scheme', arkWeb('sidebar://file/session/s1/a.txt') === undefined)
}

let scratch
try {
  if (stageTarget !== undefined) {
    const source = readFileSync(stageTarget, 'utf8')
    check('stage target carries H1 marker', source.includes('dsh-mobile ArkWeb resource authority fallback (H1)'))
    exercise(source)
  } else {
    scratch = mkdtempSync(join(tmpdir(), 'arkweb-resources-'))
    const target = join(scratch, targetRel)
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, readFileSync(fixture, 'utf8').replace(/\r\n/g, '\n'))
    const applied = spawnSync(process.execPath,
      [patchRunner, scratch, '--apply', '--scope', 'engine', '--only', 'arkweb-resource-protocol-H1'],
      { encoding: 'utf8' })
    check('apply-patches exits 0', applied.status === 0,
      (applied.stderr || applied.stdout || '').trim().split('\n').slice(-3).join(' '))
    const patched = readFileSync(target, 'utf8')
    check('H1 marker appears after apply', patched.includes('dsh-mobile ArkWeb resource authority fallback (H1)'))
    const syntax = spawnSync(process.execPath, ['--check', target], { encoding: 'utf8' })
    check('patched artifact parses', syntax.status === 0, (syntax.stderr || '').split('\n')[0])
    const second = spawnSync(process.execPath,
      [patchRunner, scratch, '--apply', '--scope', 'engine', '--only', 'arkweb-resource-protocol-H1'],
      { encoding: 'utf8' })
    check('re-apply is idempotent', second.status === 0 && readFileSync(target, 'utf8') === patched)
    exercise(patched)
  }
} finally {
  if (scratch !== undefined) rmSync(scratch, { recursive: true, force: true })
}

if (failures.length > 0) {
  console.error('\narkweb-resource-protocol: ' + failures.length + ' check(s) failed: ' + failures.join('; '))
  process.exit(1)
}
console.log('\narkweb-resource-protocol: all checks passed')
