// dsh-android-zorvai-tools 单测（node:test，无外部依赖；桩 HTTP 服务器复刻壳侧 /tool 面）
//
// 覆盖判据：
//   ① 清单缺席（桥未启用）→ 只注册元工具 zorvai_bridge，execute 报告未连接，不抛错；
//   ② 清单在场 → 元工具 + 动态工具（zorvai_ 前缀，deferLoading），execute 走
//      POST /tool/<name>，返回值过引擎同款校验器（@deepseek-ai/dsh-tools 的
//      validateJsonSchemaValue——与 check-tool-output-schema.mjs 同一个函数）；
//   ③ 畸形工具定义（非法名字 / 非对象属性）逐条跳过，不炸 apply；
//   ④ refresh=true 补注册清单里新出现的工具（无需重启引擎）；
//   ⑤ 壳侧重部署换端口后，execute 重读清单即走新端点（无需重启引擎）；
//   ⑥ 连接拒绝 / 壳侧 500 JSON / 非 JSON 响应 → 都转成 {ok:false|true,text}，绝不抛错；
//   ⑦ 源码级 defineTool({name}) 声明集 vs 运行时注册集差集 = 0（对齐门禁判据）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
import { validateJsonSchemaValue } from '@deepseek-ai/dsh-tools'

const HERE = dirname(fileURLToPath(import.meta.url))
const mod = await import(pathToFileURL(join(HERE, '..', 'lib', 'index.js')).href)
const EXEC = { agent: { session: 'test' } }

/** 递归收集 undefined 成员（lossless-JSON 前置检查，与门禁同判据）。 */
function undefinedPaths(v, path = 'value', out = []) {
  if (v === undefined) { out.push(path); return out }
  if (Array.isArray(v)) { v.forEach((x, i) => undefinedPaths(x, `${path}[${i}]`, out)); return out }
  if (v !== null && typeof v === 'object') {
    for (const [k, x] of Object.entries(v)) undefinedPaths(x, `${path}.${k}`, out)
  }
  return out
}

/** 某工具返回值对「引擎同款校验器 + lossless JSON」的违规条数。 */
function violations(tool, value) {
  return [
    ...undefinedPaths(value).map((p) => `${p} is undefined (not lossless JSON)`),
    ...validateJsonSchemaValue(tool.output.schema, value, 'value'),
  ]
}

/** 门禁同形态夹具 ctx：get 只认 dshHomePath（指向临时目录），tools.register 收进数组。 */
function makeCtx(homeDir) {
  const registered = []
  const ctx = {
    logger: () => ({ warn: () => {}, debug: () => {}, info: () => {}, error: () => {} }),
    tools: { register: (t) => registered.push(t) },
    get: (k) => (k === 'dshHomePath' ? ((...segs) => join(homeDir, ...segs)) : undefined),
    effect: (fn) => { try { return fn?.() } catch { return undefined } },
    on: () => {},
    inject: () => undefined,
  }
  return { ctx, registered, byName: (n) => registered.find((t) => t.name === n) }
}

/** 桩壳侧服务器：POST /tool/<name> → {ok:true,text}；boom → 500 JSON 错误；plain → 纯文本。 */
async function startStub() {
  const received = []
  const server = createServer((req, res) => {
    if (req.method === 'POST' && (req.url ?? '').startsWith('/tool/')) {
      const name = decodeURIComponent((req.url ?? '').slice('/tool/'.length))
      let body = ''
      req.on('data', (c) => { body += c })
      req.on('end', () => {
        received.push({ name, args: JSON.parse(body) })
        if (name === 'boom') {
          res.writeHead(500, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, text: '壳侧执行失败（测试桩）' }))
          return
        }
        if (name === 'plain') {
          res.writeHead(200, { 'content-type': 'text/plain' })
          res.end('纯文本回执')
          return
        }
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: true, text: 'echo:' + name + ':' + body }))
      })
      return
    }
    res.writeHead(404)
    res.end('no')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  return { server, port, received, close: () => new Promise((r) => server.close(r)) }
}

function writeManifest(homeDir, manifest) {
  writeFileSync(join(homeDir, 'zorvai-tools.json'), JSON.stringify(manifest), 'utf8')
}

function baseManifest(port, extraTools = []) {
  return {
    version: 1,
    alias: 'zorvai',
    baseUrl: 'http://127.0.0.1:' + port,
    toolPath: '/tool',
    updatedAt: '2026-10-26T00:00:00.000Z',
    tools: [
      {
        name: 'echo',
        description: '回声测试工具',
        parameters: { type: 'object', properties: { msg: { type: 'string', description: '消息' } }, required: ['msg'] },
      },
      {
        name: 'rich_params',
        description: '富参数工具',
        parameters: {
          type: 'object',
          properties: {
            mode: { type: 'string', enum: ['fast', 'safe'], description: '模式' },
            count: { type: 'integer' },
            nested: { type: 'object', properties: { deep: { type: 'string' } }, required: ['deep'] },
            list: { type: 'array', items: { type: 'string' } },
            flag: { type: 'boolean' },
            bad_prop: 'not-an-object',
          },
          required: ['mode'],
        },
      },
      { name: 'bad name!', description: '非法名字，必须被跳过' },
      { description: '没有 name，必须被跳过' },
      ...extraTools,
    ],
  }
}

