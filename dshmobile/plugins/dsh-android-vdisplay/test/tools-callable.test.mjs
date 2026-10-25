// 虚拟屏工具的**可调用性**回归（离线，node:test；与其余插件单测同一形态）。
//
// 为什么需要这个文件：本插件的 android_vdisplay_create/destroy 曾经**完全不可用**，
// 而该插件此前**一个测试都没有**，没有任何门禁发现它。缺陷是「把服务方法从服务对象上摘下来裸调」：
//   const controlExec = faceOf()?.controlExec;  await controlExec(op, ...)
// 方法内的 this.controlQueue 遂成 undefined.controlQueue，抛
//   Cannot read properties of undefined (reading 'controlQueue')
// 被 catch 包成 code=vdisplay-control-exception。
//
// 该缺陷的恶劣之处：
//   1) 两个工具在**正常使用路径里不会被触发**（要先建屏才会用到），只有「逐个工具点一遍」的
//      全量扫描才会暴露；
//   2) 壳侧桥直接调用同一个 op **完全正常**——只看壳侧会误判成「没问题」。
//
// 夹具是**严格接收者校验**的：服务方法必须挂在对象上被调用；一旦被摘出来裸调，夹具即抛错。
// 已用「把修复改回裸调」做过反证：本测试会精确复现上面那句原始报错并变红。
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { apply } from '../lib/index.js'

/**
 * 「方法依赖 this」的服务夹具（真实 AndroidPrivilegeService.controlExec 依赖 this.controlQueue）。
 * 任何把它方法摘出来裸调的写法都会在这里抛错——这正是要拦住的形态。
 */
function strictFace() {
  const calls = []
  const face = {
    controlQueue: {
      // 载荷形状必须与壳侧 `VdisplayController.status()` **逐字段对齐**（`enabled`/`ops`/
      // `transports`/`screens` 都在真回执里）。第一版夹具漏了 `enabled`，于是状态被判成
      // `vdisplay-disabled`——那不是产品缺陷，是夹具不忠实；夹具不忠实会让回归测试变成噪声。
      enqueue: async (op, args) => ({
        ok: true,
        data: {
          ok: true,
          enabled: true,
          state: 'active',
          displayId: 42,
          ops: ['vdCreate', 'vdDestroy', 'vdLaunch', 'vdMoveTask', 'vdInfo'],
          transports: ['shizuku'],
          screens: [{
            alias: 'virtual-1', displayId: 42, kind: 'virtual', label: '虚拟屏幕 1', state: 'on',
            width: 360, height: 640, densityDpi: 160, selectable: true, reason: 'DSH 创建的虚拟屏，可作为查看器目标。',
          }],
          guidance: op + ' ok',
          ...(op === 'vdInput' ? { screenId: args?.target ?? 'virtual-1', verb: args?.verb } : {}),
        },
      }),
    },
    async controlExec(op, args, timeoutMs, auth) {
      // 与生产实现同构：先读 this.controlQueue。this 丢失时就是设备上那句真实报错。
      const queue = this.controlQueue
      if (queue === undefined) {
        throw new TypeError("Cannot read properties of undefined (reading 'controlQueue')")
      }
      calls.push({ op, args, timeoutMs, auth })
      return queue.enqueue(op, args)
    },
  }
  return { face, calls }
}

/** 用桩 ctx 跑 apply()，收集注册的工具。 */
function loadTools(face) {
  const registered = []
  const ctx = {
    logger: () => ({ warn() {}, debug() {}, info() {}, error() {} }),
    tools: { register: (t) => registered.push(t) },
    get: (name) => (name === 'androidPrivilege' ? face : undefined),
    effect: (cb) => { try { return cb?.() } catch { return undefined } },
    on: () => {},
    slots: { inject: () => () => {}, register: () => () => {} },
  }
  apply(ctx)
  return registered
}

