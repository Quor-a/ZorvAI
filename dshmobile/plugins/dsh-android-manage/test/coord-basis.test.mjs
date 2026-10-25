// coord-basis.test.mjs — issue #258 反证用例：工具返回**必须回显归一化基准**。
//
// 缺陷形态（用户实报，2200x1440 设备、DSH 自由窗口 bounds=(1438,106) 733x1389）：
// 壳侧按**当前窗口**尺寸（而非整屏）换算 nx/ny，`nx=0.712` 实际落到 522（= 0.712*733，
// 屏幕左侧背景应用），返回文案却只有「实际坐标 522,63」，看不出基准是谁——于是这个缺陷
// 在被用户算出来之前一直是隐形的。
//
// issue 明确要求「在工具返回里回显所用基准」，本文件钉住这条：
//   A. 壳侧回显 basis/basisWidth/basisHeight → 工具值带这三个字段、文案里也出现该基准；
//   B. 壳侧是修好前的旧版本（不回显）→ 文案必须**如实标注「基准未回显」**，不得假装知道；
//   C. 壳侧回显的是缺陷基准 window-current（本机拿不到整屏尺寸）→ 文案必须带告警；
//   D. 坐标点击的生效校验措辞不得再报「已生效」（issue §5.1 误报成功）——只比较全局画面
//      变化并不验证命中目标，必须说「未验证目标」。
//
// 反证方向：把回显从第 16xx 行删掉，A 立即红；把 verifyClick 的 targetVerified 参数去掉、
// 或把文案改回「界面已变化（已生效）」，D 立即红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const mod = await import(pathToFileURL(join(HERE, '..', 'lib', 'index.js')).href)

/** 桩：a11y 后端 + 可配置的 click 回包（`data` 即壳侧返回值）。 */
function makeFace({ clickData = {} } = {}) {
  const calls = { control: [] }
  const face = {
    gateFor: () => ({ ok: true }),
    audit: () => {},
    controlDecision: () => ({ backend: 'a11y', reason: 'test-a11y' }),
    controlExec: async (op, args) => {
      calls.control.push({ op, args })
      if (op === 'click' || op === 'longClick') return { ok: true, data: { x: 1566, y: 65, via: 'gesture-norm', ...clickData } }
      if (op === 'state') return { ok: true, data: { gen: 3, invalidated: true } }
      return { ok: true, data: {} }
    },
    execAdbShell: async () => ({ ok: true, stdout: '' }),
    execAdbLine: async () => ({ ok: true, stdout: 'Physical size: 2200x1440' }),
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
  return { tools, byName: (n) => tools.find((t) => t.name === n) }
}

const EXEC = { agent: { session: 's1' } }
const CLICK = (face) => applyManage(face).byName('android_ui_click')

test('#258 A：壳侧回显基准时，工具值与文案都必须带上它（本次换算按哪块尺寸自证）', async () => {
  const { face, calls } = makeFace({ clickData: { basis: 'screen-max-window', basisWidth: 2200, basisHeight: 1440 } })
  const r = await CLICK(face).execute({ nx: 0.712, ny: 0.045 }, EXEC)
  assert.equal(r.ok, true)
  assert.equal(calls.control.find((c) => c.op === 'click').args.nx, 0.712, 'nx 必须原样透传给壳侧换算')
  assert.equal(r.basis, 'screen-max-window')
  assert.equal(r.basisWidth, 2200)
  assert.equal(r.basisHeight, 1440)
  assert.match(String(r.text), /screen-max-window/)
  assert.match(String(r.text), /2200x1440/)
  assert.equal(String(r.text).includes('基准未回显'), false, '壳侧已回显时不得出现「未回显」字样')
})

test('#258 B（反证核心）：壳侧未回显基准时必须如实标注，不得假装知道', async () => {
  const { face } = makeFace({ clickData: {} })
  const r = await CLICK(face).execute({ nx: 0.712, ny: 0.045 }, EXEC)
  assert.equal(r.ok, true)
  assert.equal('basis' in r, false, '壳侧没给基准，工具层不得编造一个（0 值会让「未回显」看起来像「0x0 基准」）')
  assert.match(String(r.text), /基准未回显/, '老壳侧必须被如实标注，否则基准回显形同虚设')
})

test('#258 C：壳侧回显缺陷基准 window-current 时必须告警（本机未给出整屏尺寸）', async () => {
  const { face } = makeFace({ clickData: { basis: 'window-current', basisWidth: 733, basisHeight: 1389 } })
  const r = await CLICK(face).execute({ nx: 0.712, ny: 0.045 }, EXEC)
  assert.equal(r.basis, 'window-current')
  assert.match(String(r.text), /window-current/)
  assert.match(String(r.text), /可能偏左/, '缺陷基准必须显式告警，不得静默使用')
})

test('#258 D：坐标点击的生效校验不得报「已生效」，必须如实说未验证目标', async () => {
  const { face } = makeFace({ clickData: { basis: 'screen-max-window', basisWidth: 2200, basisHeight: 1440 } })
  const r = await CLICK(face).execute({ nx: 0.712, ny: 0.045 }, EXEC)
  assert.equal(String(r.text).includes('（已生效）'), false, '坐标点击未验证命中目标，不得报「已生效」')
  assert.match(String(r.text), /未验证目标/, 'issue §5.1：应改为「画面已变化（未验证目标）」')
})

test('#258 E：按 ref 的语义点击仍可报「已生效」（修复不得把可靠的语义路径一起降级）', async () => {
  const nodes = [
    { id: '0', parentId: '', attrs: { bounds: '[0,0][2200,1440]', class: 'android.widget.FrameLayout', clickable: 'false', scrollable: 'false', editable: 'false', text: '', 'content-desc': '' } },
    { id: '0.0', parentId: '0', attrs: { bounds: '[1500,50][1700,150]', class: 'android.widget.Button', clickable: 'true', scrollable: 'false', editable: 'false', text: '最小化', 'content-desc': '', 'resource-id': 'com.x:id/btn' } },
  ]
  const { face } = makeFace({ clickData: { via: 'ACTION_CLICK' } })
  face.controlExec = async (op) => {
    if (op === 'snapshot') return { ok: true, data: { gen: 7, rotation: 0, screen: { w: 2200, h: 1440 }, nodes } }
    if (op === 'state') return { ok: true, data: { gen: 8, invalidated: true } }
    return { ok: true, data: {} }
  }
  const { byName } = applyManage(face)
  await byName('android_ui_dump').execute({}, EXEC)
  const r = await byName('android_ui_click').execute({ ref: 'text:最小化' }, EXEC)
  assert.equal(r.ok, true)
  assert.match(String(r.text), /已生效/, '语义点击目标已解析，仍应报已生效')
})
