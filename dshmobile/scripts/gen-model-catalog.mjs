#!/usr/bin/env node
/**
 * gen-model-catalog.mjs — 从引擎树里的 pi-ai 厂商目录生成能力发现用的目录快照（0.13.5 W3）。
 *
 * 为什么需要：用户手填的自定义供应商路由（issue #125）没有任何能力元数据——
 * 自定义供应商面板只写 id/name/contextWindow/maxTokens，composer 因此没有思考档位可渲染。
 * 被动探测拿不到（通用 OpenAI 兼容 /models 不回能力字段），接受性探测不可信
 * （中转网关对任意 reasoning_effort 都回 200，2026-09-10 实测）。
 * 但引擎自带的 pi-ai 厂商目录**已声明**这些能力（thinkingLevelMap/input/compat），
 * 按精确模型 id 查表是「读厂商声明」而不是「按名字猜」。
 *
 * 输出：plugins/dsh-model-capability/lib/catalog-snapshot.json（随插件注入快照）。
 * 可复现：generatedAt 取 SOURCE_DATE_EPOCH（默认 1704067200），内容相同的两次生成字节一致。
 *
 * 用法：node scripts/gen-model-catalog.mjs [--engine-root <snapshotRoot>] [--out <file>]
 *   默认 engine-root 依次尝试：
 *     .deploy-tmp/snapshot-013/x86_64/stage/root
 *     \\wsl.localhost\<distro>\root\.dsh-stage\x86_64   （0.13.5 W5 之后的 ext4 工作区）
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { wslHostPath } from './lib/shell.mjs'

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const argv = process.argv.slice(2)
const argOf = (name, fallback) => {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback
}

const OUT = resolve(argOf('--out', join(ROOT, 'plugins', 'dsh-model-capability', 'lib', 'catalog-snapshot.json')))
const PI_SUBPATH = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@earendil-works/pi-ai'

const candidates = [
  argOf('--engine-root', ''),
  join(ROOT, '.deploy-tmp', 'snapshot-013', 'x86_64', 'stage', 'root'),
  join(ROOT, '.deploy-tmp', 'snapshot-013', 'arm64', 'stage', 'root'),
  wslHostPath('/root/.dsh-stage/x86_64'),
  wslHostPath('/root/.dsh-stage/arm64'),
].filter(Boolean)

const engineRoot = candidates.find((p) => existsSync(join(p, PI_SUBPATH, 'dist', 'providers', 'data')))
if (!engineRoot) {
  console.error('找不到 pi-ai 厂商目录。候选位置：\n  ' + candidates.join('\n  '))
  console.error('先跑 build-snapshot-013.mjs，或用 --engine-root 指定快照根（含 usr/lib/...）。')
  process.exit(1)
}

const piDir = join(engineRoot, PI_SUBPATH)
const dataDir = join(piDir, 'dist', 'providers', 'data')
const piVersion = JSON.parse(readFileSync(join(piDir, 'package.json'), 'utf8')).version
const SOURCE_DATE_EPOCH = Number(process.env.SOURCE_DATE_EPOCH ?? 1704067200)

/** 只保留能力相关字段：cost/description 等与发现无关，去掉可让快照小一个量级。 */
function compact(entry, providerId) {
  const out = { provider: typeof entry.provider === 'string' ? entry.provider : providerId }
  if (typeof entry.api === 'string') out.api = entry.api
  if (typeof entry.reasoning === 'boolean') out.reasoning = entry.reasoning
  if (entry.thinkingLevelMap && typeof entry.thinkingLevelMap === 'object') out.thinkingLevelMap = entry.thinkingLevelMap
  if (Array.isArray(entry.input)) out.input = entry.input.filter((m) => typeof m === 'string')
  if (typeof entry.contextWindow === 'number') out.contextWindow = entry.contextWindow
  if (typeof entry.maxTokens === 'number') out.maxTokens = entry.maxTokens
  const compat = entry.compat
  if (compat && typeof compat === 'object') {
    const picked = {}
    for (const key of ['thinkingFormat', 'supportsReasoningEffort', 'supportsReasoning', 'maxTokensField']) {
      if (compat[key] !== undefined) picked[key] = compat[key]
    }
    if (Object.keys(picked).length > 0) out.compat = picked
  }
  return out
}

const models = {}
let files = 0
for (const file of readdirSync(dataDir).filter((f) => f.endsWith('.json')).sort()) {
  const providerId = file.replace(/\.json$/, '')
  let catalog
  try {
    catalog = JSON.parse(readFileSync(join(dataDir, file), 'utf8'))
  } catch (error) {
    console.warn(`跳过 ${file}：${error.message}`)
    continue
  }
  files++
  for (const [api, group] of Object.entries(catalog)) {
    if (!group || typeof group !== 'object') continue
    for (const [id, raw] of Object.entries(group)) {
      if (!raw || typeof raw !== 'object') continue
      const entry = compact({ ...raw, api: raw.api ?? api }, providerId)
      if (!models[id]) models[id] = []
      // 同 provider 同 api 重复时保留最后一个（后写覆盖，与目录语义一致）
      const existing = models[id].findIndex((e) => e.provider === entry.provider && e.api === entry.api)
      if (existing >= 0) models[id][existing] = entry
      else models[id].push(entry)
    }
  }
}

for (const id of Object.keys(models)) {
  models[id].sort((a, b) => String(a.provider).localeCompare(String(b.provider)))
}

const snapshot = {
  schema: 1,
  source: `@earendil-works/pi-ai@${piVersion} dist/providers/data`,
  // A stable snapshot-relative hint keeps catalog bytes independent of the CI job or host checkout path.
  engineRootHint: PI_SUBPATH,
  generatedAt: new Date(SOURCE_DATE_EPOCH * 1000).toISOString(),
  modelCount: Object.keys(models).length,
  models,
}

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, JSON.stringify(snapshot) + '\n')
const kb = Math.round(Buffer.byteLength(JSON.stringify(snapshot)) / 1024)
console.log(`gen-model-catalog: pi-ai ${piVersion} / ${files} 个目录 / ${snapshot.modelCount} 个模型 / ${kb} KB -> ${OUT}`)