const LOST_RECEIVER = /Cannot read propert(?:y|ies) of (?:undefined|null) \(reading '/

/**
 * 壳侧 Kotlin 真源定位：本测试同时活在两种布局里——
 *   协调仓：<repo>/plugins/dsh-android-vdisplay/test/ -> <repo>/dsh-mobile-apk/app/...
 *   apk 仓（云端自包含检出）：<repo>/plugins/dsh-android-vdisplay/test/ -> <repo>/app/...
 * 只认一种布局会让另一种结构性必红（0.14.1 apk CI 实锤：ENOENT 双 dsh-mobile-apk 前缀）。
 * 找不到即抛——不静默跳过，否则「路径写错」会伪装成「测试通过」。
 */
const KOTLIN_SOURCE = (() => {
  const candidates = [
    '../../../app/src/main/java/com/ai/assistance/quro/dshmobile/VdisplayController.kt',
    '../../../dsh-mobile-apk/app/src/main/java/com/ai/assistance/quro/dshmobile/VdisplayController.kt',
  ]
  for (const rel of candidates) {
    const url = new URL(rel, import.meta.url)
    if (existsSync(fileURLToPath(url))) return url
  }
  throw new Error('VdisplayController.kt 未在任一已知布局下找到：' + candidates.join(' | '))
})()

test('android_vdisplay_create 必须走通，且不得出现接收者丢失', async () => {
  const { face, calls } = strictFace()
  const tools = loadTools(face)
  const create = tools.find((t) => t.name === 'android_vdisplay_create')
  assert.ok(create, 'android_vdisplay_create 必须注册')
  const value = await create.execute({}, { agent: { session: 't' } })
  const text = JSON.stringify(value ?? {})
  assert.doesNotMatch(text, LOST_RECEIVER, '返回值含「服务方法接收者丢失」错误（应写成 svc.method(...)）: ' + text)
  assert.doesNotMatch(text, /is not a function/, '返回值含 is not a function: ' + text)
  assert.ok(calls.length > 0, '必须真的经控制队列调用到壳侧')
  assert.equal(value.ok, true, '服务在场时必须成功: ' + text)
  assert.equal(value.displayId, 42)
})

function assertMatchesToolSchema(tool, value) {
  const schema = tool.output.schema
  assert.equal(schema.type, 'object')
  assert.equal(schema.additionalProperties, false, 'output must reject undeclared fields')
  for (const key of schema.required ?? []) assert.ok(Object.hasOwn(value, key), `missing required ${key}`)
  for (const key of Object.keys(value)) {
    assert.ok(Object.hasOwn(schema.properties, key), `unexpected output property ${key}`)
    const expected = schema.properties[key].type
    const actual = Array.isArray(value[key]) ? 'array' : value[key] === null ? 'null' : typeof value[key]
    if (expected === 'integer') assert.ok(Number.isInteger(value[key]), `${key} must be integer`)
    else assert.equal(actual, expected, `${key} must be ${expected}`)
  }
}

test('android_vdisplay_input success receipt declares and validates state and screen identity', async () => {
  const tools = loadTools(strictFace().face)
  const input = tools.find((t) => t.name === 'android_vdisplay_input')
  const value = await input.execute({ verb: 'tap', x: 12, y: 34, screenId: 'virtual-1' }, { agent: { session: 't' } })
  assert.deepEqual(value, {
    ok: true, code: 'vdisplay-ok', guidance: 'vdInput ok', state: 'active',
    displayId: 42, screenId: 'virtual-1', verb: 'tap',
  })
  assertMatchesToolSchema(input, value)
})

test('android_vdisplay_input rejection and control failure both satisfy the strict output schema', async () => {
  const tools = loadTools(strictFace().face)
  const input = tools.find((t) => t.name === 'android_vdisplay_input')
  const rejected = await input.execute({ verb: 'tap', x: -1, y: 3 }, { agent: { session: 't' } })
  assert.equal(rejected.ok, false)
  assertMatchesToolSchema(input, rejected)

  const brokenFace = { async controlExec() { throw new Error('offline') } }
  const brokenInput = loadTools(brokenFace).find((t) => t.name === 'android_vdisplay_input')
  const failed = await brokenInput.execute({ verb: 'text', text: 'hello' }, { agent: { session: 't' } })
  assert.equal(failed.ok, false)
  assert.equal(failed.verb, 'text')
  assertMatchesToolSchema(brokenInput, failed)
})

test('android_vdisplay_input preserves the stable scope denial code from bridge service', async () => {
  const deniedFace = {
    async controlExec(op) {
      if (op === 'vdInfo') return { ok: true, data: { screens: [{ alias: 'virtual-1', kind: 'virtual', displayId: 42 }] } }
      return { ok: false, code: 'screen-out-of-scope', error: 'screen-out-of-scope: user scope is real-only' }
    },
  }
  const input = loadTools(deniedFace).find((t) => t.name === 'android_vdisplay_input')
  const value = await input.execute({ verb: 'tap', x: 1, y: 2, screenId: 'virtual-1' }, { agent: { session: 't' } })
  assert.equal(value.ok, false)
  assert.equal(value.code, 'screen-out-of-scope')
  assertMatchesToolSchema(input, value)
})

test('native vdInput execution checks scope before Shizuku and honors the explicit display target', async () => {
  const source = (await import('node:fs')).readFileSync(fileURLToPath(KOTLIN_SOURCE), 'utf8')
  const start = source.indexOf('fun input(context: Context, verb: String, args: JSONObject, target: String? = null)')
  const end = source.indexOf('\n  /**', start + 1)
  assert.ok(start >= 0 && end > start, 'VdisplayController.input source block must exist')
  const body = source.slice(start, end)
  assert.match(body, /if \(target != null\) recordOf\(target\) else selectedRecord\(\)/,
    'explicit target must not be replaced by selectedRecord')
  assert.ok(body.indexOf('scope.inputDenial(record.alias)') < body.indexOf('ShizukuTransport.runController'),
    'scope refusal must happen before any Shizuku input dispatch')
  assert.match(body, /\.put\("code", denial\)\.put\("reason", denial\)/,
    'native refusal must carry the stable machine-readable denial code')
})

test('android_vdisplay_destroy 同样必须走通（同一缺陷类）', async () => {
  const { face, calls } = strictFace()
  const tools = loadTools(face)
  const destroy = tools.find((t) => t.name === 'android_vdisplay_destroy')
  assert.ok(destroy, 'android_vdisplay_destroy 必须注册')
  const value = await destroy.execute({}, { agent: { session: 't' } })
  assert.doesNotMatch(JSON.stringify(value ?? {}), LOST_RECEIVER)
  assert.ok(calls.length > 0)
})

test('服务缺席时给结构化拒绝（不抛异常、不静默）', async () => {
  const tools = loadTools(undefined)
  const create = tools.find((t) => t.name === 'android_vdisplay_create')
  const value = await create.execute({}, { agent: { session: 't' } })
  assert.equal(value.ok, false)
  assert.equal(value.code, 'vdisplay-control-unavailable')
  assert.equal(typeof value.guidance, 'string')
})

// ── B2（0.14.1 设备实测缺陷）：`android_vdisplay_input` 是**承诺过却从未存在**的工具 ──────
//
// 设备实录：Skill 文档与三处工具文案都让模型用 `android_vdisplay_input`，实际调用报 `unknown tool`；
// 而虚拟屏上的按键/文本在工具面完全无路可达。本轮补实现（后端能力早已在壳侧 vdInput）。
// 下面两条钉住「参数如实映射到 vdInput」与「本地先拦非法 verb、不打无效往返」。

test('android_vdisplay_input 必须注册，且参数如实映射到壳侧 vdInput', async () => {
  const { face, calls } = strictFace()
  const tools = loadTools(face)
  const input = tools.find((t) => t.name === 'android_vdisplay_input')
  assert.ok(input, 'android_vdisplay_input 必须注册（文档承诺过它存在）')
  const value = await input.execute(
    { verb: 'tap', x: 12, y: 34, screenId: 'virtual-2' },
    { agent: { session: 't' } },
  )
  assert.doesNotMatch(JSON.stringify(value ?? {}), LOST_RECEIVER, '不得出现服务方法接收者丢失')
  assert.equal(calls.length, 1, '必须真的经控制队列调用到壳侧')
  assert.equal(calls[0].op, 'vdInput')
  assert.equal(calls[0].args.verb, 'tap')
  assert.equal(calls[0].args.target, 'virtual-2', '别名必须以 target 键下行（壳侧读 target）')
  assert.equal(calls[0].args.x, 12)
  assert.equal(calls[0].args.y, 34)
  assert.equal(value.ok, true)
  assert.equal(value.verb, 'tap')
})

test('android_vdisplay_input：非法 verb 由框架按 schema 挡在 execute 之前', async () => {
  const { face, calls } = strictFace()
  const input = loadTools(face).find((t) => t.name === 'android_vdisplay_input')
  await assert.rejects(
    () => input.execute({ verb: 'teleport', x: 1, y: 2 }, { agent: { session: 't' } }),
    /must be one of/,
    '非法枚举应在参数校验层被拒（不到执行面、更不到壳侧）',
  )
  assert.equal(calls.length, 0, '被参数校验拒掉的调用不得打到壳侧')
})

test('android_vdisplay_input：越界坐标在本地拦（schema 表达不了的跨字段约束）', async () => {
  const { face, calls } = strictFace()
  const input = loadTools(face).find((t) => t.name === 'android_vdisplay_input')
  const oor = await input.execute({ verb: 'tap', x: -1, y: 2 }, { agent: { session: 't' } })
  assert.equal(oor.ok, false)
  assert.equal(oor.code, 'invalid-arguments')
  assert.equal(calls.length, 0, '本地拦下的调用不得打到壳侧')
})

test('android_vdisplay_input：text 直传不过 shell（中文可用），长度按壳侧上限拦', async () => {
  const { face, calls } = strictFace()
  const input = loadTools(face).find((t) => t.name === 'android_vdisplay_input')
  const ok = await input.execute({ verb: 'text', text: '中文输入' }, { agent: { session: 't' } })
  assert.equal(ok.ok, true)
  assert.equal(calls[0].args.text, '中文输入', '文本作为单个 argv 元素直传，不应被 ASCII 白名单拒绝')
  const tooLong = await input.execute({ verb: 'text', text: 'x'.repeat(501) }, { agent: { session: 't' } })
  assert.equal(tooLong.ok, false)
  assert.equal(tooLong.code, 'invalid-arguments')
  assert.equal(calls.length, 1, '超长文本本地拦下，不再打一发')
})

// ── 0.14.1 批 2（W2 真实任务设备实测）：`vdInput` **从未成功过一次** ──────────────
//
// 设备证据（不是推断）：壳侧桥审计 `files/audit/audit.ndjson` 里
//   {"action":"privileged","op":"vdInput","result":"denied-no-session"}
// 从 0.14.1 引入本工具起，每次调用都是这一条（2026-09-20 四次、2026-09-22 一次）。
//
// 真因：`vdInput`/`vdLaunch`/`vdLaunchApp`/`vdMoveTask` 在 bridge 的 `TIER_REQUIRED_OPS` 内，
// 服务面按 `resolveAuth`（**显式 auth > AsyncLocalStorage 绑定 > 无**）解析调用方会话；
// 本插件此前只把会话放进 `payload.session`（那是壳侧**归属**校验用的），既不传 auth 也不调
// `bindSession` ⇒ 服务面恒判「缺少调用方会话」并 fail-closed 拒绝。
// 症状：工具描述与三处指引都承诺「虚拟屏按键/文本走 android_vdisplay_input」，模型照做只得
// 「会话问题」，退回 `android_shell_exec` 裸跑 `input -d`——能力被承诺而不可用。
//
// 判据：**会话必须以 auth 形参下行**（不是只挂在 payload 上）。反证方式 = 把第 4 个实参去掉，
// 本测试立即变红（而 payload.session 仍在，故只查 payload 的写法抓不到这个缺陷）。
test('android_vdisplay_input：会话必须以 auth 形参**原样传对象**（vdInput 属档位门 op）', async () => {
  const { face, calls } = strictFace()
  const input = loadTools(face).find((t) => t.name === 'android_vdisplay_input')
  // 会话对象（与引擎下发形态一致：`exec.agent.session` 是对象，`id` 只是它的一个字段）。
  const session = { id: 'sess-A', snapshotEvents: () => [] }
  const value = await input.execute({ verb: 'tap', x: 5, y: 6 }, { agent: { session } })
  assert.equal(value.ok, true)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].args.session, 'sess-A', 'payload.session 带的是 **id 字符串**（壳侧归属校验用）')
  assert.ok(calls[0].auth !== undefined, 'auth 必须显式传出——否则服务面按 fail-closed 拒绝（denied-no-session）')
  assert.equal(calls[0].auth.session, session, 'auth.session 必须是**会话对象本身**，不得降级成 id 字符串')
})