test('① 清单缺席（桥未启用）：只注册元工具，报告未连接，不抛错', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-no-manifest-'))
  try {
    const { ctx, registered, byName } = makeCtx(home)
    mod.apply(ctx)
    assert.equal(registered.length, 1, '清单缺席只能有元工具')
    assert.equal(registered[0].name, 'zorvai_bridge')
    assert.equal(registered[0].deferLoading, undefined, '元工具常驻可见，不带 deferLoading')

    const r = await byName('zorvai_bridge').execute({}, EXEC)
    assert.equal(r.ok, true)
    assert.equal(r.connected, false)
    assert.equal(r.toolCount, 0)
    assert.deepEqual(r.tools, [])
    assert.match(r.text, /未连接/)
    assert.equal(violations(byName('zorvai_bridge'), r).length, 0)

    const rendered = byName('zorvai_bridge').output.render({}, r).map((b) => b.text).join('\n')
    assert.match(rendered, /未连接/, 'render 必须非空且与状态一致')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('② 清单在场：元工具 + 动态工具（zorvai_ 前缀 + deferLoading），execute 走 /tool/<name>', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-manifest-'))
  const stub = await startStub()
  try {
    writeManifest(home, baseManifest(stub.port))
    const { ctx, registered, byName } = makeCtx(home)
    mod.apply(ctx)

    const names = registered.map((t) => t.name).sort()
    assert.deepEqual(names, ['zorvai_bridge', 'zorvai_echo', 'zorvai_rich_params'], '畸形定义必须被逐条跳过')

    const echo = byName('zorvai_echo')
    assert.equal(echo.deferLoading, true, '动态工具走渐进披露')
    assert.match(echo.description, /内置插件通道/)

    const r = await echo.execute({ msg: 'hi' }, EXEC)
    assert.equal(r.ok, true)
    assert.equal(r.text, 'echo:echo:{"msg":"hi"}')
    assert.equal(violations(echo, r).length, 0)
    assert.equal(stub.received.length, 1)
    assert.equal(stub.received[0].name, 'echo', 'URL 路径用原名（无 zorvai_ 前缀）')
    assert.deepEqual(stub.received[0].args, { msg: 'hi' })

    // rich_params：嵌套 object/array/enum 都进了编译后的 JSON Schema，且必填收敛到根 required
    const rich = byName('zorvai_rich_params')
    assert.equal(rich.parameters.type, 'object')
    assert.deepEqual(rich.parameters.required, ['mode'], '根 required 数组由逐属性 required:true 汇总')
    assert.equal(rich.parameters.properties.mode.enum[0], 'fast')
    assert.equal(rich.parameters.properties.nested.type, 'object')
    assert.equal(rich.parameters.properties.nested.additionalProperties, true)
    assert.equal(rich.parameters.properties.list.items.type, 'string')
    assert.equal(rich.parameters.properties.bad_prop, undefined, '畸形属性被跳过，不进 parameters')
    const rr = await rich.execute({ mode: 'fast' }, EXEC)
    assert.equal(violations(rich, rr).length, 0)
  } finally {
    await stub.close()
    rmSync(home, { recursive: true, force: true })
  }
})

test('③ 元工具报告已连接与工具清单；render 非空', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-meta-'))
  const stub = await startStub()
  try {
    writeManifest(home, baseManifest(stub.port))
    const { ctx, byName } = makeCtx(home)
    mod.apply(ctx)
    const r = await byName('zorvai_bridge').execute({}, EXEC)
    assert.equal(r.connected, true)
    assert.equal(r.baseUrl, 'http://127.0.0.1:' + stub.port)
    assert.equal(r.toolCount, 2)
    assert.deepEqual(r.tools, ['zorvai_echo', 'zorvai_rich_params'])
    assert.match(r.text, /已连接/)
    assert.match(r.text, /mcp__zorvai__\*/, '必须向模型点明另一条 MCP 通道')
    assert.equal(violations(byName('zorvai_bridge'), r).length, 0)
  } finally {
    await stub.close()
    rmSync(home, { recursive: true, force: true })
  }
})

