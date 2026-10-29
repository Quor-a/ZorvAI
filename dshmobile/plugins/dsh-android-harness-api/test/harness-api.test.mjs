// dsh-android-harness-api 单测（node:test，无外部依赖；桩 ctx 复刻 cordis 面）
//
// 覆盖判据：
//   ① apply 经 ctx.effect 注册恰好 4 条 exact 路由（路径字面量钉死），disposer 可摘除；
//   ② 鉴权：requestRejection 403/401 → 空体结束且不进业务；undefined → 放行；
//      栅栏服务抛错 → 失败关闭 401（绝不放行）；
//   ③ 方法不符 → 405 + allow 头；
//   ④ 请求体 >1MiB / 非法 JSON / 缺 name → 400；
//   ⑤ tools 列举：schemas() 投影（name/description/parameters/deferLoading 透传）；
//      schemas() 抛错 → 500；
//   ⑥ tools/call：成功→200 带 value/content/text；工具级失败（isError）→200 带 error；
//      execute 抛错→500；arguments 缺省 {}、原样透传；timeoutMs 夹取；
//      超时经 AbortSignal 真的中断挂起执行；
//   ⑦ skills 列举/加载：成功 200；get() 返回 undefined→404；validateDefinition
//      抛错→500（#505 修复效果的自诊断面）；
//   ⑧ 所有 JSON 响应带 no-store + content-type；拒绝响应空体；
//   ⑨ 源码契约：inject 只含 webServer、懒读助手在场、无 ctx.tools/skills 直读、
//      四路由常量、effect 包装、requestRejection 在场；
//   ⑩ 服务缺席回归（2026-10-30 真机事故）：tools/skills 缺席 → 500 可读、
//      connection 缺席 → fail-closed 401，路由注册不受影响。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const mod = await import(pathToFileURL(join(HERE, '..', 'lib', 'index.js')).href)
const { apply, name, inject } = mod

const ROUTE_TOOLS = '/api/harness/tools'
const ROUTE_TOOLS_CALL = '/api/harness/tools/call'
const ROUTE_SKILLS = '/api/harness/skills'
const ROUTE_SKILLS_LOAD = '/api/harness/skills/load'

// ── 桩 ──────────────────────────────────────────────────────────────────────
/** 桩请求体：异步可迭代（readBoundedBody 的输入形态）+ method + headers。 */
function makeReq({ method = 'GET', body = '' } = {}) {
  const chunks = body === '' ? [] : [Buffer.from(body)]
  return {
    method,
    headers: { host: '127.0.0.1:PORT' },
    url: '/',
    resume: () => {},
    async *[Symbol.asyncIterator]() {
      for (const c of chunks) yield c
    },
  }
}

/** 桩响应：捕获 statusCode / setHeader / end 载荷。 */
function makeRes() {
  return {
    statusCode: 0,
    headers: {},
    body: '',
    ended: false,
    setHeader(k, v) {
      this.headers[k.toLowerCase()] = v
    },
    end(payload) {
      if (payload !== undefined) this.body += payload
      this.ended = true
    },
  }
}

/**
 * 桩 ctx：复刻 cordis 懒读面——服务不直挂属性，一律经 ctx.get(name) 查找
 * （对齐 2026-10-30 修复：插件只静态 inject webServer，其余请求期懒读）。
 * omit 可模拟服务缺席（get 返回 undefined = 引擎里该服务未激活）。
 */