test('android_vdisplay_input：会话对象缺 id 时仍走 auth（归属键可缺、档位门不能缺）', async () => {
  const { face, calls } = strictFace()
  const input = loadTools(face).find((t) => t.name === 'android_vdisplay_input')
  const session = { snapshotEvents: () => [] }   // 无 id 字段
  await input.execute({ verb: 'tap', x: 1, y: 1 }, { agent: { session } })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].args.session, undefined, '取不到 id 时不编造归属键')
  assert.equal(calls[0].auth?.session, session, '档位门仍要拿到对象（否则又被 fail-closed 拒）')
})

test('android_vdisplay_create/destroy 同样带 auth（同一解析路径，不许只修一条）', async () => {
  const { face, calls } = strictFace()
  const tools = loadTools(face)
  const session = { id: 'sess-B', snapshotEvents: () => [] }
  for (const name of ['android_vdisplay_create', 'android_vdisplay_destroy']) {
    const tool = tools.find((t) => t.name === name)
    await tool.execute({}, { agent: { session } })
  }
  assert.equal(calls.length, 2)
  for (const c of calls) {
    assert.equal(c.auth?.session, session, c.op + ' 的会话必须走 auth（对象），而不是只挂 payload')
  }
})

test('没有会话来源时不得伪造 auth（如实让服务面 fail-closed）', async () => {
  const { face, calls } = strictFace()
  const input = loadTools(face).find((t) => t.name === 'android_vdisplay_input')
  await input.execute({ verb: 'tap', x: 1, y: 2 }, {})
  assert.equal(calls.length, 1)
  assert.equal(calls[0].auth, undefined, '会话缺席时必须是 undefined（编一个假会话等于绕过档位门）')
  assert.equal(calls[0].args.session, undefined, 'payload 里同样不得凭空造会话')
})
// ── 0.14.0 用户实报回归：序号复用 / 空闲回收 / 销毁权限 ──────────────────────

