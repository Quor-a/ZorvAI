// A1（0.14.1 设备实测缺陷）的引擎侧修法判据：caps 缺席时的补探、TTL、以及**绝不把未知降级成 false**。
//
// 为什么单独成文件：这条修法的风险不是「探不到」，而是「探不到被当成不可用」——那正是缺陷本身。
// 因此每个用例都同时断言「探测行为」与「返回值的三态身份」。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AndroidPrivilegeService } from '../lib/index.js'

/**
 * 造一个只实现队列契约的替身：`enqueue` 记录调用，并在「壳侧回执」里写入 caps（模拟真实链路
 * ——caps 是随控制 op 的回执抵达的，引擎从不自己知道自己有没有特权）。
 */
function serviceWith(initialCaps, options = {}) {
  const state = { caps: initialCaps, enqueued: [] }
  const queue = {
    stats: () => ({
      waiting: false, served: 0, failed: 0, lastTakeAt: 0, lastResultAt: 0, protocol: {},
      ...(state.caps === undefined ? {} : { caps: state.caps }),
    }),
    enqueue: async (op, args) => {
      state.enqueued.push({ op, args })
      if (options.fail) throw new Error('shell offline')
      // 回执把壳侧真实能力带回来（capsExtra 的 a11y/shizuku）。
      state.caps = options.caps ?? { a11y: true, shizuku: true }
      return { ok: true, data: {} }
    },
    pollAgeMs: () => 0,
  }
  const svc = new AndroidPrivilegeService({}, undefined, undefined, undefined, queue)
  return { svc, state }
}

test('A1：caps 缺席时补探一次，并把新回执里的 caps 读回来（未知 → 就绪）', async () => {
  const { svc, state } = serviceWith(undefined)
  assert.equal(svc.shizukuChannel(), undefined, '补探前是「未知」，不是 false')
  const probed = await svc.shizukuChannelProbed()
  assert.equal(probed, true, '补探后应拿到壳侧实测事实')
  assert.equal(svc.shizukuReady(), true)
  assert.equal(state.enqueued.length, 1, '只打一发控制队列')
  assert.equal(state.enqueued[0].op, 'vdInfo', '补探必须用最廉价的读面 op（不在档位门内）')
})

test('A1：已有 caps 时零额外往返（不打扰单槽控制队列）', async () => {
  const { svc, state } = serviceWith({ a11y: true, shizuku: false })
  assert.equal(await svc.shizukuChannelProbed(), false)
  assert.equal(state.enqueued.length, 0, 'caps 已在手就不该再发请求')
})

test('A1：探测失败必须保持「未知」，绝不降级成 false（否则缺陷原样复发）', async () => {
  const { svc } = serviceWith(undefined, { fail: true })
  const probed = await svc.shizukuChannelProbed()
  assert.equal(probed, undefined, '壳侧没回执 ⇒ 未知；不得当成「未就绪」')
  assert.equal(svc.shizukuChannel(), undefined)
})

test('A1：壳侧回执明确说不可用时，才允许是 false', async () => {
  const { svc } = serviceWith(undefined, { caps: { a11y: true, shizuku: false } })
  assert.equal(await svc.shizukuChannelProbed(), false)
})

test('A1：TTL 内不重复探（连续询问只打一发）', async () => {
  const { svc, state } = serviceWith(undefined, { caps: { a11y: true, shizuku: false } })
  await svc.shizukuChannelProbed()
  await svc.shizukuChannelProbed()
  await svc.shizukuChannelProbed()
  assert.equal(state.enqueued.length, 1, 'TTL 窗口内重复询问不得各打一发')
})

test('A1：三态身份不得被布尔视图抹平——shizukuReady 只认实测为真', () => {
  const unknown = serviceWith(undefined).svc
  assert.equal(unknown.shizukuReady(), false, '未知按未就绪处理（仅限不需要三态的旧判据）')
  assert.equal(unknown.shizukuChannel(), undefined, '但三态接口必须仍能区分出「未知」')
})