function makeCtx({ tools, skills, rejection, omit = [] } = {}) {
  const routes = []
  const effects = []
  const executeCalls = []
  const getCalls = []
  const listCalls = []
  const services = {
    webServer: {
      register: (route) => {
        routes.push(route)
        return () => {
          const i = routes.indexOf(route)
          if (i >= 0) routes.splice(i, 1)
        }
      },
    },
  }
  if (!omit.includes('tools')) {
    services.tools = {
      schemas: tools?.schemas ?? (() => []),
      execute: tools?.execute
        ? async (exec) => {
            executeCalls.push(exec)
            return tools.execute(exec)
          }
        : async (exec) => {
            executeCalls.push(exec)
            return { isError: false, value: {}, content: [] }
          },
    }
  }
  if (!omit.includes('skills')) {
    services.skills = {
      list: skills?.list
        ? async () => {
            listCalls.push(1)
            return skills.list()
          }
        : async () => {
            listCalls.push(1)
            return []
          },
      get: skills?.get
        ? async (n) => {
            getCalls.push(n)
            return skills.get(n)
          }
        : async (n) => {
            getCalls.push(n)
            return undefined
          },
    }
  }
  if (!omit.includes('connection')) {
    services.connection = {
      requestRejection: rejection ?? (() => undefined),
    }
  }
  const ctx = {
    logger: () => ({ warn: () => {}, debug: () => {}, info: () => {}, error: () => {} }),
    // webServer 是静态 inject 的：cordis 代理上直读可用（apply 期正途）；
    // 同时入 get() 查找表（懒读面一致性）。
    webServer: services.webServer,
    get: (n) => services[n],
    effect: (fn, tag) => {
      const disposer = fn()
      effects.push({ tag, disposer })
      return disposer
    },
  }
  return { ctx, routes, effects, executeCalls, getCalls, listCalls, services, byPath: (p) => routes.find((r) => r.path === p) }
}

/** 走一遍路由处理并回收响应。 */
async function call(ctx, path, req) {
  const route = ctx.byPath(path)
  assert.ok(route, '路由未注册：' + path)
  const res = makeRes()
  await route.handler(req, res)
  return res
}

const json = (res) => JSON.parse(res.body)

// ── ① 注册面 ────────────────────────────────────────────────────────────────
test('apply 注册恰好 4 条 exact 路由，路径钉死', () => {
  const c = makeCtx()
  apply(c.ctx)
  assert.equal(c.routes.length, 4)
  assert.deepEqual(
    c.routes.map((r) => [r.kind, r.path]).sort(),
    [
      ['exact', ROUTE_SKILLS],
      ['exact', ROUTE_SKILLS_LOAD],
      ['exact', ROUTE_TOOLS],
      ['exact', ROUTE_TOOLS_CALL],
    ].sort(),
  )
})

test('路由经 ctx.effect 注册，disposer 摘除后路由表清空', () => {
  const c = makeCtx()
  apply(c.ctx)
  assert.equal(c.effects.length, 4)
  for (const e of c.effects) {
    assert.equal(typeof e.tag, 'string')
    assert.ok(e.tag.startsWith('harness-api: '), 'effect tag 缺前缀：' + e.tag)
    assert.equal(typeof e.disposer, 'function')
  }
  for (const e of c.effects) e.disposer()
  assert.equal(c.routes.length, 0)
})

test('导出面：name 与 inject 只含 webServer（2026-10-30 真机事故修复）', () => {
  assert.equal(name, 'dsh-android-harness-api')
  // 🔴 只允许 webServer：静态 inject 任一请求期服务 = 引擎启动埋永久 pending 雷。
  assert.deepEqual([...inject], ['webServer'])
  for (const forbidden of ['tools', 'skills', 'connection']) {
    assert.ok(!inject.includes(forbidden), 'inject 绝不允许含请求期服务：' + forbidden)
  }
})

