#!/usr/bin/env node
// Apply the registered marketplace patches to source-built output while keeping
// the shared patch runner byte-identical to the coordination repository.
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { spawnSync } from 'node:child_process'

const args = process.argv.slice(2)
if (args.length < 1) {
  console.error('usage: node apply-source-marketplace-patches.mjs <vendorRoot> [patch arguments]')
  process.exit(2)
}

const sourcePath = resolve('scripts/patches/apply-patches.mjs')
const source = readFileSync(sourcePath, 'utf8')
// Anchors are matched against LF-normalised text: a Windows checkout can carry
// CRLF, or a mix of both endings after a partial conversion, which would fail
// the anchor assertion for a reason unrelated to the shared runner's content.
// The generated runner is a temporary file, so its endings are irrelevant, and
// the hashes below still cover the shared runner's real bytes.
const normalized = source.replaceAll('\r\n', '\n')
const sha256 = (value) => createHash('sha256').update(value).digest('hex')
const adapterSource = readFileSync(fileURLToPath(import.meta.url))
const hereLine = 'const HERE = dirname(fileURLToPath(import.meta.url))'
// 本适配器做两处改写，目的只有一个：让落盘在 .deploy-tmp 的生成副本仍能在 scripts/patches
// 的语境下运行。两处都只动「定位」，不动任何补丁语义。
//
// ① HERE 指回 scripts/patches——生成文件按 import.meta.url 推路径会指向错误目录。
// ② 相对 import 说明符改写成指向 scripts/patches 的绝对 file URL——静态 ESM 说明符按
//    **文件自身位置**解析，不认 HERE。上游 0.2.0-rc.2 起共享执行器静态引入三个同伴模块
//    （ptc-android-native-A1 / pi-upstream-streaming-020 / resolve-engine-patch-target），
//    不改写则生成副本在 import 期就 ERR_MODULE_NOT_FOUND（实锤 2026-10-02：本地预检与
//    工作流 505 行同点判红，来源审计链自 0.1.7-rc.2 之后没人跑过，故未被发现）。
//    同伴模块各自用 import.meta.url 推导仓库根与 upstream/ 数据目录（pi-ai-0.87.1.patch），
//    所以**不能**把它们复制到生成目录旁边——只能把说明符指回它们的真实位置。
//
// 0.1.7 之前这里还有第三处改写：market-A 的市场补丁在源码构建产物上要接受另一种
// `requireApproval` 闭合形态。上游 0.1.7 自修了那个 waterfall 崩溃、market-A 退役
// （registry.json 的 retired 段），锚点随之从共享执行器消失——本改写已成死代码，删除。
// 锚点整体失配不需要本适配器兜底：共享执行器对「check 为假且 apply 零改动」本来就判红。
if (normalized.split(hereLine).length !== 2) {
  throw new Error('shared patch runner changed; review the source-build marketplace adapter before updating it')
}
const patchesDir = resolve('scripts', 'patches')
const relativeSpecifier = /(\bfrom\s*)(['"])(\.\.?\/[^'"]*)\2/g
const adapted = normalized
  .replace(hereLine, "const HERE = join(process.cwd(), 'scripts', 'patches')")
  .replace(relativeSpecifier, (_match, head, quote, specifier) =>
    head + quote + pathToFileURL(join(patchesDir, specifier)).href + quote)

const reportRoot = resolve('.deploy-tmp/source-build')
mkdirSync(reportRoot, { recursive: true })
const generatedPath = join(reportRoot, 'apply-patches-source.mjs')
writeFileSync(generatedPath, adapted)
const result = spawnSync(process.execPath, [generatedPath, ...args], { cwd: process.cwd(), stdio: 'inherit' })
if (result.error) throw result.error
if (result.status !== 0) process.exit(result.status ?? 1)

const target = resolve(args[0], 'dshmarketplace-plugin/lib/index.js')
const report = {
  sharedPatchRunner: 'scripts/patches/apply-patches.mjs',
  sharedPatchRunnerSha256: sha256(source),
  generatedPatchRunner: '.deploy-tmp/source-build/apply-patches-source.mjs',
  generatedPatchRunnerSha256: sha256(adapted),
  sourceAdapter: 'scripts/source-build/apply-source-marketplace-patches.mjs',
  sourceAdapterSha256: sha256(adapterSource),
  registrySha256: sha256(readFileSync('scripts/patches/registry.json')),
  marketplacePatchOutput: args[0] + '/dshmarketplace-plugin/lib/index.js',
  marketplacePatchOutputSha256: sha256(readFileSync(target)),
  arguments: args,
  sourceBuildOnlyRule: "The generated copy differs from the shared runner only in how it locates its own inputs: its HERE anchor and every relative import specifier are re-pointed at scripts/patches. Neither the registry patches nor any patch body is rewritten. The market-A A-3 closure rewrite was removed when upstream 0.1.7 retired that patch.",
}
writeFileSync(join(reportRoot, 'marketplace-patch-adapter.json'), JSON.stringify(report, null, 2) + '\n')
console.log('source-built marketplace patches applied with a generated, source-only runner adapter')
