// market-route-auth.test.mjs — #222 follow-up: vendored marketplace exact routes require browser auth.
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const targetRel = 'dshmarketplace-plugin/lib/index.js'
const source = join(repoRoot, 'vendor', targetRel)
const patchRunner = join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs')
const failures = []

function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

function responseRecorder() {
  const out = { status: undefined, headers: undefined, body: undefined }
  return { out, writeHead(status, headers = {}) { out.status = status; out.headers = headers }, end(body) { out.body = body } }
}

const scratch = mkdtempSync(join(tmpdir(), 'market-route-auth-'))
try {
  const target = join(scratch, targetRel)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, readFileSync(source, 'utf8').replace(/\r\n/g, '\n'))
  writeFileSync(join(scratch, 'dshmarketplace-plugin', 'package.json'), JSON.stringify({ type: 'module' }))

  const nativeCommand = join(scratch, 'dshmarketplace-plugin', 'node_modules', '@deepseek-ai', 'dsh-native-command')
  mkdirSync(nativeCommand, { recursive: true })
  writeFileSync(join(nativeCommand, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh-native-command', type: 'module', exports: './index.js' }))
  writeFileSync(join(nativeCommand, 'index.js'), 'export const runNativeCommand = async () => ({ stdout: "" });\n')
  const dshTools = join(scratch, 'dshmarketplace-plugin', 'node_modules', '@deepseek-ai', 'dsh-tools')
  mkdirSync(dshTools, { recursive: true })
  writeFileSync(join(dshTools, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh-tools', type: 'module', exports: './index.js' }))
  writeFileSync(join(dshTools, 'index.js'), 'export const defineTool = value => value;\n')

  const applied = spawnSync(process.execPath,
    [patchRunner, scratch, '--apply', '--scope', 'vendor', '--only', 'market-route-auth-U2'],
    { encoding: 'utf8' })
  check('apply-patches exits 0', applied.status === 0,
    (applied.stderr || applied.stdout || '').trim().split('\n').slice(-3).join(' '))
  const patched = readFileSync(target, 'utf8')
  check('U2 marker appears in patched marketplace artifact', patched.includes('dsh-mobile marketplace route auth (U2)'))
  check('U2 rejection responses are explicitly no-store', patched.includes('dsh-mobile marketplace no-store (U2)'))
  check('search and install routes both call the authorization guard',
    (patched.match(/dshMobileMarketplaceRouteAuthorized\(n,e\)/g) || []).length === 2)
  const syntax = spawnSync(process.execPath, ['--check', target], { encoding: 'utf8' })
  check('patched artifact parses', syntax.status === 0, (syntax.stderr || '').split('\n')[0])
  const second = spawnSync(process.execPath,
    [patchRunner, scratch, '--apply', '--scope', 'vendor', '--only', 'market-route-auth-U2'],
    { encoding: 'utf8' })
  check('re-apply is idempotent', second.status === 0 && readFileSync(target, 'utf8') === patched)

  const mod = await import(pathToFileURL(target).href + '?market-route-auth-test=1')
  const routes = []
  let connection = { requestRejection: () => 401 }
  const ctx = {
    commands: { register() { return () => {} } },
    tools: { register() { return () => {} } },
    skills: { register() { return () => {} } },
    on() { return () => {} },
    get(name) { return name === 'connection' ? connection : undefined },
    webServer: { register(route) { routes.push(route); return () => {} } },
  }
  mod.apply(ctx)
  const search = routes.find((route) => route.path === '/api/dshmarketplace/search')
  const install = routes.find((route) => route.path === '/api/dshmarketplace/install')
  check('search exact route is registered', search !== undefined)
  check('install exact route is registered', install !== undefined)
  for (const [name, route] of [['search', search], ['install', install]]) {
    if (route === undefined) continue
    const denied = responseRecorder()
    await route.handler({ method: 'POST', headers: { host: '127.0.0.1:3080' } }, denied)
    check(name + ' rejects an unauthenticated exact-route request',
      denied.out.status === 401
        && denied.out.headers['cache-control'] === 'no-store'
        && denied.out.headers['content-type'] === 'application/json; charset=utf-8'
        && denied.out.body === '{"ok":false,"error":"unauthorized"}',
      JSON.stringify(denied.out))
    connection = { requestRejection: () => 403 }
    const forbidden = responseRecorder()
    await route.handler({ method: 'POST', headers: { host: 'attacker.invalid' } }, forbidden)
    check(name + ' rejects a cross-origin/forged-host request',
      forbidden.out.status === 403
        && forbidden.out.headers['cache-control'] === 'no-store'
        && forbidden.out.body === undefined,
      JSON.stringify(forbidden.out))
    connection = { requestRejection: () => 401 }
  }

  connection = undefined
  const noConnection = responseRecorder()
  await search.handler({ method: 'GET', headers: { host: '127.0.0.1:3080' } }, noConnection)
  check('marketplace remains browser-session-only when connection is absent',
    noConnection.out.status === 401 && noConnection.out.headers['cache-control'] === 'no-store',
    JSON.stringify(noConnection.out))
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

if (failures.length > 0) {
  console.error('\nmarket-route-auth: ' + failures.length + ' check(s) failed: ' + failures.join('; '))
  process.exit(1)
}
console.log('\nmarket-route-auth: all checks passed')