// ── ② 鉴权 ──────────────────────────────────────────────────────────────────
test('requestRejection=403 → 空体 403，不进业务', async () => {
  let schemasCalled = false
  const c = makeCtx({
    rejection: () => 403,
    tools: {
      schemas: () => {
        schemasCalled = true
        return []
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 403)
  assert.equal(res.body, '')
  assert.equal(res.headers['cache-control'], 'no-store')
  assert.equal(schemasCalled, false)
})

test('requestRejection=401 → 空体 401，不进业务', async () => {
  let listCalled = false
  const c = makeCtx({
    rejection: () => 401,
    skills: {
      list: async () => {
        listCalled = true
        return []
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_SKILLS, makeReq())
  assert.equal(res.statusCode, 401)
  assert.equal(res.body, '')
  assert.equal(listCalled, false)
})

test('requestRejection=undefined → 放行到业务', async () => {
  const c = makeCtx({ tools: { schemas: () => [{ name: 't', description: 'd', parameters: {} }] } })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 200)
  assert.equal(json(res).count, 1)
})

test('栅栏服务抛错 → 失败关闭 401（绝不放行）', async () => {
  const c = makeCtx({
    rejection: () => {
      throw new Error('connection 装配损坏（测试桩）')
    },
    tools: { schemas: () => [] },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 401)
  assert.equal(res.body, '')
})

test('connection 服务缺席（引擎未激活）→ fail-closed 401，不进业务', async () => {
  let schemasCalled = false
  const c = makeCtx({
    omit: ['connection'],
    tools: {
      schemas: () => {
        schemasCalled = true
        return []
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 401)
  assert.equal(res.body, '')
  assert.equal(schemasCalled, false)
})

// ── ③ 方法 ──────────────────────────────────────────────────────────────────
test('GET 路由收到 POST → 405 + allow:GET', async () => {
  const c = makeCtx()
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq({ method: 'POST', body: '{}' }))
  assert.equal(res.statusCode, 405)
  assert.equal(res.headers.allow, 'GET')
})

test('POST 路由收到 GET → 405 + allow:POST', async () => {
  const c = makeCtx()
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'GET' }))
  assert.equal(res.statusCode, 405)
  assert.equal(res.headers.allow, 'POST')
})

// ── ④ 请求体 ────────────────────────────────────────────────────────────────
test('请求体 >1MiB → 400 且不进 execute', async () => {
  let executed = false
  const c = makeCtx({
    tools: {
      execute: () => {
        executed = true
        return { isError: false, value: {}, content: [] }
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: 'x'.repeat(1024 * 1024 + 1) }))
  assert.equal(res.statusCode, 400)
  assert.match(json(res).error, /上限/)
  assert.equal(executed, false)
})

test('非法 JSON → 400', async () => {
  const c = makeCtx()
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: '{oops' }))
  assert.equal(res.statusCode, 400)
  assert.match(json(res).error, /JSON/)
})

test('缺 name / name 非字符串 / name 空串 → 400', async () => {
  const c = makeCtx()
  apply(c.ctx)
  for (const body of ['{}', '{"name":42}', '{"name":""}']) {
    const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body }))
    assert.equal(res.statusCode, 400, body)
  }
})

// ── ⑤ tools 列举 ────────────────────────────────────────────────────────────
test('tools 列举：schemas() 投影四字段，deferLoading 透传', async () => {
  const c = makeCtx({
    tools: {
      schemas: () => [
        { name: 'a', description: 'da', parameters: { type: 'object' } },
        { name: 'b', description: 'db', parameters: {}, deferLoading: true },
      ],
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 200)
  const body = json(res)
  assert.equal(body.ok, true)
  assert.equal(body.count, 2)
  assert.deepEqual(body.tools[0], { name: 'a', description: 'da', parameters: { type: 'object' } })
  assert.equal(body.tools[1].deferLoading, true)
  assert.equal(res.headers['content-type'], 'application/json; charset=utf-8')
  assert.equal(res.headers['cache-control'], 'no-store')
})

test('tools 列举：schemas() 抛错 → 500 带原文', async () => {
  const c = makeCtx({
    tools: {
      schemas: () => {
        throw new Error('registry 损坏（测试桩）')
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 500)
  assert.match(json(res).error, /registry 损坏/)
})

test('tools 服务缺席（引擎未激活）→ 500 带"未激活"，路由照常注册', async () => {
  const c = makeCtx({ omit: ['tools'] })
  apply(c.ctx)
  assert.equal(c.routes.length, 4, '服务缺席不影响路由注册')
  const res = await call(c, ROUTE_TOOLS, makeReq())
  assert.equal(res.statusCode, 500)
  assert.match(json(res).error, /未激活/)
})

test('tools/call：tools 服务缺席 → 500 不挂请求', async () => {
  const c = makeCtx({ omit: ['tools'] })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'x' }) }))
  assert.equal(res.statusCode, 500)
  assert.match(json(res).error, /未激活/)
})

// ── ⑥ tools/call ────────────────────────────────────────────────────────────
test('tools/call 成功：200 带 value/content/text/callId', async () => {
  const c = makeCtx({
    tools: {
      execute: (exec) => ({
        isError: false,
        value: { echo: exec.arguments },
        content: [
          { type: 'text', text: '第一行' },
          { type: 'text', text: '第二行' },
        ],
      }),
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'echo', arguments: { q: 1 } }) }))
  assert.equal(res.statusCode, 200)
  const body = json(res)
  assert.equal(body.ok, true)
  assert.equal(body.isError, false)
  assert.equal(body.name, 'echo')
  assert.match(body.callId, /^harness-api-/)
  assert.deepEqual(body.value, { echo: { q: 1 } })
  assert.equal(body.text, '第一行\n第二行')
  assert.equal(typeof body.durationMs, 'number')
  assert.equal(body.error, undefined)
})

