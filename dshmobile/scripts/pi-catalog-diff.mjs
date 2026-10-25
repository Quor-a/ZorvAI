#!/usr/bin/env node
// pi-catalog-diff.mjs — pi-ai 模型目录 diff（0.13.3 W1/P2，交接文档）
// 用途：引擎升级 pin 变更时例行执行，输出两个版本 dist/providers/data/*.json 的
// 模型 id 删除/新增清单进构建日志与回归报告（信息性输出，不拒绝构建——模型增删是
// 上游常态，删除项由上游 0.1.5 的 strict/deferred 校验兜底不死整包——
// 写严格、读宽容：未知 modelOverrides id 记入 modelErrors 诊断而不抛错，非严格路径下
// 目录错误只跳过该 provider。0.13.7 追上游时 pi-drift-F1 降级补丁已因此退役）。
//
// 用法：
//   node scripts/pi-catalog-diff.mjs --from 0.84.2 --to 0.85.1 [--out <报告文件>] [--package @earendil-works/pi-ai]
//   --from/--to 接受 npm 版本号（经镜像拉 tgz，缓存 .deploy-tmp/pi-catalog-cache/）或本地目录路径。
// 退出码：0 正常（含模型删除）；2 元数据/网络失败（构建链可见）。
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execSync } from 'node:child_process'
import { createHash } from 'node:crypto'

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const CACHE = join(ROOT, '.deploy-tmp', 'pi-catalog-cache')
const MIRRORS = ['https://registry.npmjs.org', 'https://registry.npmmirror.com']

function argOf(flag, def) {
  const i = process.argv.indexOf(flag)
  return i >= 0 ? process.argv[i + 1] : def
}
const PKG = argOf('--package', '@earendil-works/pi-ai')
const FROM = argOf('--from')
const TO = argOf('--to')
const OUT = argOf('--out')
if (!FROM || !TO) { console.error('用法: node scripts/pi-catalog-diff.mjs --from <版本|目录> --to <版本|目录> [--out 报告] [--package 包名]'); process.exit(2) }

async function fetchMeta(name) {
  let lastErr
  for (const m of MIRRORS) {
    try {
      const r = await fetch(`${m}/${name}`, { signal: AbortSignal.timeout(30000) })
      if (!r.ok) throw new Error('HTTP ' + r.status)
      return await r.json()
    } catch (e) { lastErr = e }
  }
  throw new Error(`目录元数据不可得: ${name}（${lastErr}）`)
}

async function resolveDir(spec) {
  if (existsSync(spec)) return spec
  const short = PKG.replace('@', '').replace('/', '-')
  const dest = join(CACHE, `${short}-${spec}`)
  if (!existsSync(join(dest, 'dist', 'providers', 'data'))) {
    const meta = await fetchMeta(PKG)
    const dist = meta.versions?.[spec]?.dist
    if (!dist) throw new Error(`${PKG}@${spec} 不存在于镜像`)
    mkdirSync(CACHE, { recursive: true })
    const tgz = join(CACHE, `${short}-${spec}.tgz`)
    if (!existsSync(tgz)) {
      const r = await fetch(dist.tarball, { signal: AbortSignal.timeout(300000) })
      if (!r.ok) throw new Error(`tgz 下载失败: ${dist.tarball} HTTP ${r.status}`)
      const buf = Buffer.from(await r.arrayBuffer())
      if (dist.sha512 && createHash('sha512').update(buf).digest('base64') !== dist.sha512) {
        throw new Error(`tgz sha512 不匹配: ${PKG}@${spec}`)
      }
      writeFileSync(tgz, buf)
    }
    rmSync(dest, { recursive: true, force: true })
    mkdirSync(dest, { recursive: true })
    execSync(`tar -xzf "${tgz}" -C "${dest}" --strip-components=1`)
  }
  return dest
}

// 目录形态：{ "route 名": { "<模型 id>": {...}, ... }, ... }
function catalogOf(dir) {
  const dataDir = join(dir, 'dist', 'providers', 'data')
  if (!existsSync(dataDir)) throw new Error(`目录数据缺失: ${dataDir}`)
  const cat = {}
  for (const f of readdirSync(dataDir).filter((f) => f.endsWith('.json') && !f.startsWith('.'))) {
    const j = JSON.parse(readFileSync(join(dataDir, f), 'utf8'))
    const entry = {}
    for (const [route, models] of Object.entries(j)) {
      if (models && typeof models === 'object') entry[route] = new Set(Object.keys(models))
    }
    cat[f.replace(/\.json$/, '')] = entry
  }
  return cat
}

const fromDir = await resolveDir(FROM)
const toDir = await resolveDir(TO)
const from = catalogOf(fromDir)
const to = catalogOf(toDir)

const lines = []
let delTotal = 0, addTotal = 0
const allKeys = [...new Set([...Object.keys(from), ...Object.keys(to)])].sort()
for (const prov of allKeys) {
  const fr = from[prov] ?? {}, tr = to[prov] ?? {}
  const routes = [...new Set([...Object.keys(fr), ...Object.keys(tr)])].sort()
  for (const route of routes) {
    const a = fr[route] ?? new Set(), b = tr[route] ?? new Set()
    const del = [...a].filter((x) => !b.has(x)).sort()
    const add = [...b].filter((x) => !a.has(x)).sort()
    if (!del.length && !add.length) continue
    delTotal += del.length; addTotal += add.length
    lines.push(`${prov}/${route}: 删除 ${del.length} 新增 ${add.length}`)
    if (del.length) lines.push(`  删除: ${del.join(', ')}`)
    if (add.length) lines.push(`  新增: ${add.slice(0, 40).join(', ')}${add.length > 40 ? ` …(+${add.length - 40})` : ''}`)
  }
}
const summary = `pi-ai 目录 diff ${PKG} ${FROM} -> ${TO}：删除 ${delTotal} / 新增 ${addTotal}（提供方文件 ${allKeys.length}）`
console.log('==== ' + summary + ' ====')
if (lines.length) console.log(lines.join('\n'))
else console.log('（两版目录模型 id 集合一致）')
if (delTotal > 0) console.log('注意：删除模型若被 modelOverrides/显式列表引用，由上游 strict/deferred 校验保证「该模型缺席、其余路由存活」而非整包拒绝（dsh-llm-pi-ai 0.1.5 原生；原 pi-drift-F1 补丁已退役）。')
if (OUT) {
  mkdirSync(dirname(OUT), { recursive: true })
  writeFileSync(OUT, `# pi-ai 目录 diff（${FROM} -> ${TO}）\n\n${summary}\n\n${lines.join('\n') || '（无差异）'}\n`)
  console.log(`报告: ${OUT}`)
}