test('④ refresh=true 补注册清单里新出现的工具（无需重启引擎）', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-refresh-'))
  const stub = await startStub()
  try {
    writeManifest(home, baseManifest(stub.port))
    const { ctx, registered, byName } = makeCtx(home)
    mod.apply(ctx)
    assert.equal(byName('zorvai_late'), undefined)

    writeManifest(home, baseManifest(stub.port, [
      { name: 'late', description: '后出现的工具', parameters: { type: 'object', properties: {} } },
    ]))
    const r = await byName('zorvai_bridge').execute({ refresh: true }, EXEC)
    assert.equal(r.toolCount, 3)
    assert.ok(byName('zorvai_late'), 'refresh 后新工具必须在场')
    assert.equal(byName('zorvai_late').deferLoading, true)

    // 幂等：再次 refresh 不重复注册
    await byName('zorvai_bridge').execute({ refresh: true }, EXEC)
    assert.equal(registered.filter((t) => t.name === 'zorvai_late').length, 1)
  } finally {
    await stub.close()
    rmSync(home, { recursive: true, force: true })
  }
})

test('⑤ 壳侧换端口后 execute 重读清单即走新端点（无需重启引擎）', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-port-'))
  const stub1 = await startStub()
  const stub2 = await startStub()
  try {
    writeManifest(home, baseManifest(stub1.port))
    const { ctx, byName } = makeCtx(home)
    mod.apply(ctx)

    const first = await byName('zorvai_echo').execute({ msg: 'a' }, EXEC)
    assert.equal(first.ok, true)
    assert.equal(stub1.received.length, 1)

    // 壳侧重部署：新随机端口 + 清单改写（模拟 HarnessBridge 重新 deploy）
    writeManifest(home, baseManifest(stub2.port))
    const second = await byName('zorvai_echo').execute({ msg: 'b' }, EXEC)
    assert.equal(second.ok, true, '换端口后无需重启引擎即恢复')
    assert.equal(stub2.received.length, 1, '第二次调用必须落到新端点')
    assert.equal(stub1.received.length, 1, '旧端点不再被调用')
  } finally {
    await stub1.close()
    await stub2.close()
    rmSync(home, { recursive: true, force: true })
  }
})

test('⑥ 连接拒绝 / 壳侧 500 JSON / 非 JSON 响应：都转成回执对象，绝不抛错', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-fail-'))
  const stub = await startStub()
  try {
    writeManifest(home, baseManifest(stub.port, [
      { name: 'boom', description: '壳侧 500', parameters: { type: 'object', properties: {} } },
      { name: 'plain', description: '非 JSON 响应', parameters: { type: 'object', properties: {} } },
    ]))
    const { ctx, byName } = makeCtx(home)
    mod.apply(ctx)

    const boom = await byName('zorvai_boom').execute({}, EXEC)
    assert.equal(boom.ok, false)
    assert.match(boom.text, /壳侧执行失败/)
    assert.equal(violations(byName('zorvai_boom'), boom).length, 0)

    const plain = await byName('zorvai_plain').execute({}, EXEC)
    assert.equal(plain.ok, true, 'HTTP 200 纯文本按成功回执')
    assert.equal(plain.text, '纯文本回执')

    // 连接拒绝：清单指向没人监听的端口（先占后放）
    const dead = await startStub()
    const deadPort = dead.port
    await dead.close()
    writeManifest(home, baseManifest(deadPort))
    const refused = await byName('zorvai_echo').execute({ msg: 'x' }, EXEC)
    assert.equal(refused.ok, false)
    assert.match(refused.text, /桥调用失败/)
    assert.equal(violations(byName('zorvai_echo'), refused).length, 0)
  } finally {
    await stub.close()
    rmSync(home, { recursive: true, force: true })
  }
})

test('⑦ 源码级 defineTool({name}) 声明集 vs 运行时注册集差集 = 0（对齐门禁判据）', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-src-'))
  try {
    const { ctx, registered } = makeCtx(home)
    mod.apply(ctx)
    const src = await import('node:fs').then((fs) => fs.readFileSync(join(HERE, '..', 'src', 'index.ts'), 'utf8'))
    const declared = new Set([...src.matchAll(/defineTool\(\s*\{[\s\S]{0,600}?name:\s*'([^']+)'/g)].map((m) => m[1]))
    assert.ok(declared.has('zorvai_bridge'), '元工具必须是字面量 defineTool({name}) 形态：' + [...declared].join(','))
    const registeredNames = new Set(registered.map((t) => t.name))
    for (const n of declared) assert.ok(registeredNames.has(n), '声明了但没注册：' + n)
    for (const n of registeredNames) assert.ok(declared.has(n), '注册了但源码没有字面量声明：' + n)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('⑧ 清单损坏（坏 JSON / baseUrl 缺失 / tools 缺失）按「桥未连接」处理', async () => {
  const home = mkdtempSync(join(tmpdir(), 'zorvai-broken-'))
  try {
    for (const bad of ['not json', JSON.stringify({ tools: [] }), JSON.stringify({ baseUrl: 'http://127.0.0.1:1' })]) {
      writeFileSync(join(home, 'zorvai-tools.json'), bad, 'utf8')
      const { ctx, registered, byName } = makeCtx(home)
      mod.apply(ctx)
      assert.equal(registered.length, 1, '损坏清单只留元工具：' + bad.slice(0, 30))
      const r = await byName('zorvai_bridge').execute({}, EXEC)
      assert.equal(r.connected, false)
    }
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})