test('tools/call 工具级失败（isError）→ 200 带 error.message', async () => {
  const c = makeCtx({
    tools: {
      execute: () => ({ isError: true, error: { message: 'UNKNOWN_TOOL', info: { code: 'UNKNOWN_TOOL' } }, content: [] }),
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'nope' }) }))
  assert.equal(res.statusCode, 200)
  const body = json(res)
  assert.equal(body.ok, true)
  assert.equal(body.isError, true)
  assert.equal(body.error.message, 'UNKNOWN_TOOL')
  assert.deepEqual(body.error.info, { code: 'UNKNOWN_TOOL' })
  assert.equal(body.value, undefined)
})

test('tools/call execute 抛错（装配级异常）→ 500，不挂请求', async () => {
  const c = makeCtx({
    tools: {
      execute: () => {
        throw new Error('管线炸了（测试桩）')
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'x' }) }))
  assert.equal(res.statusCode, 500)
  assert.match(json(res).error, /管线炸了/)
})

test('tools/call arguments 缺省 {}，signal 是 AbortSignal，callId 透传给 execute', async () => {
  const c = makeCtx()
  apply(c.ctx)
  await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'x' }) }))
  assert.equal(c.executeCalls.length, 1)
  const exec = c.executeCalls[0]
  assert.deepEqual(exec.arguments, {})
  assert.ok(exec.signal instanceof AbortSignal)
  assert.match(String(exec.callId), /^harness-api-/)
  assert.equal(exec.name, 'x')
})

test('tools/call 超时：timeoutMs=1 经 AbortSignal 真中断挂起执行', async () => {
  const c = makeCtx({
    tools: {
      execute: (exec) =>
        new Promise((resolve) => {
          if (exec.signal.aborted) {
            resolve({ isError: true, error: { message: 'aborted' }, content: [] })
            return
          }
          exec.signal.addEventListener(
            'abort',
            () => resolve({ isError: true, error: { message: 'aborted' }, content: [] }),
            { once: true },
          )
        }),
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'hang', timeoutMs: 1 }) }))
  assert.equal(res.statusCode, 200)
  assert.equal(json(res).isError, true)
})

test('tools/call timeoutMs 夹取：0 与超大值都不炸，正常完成', async () => {
  for (const timeoutMs of [0, -5, 999999999, 1.9, Number.NaN]) {
    const c = makeCtx()
    apply(c.ctx)
    const res = await call(c, ROUTE_TOOLS_CALL, makeReq({ method: 'POST', body: JSON.stringify({ name: 'x', timeoutMs }) }))
    assert.equal(res.statusCode, 200, 'timeoutMs=' + timeoutMs)
    assert.equal(json(res).ok, true)
  }
})

// ── ⑦ skills ────────────────────────────────────────────────────────────────
test('skills 列举：摘要原样透传', async () => {
  const summaries = [
    { name: 's1', description: 'd1', invocation: { modelInvocable: true, userInvocable: true }, source: 'plugin:x', provider: 'p' },
  ]
  const c = makeCtx({ skills: { list: async () => summaries } })
  apply(c.ctx)
  const res = await call(c, ROUTE_SKILLS, makeReq())
  assert.equal(res.statusCode, 200)
  const body = json(res)
  assert.equal(body.count, 1)
  assert.deepEqual(body.skills, summaries)
})

