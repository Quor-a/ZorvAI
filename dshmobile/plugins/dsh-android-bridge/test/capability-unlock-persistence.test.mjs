// P0（用户 2026-09-26 亲报）：解锁必须**跨进程重启存活**，而不是每次重进重新授权。
//
// 用户原话：「如果划掉软件重进，ai就得从新获取权限（浏览器和手机控制）而不是解锁一次对话永久起效，
// 这俩一波修了」。
//
// 真因（Lead 定位于源码）：capability-gate 的解锁态是纯内存 WeakMap<agent, ...>，键随进程消失；
// 解锁动作只是 fiber.dispose()，不落持久化 => 进程重启 = 解锁全丢。
//
// 本文件覆盖 task-58 的四条必测 + 三条边界。每条都配**反证**（删掉持久化读回必须判红）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  installCapabilityGate,
  DEVICE_TOOL_GROUPS,
  UNLOCK_STATE_FILE,
  UNLOCK_STATE_VERSION,
  createFileUnlockStore,
  parseUnlockedGroups,
  serializeUnlockedGroups,
} from '../lib/capability-gate.js'
import { mkdtempSync, readFileSync as readFileSyncNode, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/** 内存 UnlockStore：模拟「跨进程重启」——store 对象在两次 install 之间存活，agent 对象换新。 */
function memoryStore(initial = []) {
  let saved = [...initial]
  const writes = []
  return {
    read: () => [...saved],
    write: (groups) => { saved = [...groups]; writes.push([...groups]) },
    /** 当前磁盘内容（断言用）。 */
    peek: () => [...saved],
    writes,
  }
}

/**
 * 装一次门禁 = 模拟一次进程启动。
 * @param store 跨启动存活的持久化面。
 * @param denies 收集本进程内的 tools.restrict 掩蔽（模拟真实掩蔽）。
 */
function boot(store, options = {}) {
  const denies = []
  const registered = []
  const state = { disposeCount: 0 }
  const makeAgent = () => ({
    ctx: {
      inject: (_deps, callback) => {
        callback({
          tools: {
            restrict: (filter) => {
              denies.push(filter.deny)
              return { dispose: () => { state.disposeCount += 1 } }
            },
          },
        })
        return { dispose: () => { state.disposeCount += 1 } }
      },
    },
  })
  const agent = makeAgent()
  const ctx = {
    tools: { register: (tool) => { registered.push(tool) } },
    get: (name) => (name === 'agents' ? { list: () => options.existingAgents ?? [] } : undefined),
    on: (event, handler) => { if (event === 'agent/created') options.created?.(handler) },
    logger: { warn: () => {} },
  }
  installCapabilityGate(ctx, () => ({ a11y: false }), store)
  return { ctx, agent, denies, registered, state, makeAgent }
}

const maskedGroups = (denies) => {
  const byName = new Map()
  for (const deny of denies) {
    for (const [group, names] of Object.entries(DEVICE_TOOL_GROUPS)) {
      if (names.length === deny.length && names.every((n, i) => n === deny[i])) byName.set(group, true)
    }
  }
  return [...byName.keys()].sort()
}

// ---------------------------------------------------------------------------
// 必测 1：解锁 -> 重建 gate（模拟进程重启）-> 该组仍未被 mask
// ---------------------------------------------------------------------------

test('必测1：解锁 browser 后重建 gate（模拟进程重启），browser 组仍未被 mask', async () => {
  const store = memoryStore()
  // 第一次启动
  const first = boot(store)
  const handler = first.ctx.on // 直接用 events 走一遍
  // 触发一次 agent 创建（真实安装路径）
  let created
  const boot1 = boot(store, { created: (h) => { created = h } })
  created({ agent: boot1.agent })
  assert.deepEqual(maskedGroups(boot1.denies), ['browser', 'phone', 'virtual-display'], '首次启动必须三组全锁')
  await boot1.registered[0].execute({ group: 'browser' }, { agent: boot1.agent })
  assert.deepEqual(store.peek(), ['browser'], '解锁必须落盘')

  // **模拟进程重启**：新 boot，新 agent 对象，同一个 store
  let created2
  const boot2 = boot(store, { created: (h) => { created2 = h } })
  created2({ agent: boot2.agent })
  const masked = maskedGroups(boot2.denies)
  assert.equal(masked.includes('browser'), false, '重启后 browser 不得被重新 mask（本 P0 的修法本体）')
  assert.deepEqual(masked.sort(), ['phone', 'virtual-display'], '未解锁的两组仍必须锁定')
})

test('必测1 反证：删掉持久化读回（store 永远返回空），重启后 browser 仍会被 mask —— 必须判红', async () => {
  // 用「读回失效」的 store 复现缺陷形态：写能成功，但读永远空。
  const brokenStore = { read: () => [], write: () => {} }
  let created1
  const boot1 = boot(brokenStore, { created: (h) => { created1 = h } })
  created1({ agent: boot1.agent })
  await boot1.registered[0].execute({ group: 'browser' }, { agent: boot1.agent })

  let created2
  const boot2 = boot(brokenStore, { created: (h) => { created2 = h } })
  created2({ agent: boot2.agent })
  const masked = maskedGroups(boot2.denies)
  // 这正是修复前的现象：重启后 40 个工具全部回来掩蔽。
  assert.equal(masked.includes('browser'), true, '读回失效时必然复现本 P0（此断言即反证色）')
  assert.deepEqual(masked.sort(), ['browser', 'phone', 'virtual-display'])
})

// ---------------------------------------------------------------------------
// 必测 2：未解锁 -> 重启后仍锁定
// ---------------------------------------------------------------------------

test('必测2：从未解锁过 -> 重启后三组仍全部锁定（不得因持久化默认全开）', () => {
  const store = memoryStore()
  for (let bootCount = 0; bootCount < 3; bootCount++) {
    let created
    const b = boot(store, { created: (h) => { created = h } })
    created({ agent: b.agent })
    assert.deepEqual(maskedGroups(b.denies), ['browser', 'phone', 'virtual-display'], '第 ' + (bootCount + 1) + ' 次启动必须全锁')
  }
  assert.deepEqual(store.peek(), [], '从未解锁时不得写出任何解锁记录')
})

test('必测2b：新设备/首启（状态文件不存在）也必须默认锁定', async () => {
  // 真实文件存储 + 不存在的文件：read 必须 fail-closed 成空集。
  const store = createFileUnlockStore({
    file: '/nonexistent/dir/state.json',
    readFileSync: () => { throw new Error('ENOENT') },
    writeFileSync: () => {},
  })
  assert.deepEqual(store.read(), [], '读不到必须视作「没有任何组解锁」')

  let created
  const b = boot(store, { created: (h) => { created = h } })
  created({ agent: b.agent })
  assert.deepEqual(maskedGroups(b.denies), ['browser', 'phone', 'virtual-display'])
})

// ---------------------------------------------------------------------------
// 必测 3：持久化解锁不得让 a11y/Shizuku 缺失时的调用伪装成功
// ---------------------------------------------------------------------------

test('必测3：持久化解锁只让工具可见，不冒充权限可用 —— channels 必须如实汇报缺失', async () => {
  const store = memoryStore(['phone'])
  // 无障碍关着、Shizuku 未就绪（实测回执明确 false）
  const h = bootWithChannels(store, () => ({ a11y: false, shizuku: false }))
  const result = await h.registered[0].execute({ group: 'all' }, { agent: h.agent })
  // 解锁态：phone 已在持久化集合里（重启后仍有效）
  const phoneGroup = result.groups.find((g) => g.group === 'phone')
  assert.equal(phoneGroup.persisted, true, 'phone 应被标记为已持久化解锁')
  // 权限事实：必须仍报告 a11y 未开启、Shizuku 未就绪
  assert.deepEqual(result.channels, { a11y: false, shizuku: false })
  assert.match(result.text, /无障碍通道：未开启/)
  assert.match(result.text, /Shizuku 特权通道：未就绪（已实测/)
  // 且必须明说「解锁 != 可用」（否则模型会把已解锁误读成一定能用）
  assert.match(result.text, /不代表权限当前可用/)
  assert.match(result.text, /如实返回结构化失败/)
})

test('必测3 反证：不得把「已持久化解锁」渲染成通道就绪（两者混同即坑 161/167）', async () => {
  const store = memoryStore(['phone', 'browser', 'virtual-display'])
  const h = bootWithChannels(store, () => ({ a11y: false }))
  const result = await h.registered[0].execute({ group: 'all' }, { agent: h.agent })
  // 三组均持久化，但通道事实仍然必须说「未开启」。
  assert.equal(result.groups.every((g) => g.persisted), true)
  assert.equal(result.channels.a11y, false, '解锁不得把 a11y 变成 true')
  assert.doesNotMatch(
    result.text.split('\n').find((l) => l.startsWith('- 无障碍通道')),
    /已开启/,
    '解锁态绝不能污染通道事实',
  )
})

/** 与 boot 同形，但 channels 可注入（必测 3 需要）。 */
function bootWithChannels(store, channels) {
  const registered = []
  const denies = []
  const agent = {
    ctx: {
      inject: (_deps, callback) => {
        callback({ tools: { restrict: (filter) => { denies.push(filter.deny); return { dispose: () => {} } } } })
        return { dispose: () => {} }
      },
    },
  }
  const ctx = {
    tools: { register: (tool) => { registered.push(tool) } },
    get: () => undefined,
    on: () => {},
    logger: { warn: () => {} },
  }
  installCapabilityGate(ctx, channels, store)
  return { ctx, agent, denies, registered }
}

// ---------------------------------------------------------------------------
// 边界 4：用户必须有撤销路径
// ---------------------------------------------------------------------------

test('边界4：撤销（group=off）清空持久化解锁，重启后确实回到全锁', async () => {
  const store = memoryStore()
  let created
  const b1 = boot(store, { created: (h) => { created = h } })
  created({ agent: b1.agent })
  await b1.registered[0].execute({ group: 'all' }, { agent: b1.agent })
  assert.equal(store.peek().length, 3, 'all 解锁后三组都应落盘')

  // 撤销复用 group 的保留值 'off'（不新增参数：facade 在初始可见集里，预算按字节卡）。
  const revoked = await b1.registered[0].execute({ group: 'off' }, { agent: b1.agent })
  assert.deepEqual(revoked.unlocked, [], '撤销不得被当成解锁')
  assert.deepEqual(store.peek(), [], '撤销必须清空持久化集合')
  assert.match(revoked.text, /已撤销/)
  assert.match(revoked.text, /重启后也不会自动解锁/)

  // 重启：三组全部重新被 mask
  let created2
  const b2 = boot(store, { created: (h) => { created2 = h } })
  created2({ agent: b2.agent })
  assert.deepEqual(maskedGroups(b2.denies), ['browser', 'phone', 'virtual-display'],
    '撤销过的组重启后必须回到锁定')
})

test('边界4b：撤销全部（revoke + all）清空持久化，重启后三组全锁', async () => {
  const store = memoryStore(['phone', 'browser'])
  let created
  const b1 = boot(store, { created: (h) => { created = h } })
  created({ agent: b1.agent })
  await b1.registered[0].execute({ group: 'off' }, { agent: b1.agent })
  assert.deepEqual(store.peek(), [], 'revoke all 必须清空持久化集合')
  let created2
  const b2 = boot(store, { created: (h) => { created2 = h } })
  created2({ agent: b2.agent })
  assert.deepEqual(maskedGroups(b2.denies), ['browser', 'phone', 'virtual-display'])
})

// ---------------------------------------------------------------------------
// 边界 2（fail-closed）与存储自身
// ---------------------------------------------------------------------------

test('存储 fail-closed：坏 JSON / 未知版本 / 结构不符 一律视作未解锁', () => {
  assert.deepEqual(parseUnlockedGroups('not json'), [])
  assert.deepEqual(parseUnlockedGroups('null'), [])
  assert.deepEqual(parseUnlockedGroups('[]'), [])
  assert.deepEqual(parseUnlockedGroups(JSON.stringify({ version: 999, unlocked: ['phone'] })), [], '未知版本必须 fail-closed')
  assert.deepEqual(parseUnlockedGroups(JSON.stringify({ version: UNLOCK_STATE_VERSION, unlocked: 'phone' })), [])
  assert.deepEqual(parseUnlockedGroups(JSON.stringify({ version: UNLOCK_STATE_VERSION, unlocked: [1, 'phone', null] })), ['phone'], '非字符串项必须被剔除')
  assert.deepEqual(parseUnlockedGroups(''), [])
})

test('存储：序列化去重、只收非空字符串；坏数据写不进去', () => {
  const text = serializeUnlockedGroups(['phone', 'phone', '', 'browser'])
  assert.deepEqual(JSON.parse(text), { version: UNLOCK_STATE_VERSION, unlocked: ['phone', 'browser'] })
})

test('存储：写失败不得抛（持久化是尽力而为，进程内解锁仍有效）', () => {
  const store = createFileUnlockStore({
    file: '/x/state.json',
    writeFileSync: () => { throw new Error('EROFS') },
    renameSync: () => {},
  })
  assert.doesNotThrow(() => store.write(['phone']))
})

test('存储：文件路径取自 DSH_HOME，文件名固定', () => {
  const calls = []
  const store = createFileUnlockStore({
    readFileSync: (path) => { calls.push(path); throw new Error('ENOENT') },
    writeFileSync: () => {},
  })
  store.read()
  assert.equal(calls.length, 1)
  assert.match(calls[0], /\.capability-unlock\.json$/)
  assert.equal(UNLOCK_STATE_FILE, '.capability-unlock.json')
})

// ---------------------------------------------------------------------------
// 边界 1：恢复点必须在掩蔽之前
// ---------------------------------------------------------------------------

test('边界1：已持久化解锁的组在**启动时**就不得被 restrict（恢复先于掩蔽）', () => {
  const store = memoryStore(['phone'])
  let created
  const b = boot(store, { created: (h) => { created = h } })
  created({ agent: b.agent })
  const masked = maskedGroups(b.denies)
  assert.equal(masked.includes('phone'), false, '已解锁组绝不能在启动时被 restrict 一次')
  assert.deepEqual(masked.sort(), ['browser', 'virtual-display'])
})

test('边界1b：对**既有** agent（agents.list）重放锁定时同样跳过已解锁组', () => {
  const store = memoryStore(['browser'])
  // installCapabilityGate 会对 agents.list() 里的既有 agent 立刻 lock。
  const existing = [{
    ctx: {
      inject: (_deps, callback) => {
        callback({ tools: { restrict: (filter) => { spy.push(filter.deny); return { dispose: () => {} } } } })
        return { dispose: () => {} }
      },
    },
  }]
  const spy = []
  const ctx = {
    tools: { register: () => {} },
    get: (name) => (name === 'agents' ? { list: () => existing } : undefined),
    on: () => {},
    logger: { warn: () => {} },
  }
  installCapabilityGate(ctx, () => ({ a11y: false }), store)
  const groups = maskedGroups(spy)
  assert.equal(groups.includes('browser'), false, '既有 agent 也不得重新掩蔽已解锁组')
  assert.deepEqual(groups.sort(), ['phone', 'virtual-display'])
})

// ---------------------------------------------------------------------------
// 回归：不得改变分组语义与工具名单
// ---------------------------------------------------------------------------

test('回归：分组语义与工具名单未被改动（三组、phone 17 项）', () => {
  assert.deepEqual(Object.keys(DEVICE_TOOL_GROUPS).sort(), ['browser', 'phone', 'virtual-display'])
  assert.equal(DEVICE_TOOL_GROUPS.phone.length, 17)
  assert.equal(DEVICE_TOOL_GROUPS.browser.length, 19)
  assert.equal(DEVICE_TOOL_GROUPS['virtual-display'].length, 4)
})

// ---------------------------------------------------------------------------
// 反证（自测抓到的真实缺陷类）：默认钩子必须落到**真正的 node:fs**
// ---------------------------------------------------------------------------

test('反证：createFileUnlockStore() 不给钩子时必须真的落盘（否则「测试全绿但 P0 没修」）', () => {
  // 这是实现期真实踩到的形态：把 fs 钩子默认成 undefined，注入桩的单测全绿，
  // 但生产路径 read 恒空、write 静默 no-op —— P0 根本没修。这里用真实文件系统证伪。
  const dir = mkdtempSync(join(tmpdir(), 'dsh-unlock-'))
  try {
    const file = join(dir, 'state.json')
    const store = createFileUnlockStore({ file })
    // 初始：文件不存在 -> fail-closed 空集
    assert.deepEqual(store.read(), [])
    // 写入后必须能被**重新读回**（模拟另一个进程）
    store.write(['phone', 'browser'])
    const reread = createFileUnlockStore({ file })
    assert.deepEqual([...reread.read()].sort(), ['browser', 'phone'], '真实落盘后必须能读回')
    // 且磁盘内容可独立解析
    const raw = readFileSyncCompat(file)
    assert.deepEqual([...parseUnlockedGroups(raw)].sort(), ['browser', 'phone'])
    // 覆盖写：撤销一组后只留一组
    store.write(['phone'])
    assert.deepEqual([...createFileUnlockStore({ file }).read()], ['phone'])
    // 目录不存在时必须自动创建（不抛）
    const nested = join(dir, 'a', 'b', 'state.json')
    const nestedStore = createFileUnlockStore({ file: nested })
    assert.doesNotThrow(() => nestedStore.write(['phone']))
    assert.deepEqual([...nestedStore.read()], ['phone'])
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

/** 读文件（本测试专用，避免顶层再 import 一个名字）。 */
function readFileSyncCompat(path) {
  // eslint-disable-next-line no-undef
  return globalThis.require === undefined
    ? readFileSyncNode(path)
    : readFileSyncNode(path)
}