test('序号复用：建→销毁→再建 的别名必须回到最小空闲号（不得单调递增）', async () => {
  // 缺陷形态（实测复现）：旧实现每建一块就 nextAliasIndex += 1 且从不回收，
  // 反复建/销毁得到 virtual-1 → virtual-2 → virtual-3 …，上限只有 1 块时界面显示
  // 「虚拟屏 3」却切不回之前那块，用户无法理解。
  //
  // 这里用**源码级断言**守住分配形态（壳侧 Kotlin 无法在 node 侧执行）：
  // 分配必须是「扫描最小空闲序号」，且不得再出现单调计数器。
  const { readFileSync } = await import('node:fs')
  const kotlin = readFileSync(KOTLIN_SOURCE, 'utf8')
  // 用纯字符串包含判断：正则里的 `$` 在本模板里易被当成锚点/插值，写成 include 最不易错。
  assert.ok(
    kotlin.includes('while (records.containsKey("virtual-$candidate")) candidate += 1'),
    '分配必须扫描最小空闲序号',
  )
  // 只看**代码**，不看注释：注释里会引用旧实现（`nextAliasIndex += 1`）作为背景说明，
  // 直接对全文断言会被自己的说明文字判红（实测踩到）。
  const codeOnly = kotlin
    .split('\n')
    .filter((line) => !line.trim().startsWith('//') && !line.trim().startsWith('*') && !line.trim().startsWith('/*'))
    .join('\n')
  assert.doesNotMatch(codeOnly, /nextAliasIndex \+= 1/, '不得保留单调递增计数器（序号永不回收）')
})