test('skills 列举：list() 抛错 → 500', async () => {
  const c = makeCtx({
    skills: {
      list: async () => {
        throw new Error('catalog 损坏（测试桩）')
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_SKILLS, makeReq())
  assert.equal(res.statusCode, 500)
  assert.match(json(res).error, /catalog 损坏/)
})

test('skills 服务缺席（引擎未激活）→ 列举/加载均 500 带"未激活"', async () => {
  const c = makeCtx({ omit: ['skills'] })
  apply(c.ctx)
  const list = await call(c, ROUTE_SKILLS, makeReq())
  assert.equal(list.statusCode, 500)
  assert.match(json(list).error, /未激活/)
  const load = await call(c, ROUTE_SKILLS_LOAD, makeReq({ method: 'POST', body: JSON.stringify({ name: 'x' }) }))
  assert.equal(load.statusCode, 500)
  assert.match(json(load).error, /未激活/)
})

test('skills/load 成功：200 带完整定义（含 content）', async () => {
  const def = {
    name: 'dsh-plugin-store',
    description: '商城',
    content: '# 技能正文',
    invocation: { modelInvocable: true, userInvocable: true },
    source: 'plugin:dshmarketplace-plugin',
    provider: 'runtime',
  }
  const c = makeCtx({ skills: { get: async (n) => (n === 'dsh-plugin-store' ? def : undefined) } })
  apply(c.ctx)
  const res = await call(c, ROUTE_SKILLS_LOAD, makeReq({ method: 'POST', body: JSON.stringify({ name: 'dsh-plugin-store' }) }))
  assert.equal(res.statusCode, 200)
  const body = json(res)
  assert.equal(body.ok, true)
  assert.equal(body.skill.content, '# 技能正文')
  assert.deepEqual(c.getCalls, ['dsh-plugin-store'])
})

test('skills/load 不存在 → 404', async () => {
  const c = makeCtx()
  apply(c.ctx)
  const res = await call(c, ROUTE_SKILLS_LOAD, makeReq({ method: 'POST', body: JSON.stringify({ name: 'ghost' }) }))
  assert.equal(res.statusCode, 404)
  assert.match(json(res).error, /ghost/)
})

test('skills/load validateDefinition 抛错 → 500 带原文（#505 自诊断面）', async () => {
  const c = makeCtx({
    skills: {
      get: async () => {
        throw new TypeError('skill "dsh-plugin-store" source must be a string')
      },
    },
  })
  apply(c.ctx)
  const res = await call(c, ROUTE_SKILLS_LOAD, makeReq({ method: 'POST', body: JSON.stringify({ name: 'dsh-plugin-store' }) }))
  assert.equal(res.statusCode, 500)
  assert.match(json(res).error, /source must be a string/)
})

// ── ⑧⑨ 源码契约 ────────────────────────────────────────────────────────────
test('源码契约：inject 收窄 + 懒读助手 + 四路由常量 + effect 包装 + 鉴权在场', () => {
  const src = readFileSync(join(HERE, '..', 'src', 'index.ts'), 'utf8')
  // 🔴 装配纪律：inject 只留 webServer，其余一律 ctx.get 懒读。
  assert.match(src, /export const inject = \['webServer'\] as const/)
  assert.match(src, /function serviceOf<T>\(ctx: Context, name: string\): T \| undefined/)
  assert.match(src, /function toolsOf\(ctx: Context\)/)
  assert.match(src, /function skillsOf\(ctx: Context\)/)
  assert.match(src, /function connectionOf\(ctx: Context\)/)
  // 绝不允许再出现直读（直读 = 静态依赖 = 启动雷）。
  assert.doesNotMatch(src, /ctx\.tools\./)
  assert.doesNotMatch(src, /ctx\.skills\./)
  assert.doesNotMatch(src, /Reflect\.get\(ctx, 'connection'\)/)
  for (const p of [ROUTE_TOOLS, ROUTE_TOOLS_CALL, ROUTE_SKILLS, ROUTE_SKILLS_LOAD]) {
    assert.ok(src.includes("'" + p + "'"), '源码缺路由常量：' + p)
  }
  assert.equal((src.match(/kind: 'exact'/g) ?? []).length, 4, 'exact 路由数漂移')
  assert.equal((src.match(/ctx\.effect\(/g) ?? []).length, 4, 'effect 包装数漂移')
  assert.match(src, /requestRejection\(req\)/)
  assert.match(src, /MAX_BODY_BYTES = 1024 \* 1024/)
  assert.match(src, /MAX_TIMEOUT_MS = 600_000/)
  // api-route-auth 门禁要求 guard 调用落在每个注册块内（不是只藏在 handler 函数里）。
  assert.equal((src.match(/if \(rejected\(ctx, req, res\)\) return/g) ?? []).length, 8, 'guard 必须双写：注册块 4 + handler 兜底 4')
  assert.match(src, /path: ROUTE_TOOLS,/)
  assert.match(src, /path: ROUTE_TOOLS_CALL,/)
  assert.match(src, /path: ROUTE_SKILLS,/)
  assert.match(src, /path: ROUTE_SKILLS_LOAD,/)
})
