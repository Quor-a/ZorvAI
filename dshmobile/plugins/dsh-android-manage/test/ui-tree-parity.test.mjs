// 树工具「同体验」契约（用户口径 2026-09-19）：
//   「android_ui_tree 理应可以做到和无障碍一样的体验 —— 确保 AI 在只有 ADB 的情况下体验也很好
//    （因为猜像素就是折磨）」。
//
// 本文件把这句话钉成三条可判红的判据：
//   1) **同形**：两个工具的 output.schema 必须逐字段相同（复制两份必然漂移——本仓先例：op 清单、家族表）；
//   2) **可用**：纯 Shizuku（无障碍关）下 ui_tree 必须产出与 ui_dump 同形的节点清单，而不是 XML 文件路径；
//   3) **可操作**：清单里的 ref 必须能被 android_ui_click 解析并落到坐标注入（模型只报 ref，像素由引擎算）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const mod = await import(pathToFileURL(join(HERE, '..', 'lib', 'index.js')).href)
const FIXTURE = join(HERE, 'fixtures', 'ui-probe.xml')

/** 无障碍关（backend 走 ADB）的桩：execAdbLine 会把 uiautomator XML「拉」到工具要求的路径上。 */
function makeFace() {
  const calls = { control: [], adbShell: [], adbLine: [] }
  const face = {
    gateFor: () => ({ ok: true }),
    audit: () => {},
    controlDecision: () => ({ backend: 'adb', reason: 'test-adb' }),
    controlExec: async (op, args) => { calls.control.push({ op, args }); return { ok: true, data: { done: true } } },
    execAdbShell: async (command) => { calls.adbShell.push(command); return { ok: true, stdout: '' } },
    execAdbLine: async (line) => {
      calls.adbLine.push(line)
      // 命令形如：`adb shell uiautomator dump <remote>; adb pull <remote> <local> ...; adb shell wm size ...`
      const pull = /adb pull (\S+) (\S+)/.exec(line)
      if (pull !== null) {
        const local = pull[2]
        mkdirSync(dirname(local), { recursive: true })
        writeFileSync(local, readFileSync(FIXTURE))
      }
      return { ok: true, stdout: 'Physical size: 900x1600\n' }
    },
  }
  return { face, calls }
}

function applyManage(face) {
  const tools = []
  mod.apply({
    logger: () => ({ warn: () => {}, debug: () => {} }),
    tools: { register: (t) => tools.push(t) },
    get: () => undefined,
    androidPrivilege: face,
  })
  return (n) => tools.find((t) => t.name === n)
}

const exec = { agent: { session: 's1' } }

test('同形契约：android_ui_tree 与 android_ui_dump 的 output.schema 必须逐字段相同', () => {
  const { face } = makeFace()
  const byName = applyManage(face)
  const dump = byName('android_ui_dump')
  const tree = byName('android_ui_tree')
  assert.ok(dump && tree, '两个树工具都必须注册')
  assert.deepEqual(
    tree.output.schema,
    dump.output.schema,
    '两个树工具的输出 schema 漂移了——「同体验」的第一层就是同形，漂移即回归',
  )
})

test('纯 Shizuku（无障碍关）：ui_tree 产出同形节点清单 + 落明细，不再只回文件路径', async (t) => {
  if (!existsSync(FIXTURE)) { t.skip('缺少 uiautomator XML fixture：' + FIXTURE); return }
  const { face } = makeFace()
  const byName = applyManage(face)
  const r = await byName('android_ui_tree').execute({}, exec)
  assert.equal(r.ok, true, '纯 Shizuku 下必须成功：' + JSON.stringify(r).slice(0, 200))
  assert.ok(Array.isArray(r.nodes) && r.nodes.length > 0, '必须返回节点数组（不是 XML 路径）')
  assert.equal(r.count, r.nodes.length, 'count 必须等于 nodes.length')
  assert.ok(r.rawCount >= r.count, 'rawCount 是原始节点数，应不小于剪枝后的 count')
  assert.equal(typeof r.detailHandle, 'string', '必须回落盘明细句柄（供 android_ui_detail 分页）')
  assert.deepEqual(Object.keys(r.screen).sort(), ['h', 'w'])
  // 行格式：ref 形如 n<idx>，且必须带 bounds（模型据此判断层级，而不是靠猜测像素）
  const first = r.nodes.find((n) => n.clickable === true) ?? r.nodes[0]
  assert.match(String(first.id), /^n[0-9]+$/, 'ref 形态必须是 n<idx>')
  assert.ok(Number.isFinite(first.cx) && Number.isFinite(first.cy), '节点必须带中心坐标（引擎算，不由模型猜）')
})

test('可操作契约：ui_tree 的 ref 必须能被 ui_click 解析并落到坐标注入（模型只报 ref）', async (t) => {
  if (!existsSync(FIXTURE)) { t.skip('缺少 uiautomator XML fixture'); return }
  const { face, calls } = makeFace()
  const byName = applyManage(face)
  const tree = await byName('android_ui_tree').execute({}, exec)
  const target = tree.nodes.find((n) => n.clickable === true)
  assert.ok(target !== undefined, 'fixture 里应至少有一个可点节点')
  const r = await byName('android_ui_click').execute({ ref: target.id }, exec)
  assert.equal(r.ok, true, '按 ref 点击必须成功（无障碍关时落坐标路径）：' + JSON.stringify(r).slice(0, 200))
  const taps = calls.adbShell.filter((c) => /^input tap /.test(c))
  assert.equal(taps.length, 1, '必须恰好注入一次坐标点击：' + JSON.stringify(calls.adbShell))
  assert.match(taps[0], new RegExp(`^input tap ${target.cx} ${target.cy}$`), '注入坐标必须是该节点的中心：' + taps[0])
})

test('优先级契约：ui_tree 的描述必须说清「无障碍关时用我」与「只读默认屏」', () => {
  const { face } = makeFace()
  const byName = applyManage(face)
  const desc = String(byName('android_ui_tree').description ?? '')
  assert.match(desc, /无障碍未开启时/, '必须写明它是无障碍关时的控件树来源（否则模型会去撞不可用的 ui_dump）')
  assert.match(desc, /只读默认屏/, '必须写明只能读默认屏（虚拟屏要走别的路，别让模型白试）')
  assert.match(desc, /不需要无障碍/, '必须写明不需要无障碍——这是它存在的理由')
})