test('空闲回收：必须存在按「最后使用时间」判定的回收路径', async () => {
  // 用户要求：「对话数分钟不运行且虚拟屏无操作则 kill 掉，否则会一直占用资源」。
  // 虚拟屏持有 display + ImageReader + HandlerThread，是真实系统资源；此前**没有任何回收路径**。
  const { readFileSync } = await import('node:fs')
  const kotlin = readFileSync(KOTLIN_SOURCE, 'utf8')
  assert.match(kotlin, /fun reclaimIdle\(/, '必须有 reclaimIdle 回收入口')
  assert.match(kotlin, /IDLE_RECLAIM_MS/, '必须有空闲阈值常量')
  assert.match(kotlin, /lastUsedAt/, '必须记录最后使用时间')
  // 回收必须真的释放三种系统资源。
  assert.match(kotlin, /display\.release\(\)/,'回收必须释放 display')
  assert.match(kotlin, /reader\.close\(\)/,'回收必须关闭 reader')
  assert.match(kotlin, /thread\.quitSafely\(\)/,'回收必须停掉 reader 线程')
})

test('销毁权限：任何会话都能销毁（不得因归属把资源锁死）', async () => {
  // 用户明确要求：「别忘了给模型销毁权限，否则没人能关掉了」。
  // 隔离的目的是「各会话看不到、不阻塞」，绝不是「把有上限的稀缺资源锁死」。
  const { readFileSync } = await import('node:fs')
  const kotlin = readFileSync(KOTLIN_SOURCE, 'utf8')
  const destroyBody = kotlin.slice(kotlin.indexOf('fun destroy('), kotlin.indexOf('fun select('))
  assert.doesNotMatch(destroyBody, /requireOwner\(/, 'destroy 不得按归属拒绝（否则创建者消失后没人能关）')
  assert.match(destroyBody, /records\.keys\.firstOrNull\(\)/, '必须有「任意一块」兜底，保证只要存在就能关掉')
})
test('资源全局唯一、归属只管呈现：幂等判断必须看「本机是否已有屏」而不是「本会话是否已有屏」', async () => {
  // 我自己的真实回归：把幂等写成「本会话是否已有屏」后，
  //   面板先建屏(owner=null) → 模型再建(owner=X) 被算作「本会话没有屏」→ 建第 2 块 → 撞上限。
  // 教训：虚拟屏是**全局稀缺资源**（上限 1 块），隔离的是「看/操作」，不是「资源本身」。
  const { readFileSync } = await import('node:fs')
  const kotlin = readFileSync(KOTLIN_SOURCE, 'utf8')
  const createBody = kotlin.slice(kotlin.indexOf('fun create('), kotlin.indexOf('/** Explicitly remove one display'))
  assert.ok(
    createBody.includes('records.size'),
    '幂等判断必须基于本机屏总数（records.size），而不是按 owner 过滤',
  )
  assert.doesNotMatch(
    createBody,
    /count \{ it\.owner == session \}/,
    '不得按会话过滤计数：那会把全局唯一资源当成每会话私有资源（第 2 个会话必撞上限）',
  )
})
// ── 0.14.0 真机实锤回归：状态与控制 op 必须同源（不得自相矛盾） ─────────────────

test('状态必须走控制队列：只有 controlExec 的服务（引擎侧真实形状）不得报「桥面未接通」', async () => {
  // 缺陷形态（真机实锤，用户复现）：同一次交互里
  //   android_vdisplay_create → ok:true，displayId=25，state=active
  //   android_vdisplay_status → ok:false，code=vdisplay-shell-not-wired
  // 「有屏但没用处」，模型据此判定能力不可用而放弃整条路径。
  //
  // 真因：引擎侧 AndroidPrivilegeService **只暴露 controlExec**（create/destroy 都从这条路走通），
  // 从来没有 vdisplayStatus 字段；而工具却去读 vdisplayStatus?.()，于是恒拿 undefined。
  //
  // 危害与「声称可用而实际不可用」等价：**声称不可用而实际可用**同样让模型做出错误决策。
  const { face } = strictFace()
  const tools = loadTools(face)
  const status = tools.find((t) => t.name === 'android_vdisplay_status')
  assert.ok(status, 'android_vdisplay_status 必须注册')
  const value = await status.execute({}, { agent: { session: 't' } })
  const text = JSON.stringify(value ?? {})
  assert.doesNotMatch(text, /vdisplay-shell-not-wired/, '服务在场且 vdInfo 可用时不得报「桥面未接通」: ' + text)
  assert.equal(value.ok, true, '控制队列可用时必须如实报可用: ' + text)
  assert.equal(value.state, 'active', '必须透传壳侧 state: ' + text)
  assert.equal(value.displayId, 42, '必须透传 displayId: ' + text)
})

test('状态工具同样不得出现服务方法接收者丢失（与 create/destroy 同一缺陷类）', async () => {
  const { face} = strictFace()
  const tools = loadTools(face)
  const status = tools.find((t) => t.name === 'android_vdisplay_status')
  const value = await status.execute({}, { agent: { session: 't' } })
  const text = JSON.stringify(value ?? {})
  assert.doesNotMatch(text, LOST_RECEIVER, '状态读取不得摘出方法裸调: ' + text)
  assert.doesNotMatch(text, /is not a function/, text)
})

test('控制队列失败必须归因准确：读不到 ≠ 没落地（不得回退成「壳侧未接通」）', async () => {
  // 「静默降级并且归因错误」是本轮两轮阻碍的共同主题：读不到就说「没落地」，
  // 会让模型放弃一条其实可用的路径。失败必须区分「通路异常」与「能力缺席」。
  const face = {
    controlQueue: {},
    async controlExec() { throw new TypeError('boom') },
  }
  const tools = loadTools(face)
  const status = tools.find((t) => t.name === 'android_vdisplay_status')
  const value = await status.execute({}, { agent: { session: 't' } })
  assert.equal(value.ok, false)
  assert.equal(value.code, 'vdisplay-control-exception')
  assert.doesNotMatch(String(value.code), /not-wired/, '通路异常不得报成能力未落地')
  assert.match(String(value.guidance), /create/, '指引必须提示可以试建屏复核，而不是让模型放弃')
})

test('控制队列回执 ok:false 时同样归因到通路（vdisplay-control-failed）', async () => {
  const face = {
    async controlExec() { return { ok: false, error: 'queue-timeout' } },
  }
  const tools = loadTools(face)
  const status = tools.find((t) => t.name === 'android_vdisplay_status')
  const value = await status.execute({}, { agent: { session: 't' } })
  assert.equal(value.ok, false)
  assert.equal(value.code, 'vdisplay-control-failed')
  assert.match(String(value.guidance), /queue-timeout/, '必须把壳侧原因带出来')
})

test('真的没有任何来源时才报 vdisplay-shell-not-wired（保留「真未接通」语义）', async () => {
  const tools = loadTools(undefined)
  const status = tools.find((t) => t.name === 'android_vdisplay_status')
  const value = await status.execute({}, { agent: { session: 't' } })
  assert.equal(value.ok, false)
  assert.equal(value.code, 'vdisplay-shell-not-wired')
})
