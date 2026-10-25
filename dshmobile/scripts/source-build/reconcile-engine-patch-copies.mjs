#!/usr/bin/env node
// 引擎补丁目标的**副本收敛**（来源审计链专用）。
//
// 为什么需要：来源链的引擎树是 pnpm 布局——同一个包在上层有**物化副本**、在
// `node_modules/.pnpm/**` 里还有**store 副本**。引擎树补丁按登记表的 `target`
// （顶层路径）写入，于是只改到一份；而运行时按依赖查找解析到的**可能是 store 那份**
// （设备实测：`node-addon-require-builtin` 经 `.pnpm/...@0.1.6/...` 解析 ⇒ 加载到未打
// 补丁的原始文件 ⇒ `createEntryApi()` 抛出 ⇒ 引擎 boot 硬崩；同批还有 `pi-toolcall-G2`
// 的 store 副本没打上，补丁在设备上等于没生效）。
//
// 判据与修法分离：**修法**是把已打补丁那份的字节写到其余副本（同包同相对路径）；
// **判据**在 check-dsh-source-snapshot.mjs ——「每个补丁目标不得存在未打补丁的副本」，
// 这条判据要是早就有，这个包根本发不出去。
//
// 用法：node reconcile-engine-patch-copies.mjs <engineRoot> [reportPath]
//   engineRoot 例：.deploy-tmp/snapshot-013/arm64/stage/root（其下 usr/lib/node_modules/...）
import { copyFileSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, posix, relative, resolve, sep } from 'node:path'
import { planPiStreaming } from '../patches/pi-upstream-streaming-020.mjs'
import { planPatch as planPtcAndroid } from '../patches/ptc-android-native-A1.mjs'
import { resolveEnginePatchTarget } from '../patches/resolve-engine-patch-target.mjs'

// 登记表里的 target 是**归档内路径**；本模块与调用方一律用「相对引擎根」的路径对话，
// 引擎根 = `usr/lib/node_modules/@deepseek-ai/dsh` 目录本身（与 check-dsh-source-snapshot.mjs 同约定）。
// 这个常量只用于把 target 解析成引擎根相对路径——**不要**再用它去拼实际根。
const TARGET_PREFIX = 'usr/lib/node_modules/@deepseek-ai/dsh/'

/** 收集引擎树下所有**物理**文件（readdirSync 的 Dirent 对 symlink 既非文件也非目录 ⇒ 天然不跟随）。 */
export function collectPhysicalFiles(root) {
  const out = []
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (entry.isFile()) out.push(full)
    }
  }
  walk(root)
  return out
}

/**
 * 按补丁目标给出「哪些文件算同一个目标」的匹配器。
 *  - 目标是引擎根包自身（不以 node_modules/ 开头）⇒ 只可能是那一个文件。
 *  - 目标是依赖包 ⇒ 顶层物化副本与任意 `.pnpm/**` store 副本都算（同包 + 同包内相对路径）。
 * 这条区分不是洁癖：`lib/bin.js` 这种短后缀若按「结尾相同」匹配，会把**别的包**的同名文件
 * 也算进来（实测踩过：`dsh-experimental-webworker-packer/lib/bin.js` 被误报为未打补丁副本）。
 */
export function matchesPatchTarget(engineRelPath, targetRel) {
  // 引擎根包自身：只可能是那一个文件（**必须精确相等**——这里曾用「结尾相同」匹配，
  // 把别的包的 `lib/bin.js` 误判成同一目标的副本）。
  if (!targetRel.startsWith('node_modules/')) return engineRelPath === targetRel
  // 依赖包：顶层物化副本，以及任何以 `node_modules/<pkg>/<inner>` 结尾的物理副本
  // （含 `.pnpm/**` 的 store 副本、以及可能的 peer 实体副本）。后缀里带包名，
  // 不会误匹配同名不同包（`pi-ai` 不会命中 `pi-ai-extra`）。
  return engineRelPath === targetRel || engineRelPath.endsWith(`/${targetRel}`)
}

/** Expand every physical target; secondary files cannot silently leave the source audit. */
export function enginePatchTargets(patch, engineRoot = undefined) {
  let target = patch.target
  if (patch.targetDiscovery) {
    if (!engineRoot) throw new Error('discovery requires actual engine root: ' + patch.id)
    const directory = patch.targetDiscovery.directory.slice(TARGET_PREFIX.length)
    const entries = readdirSync(join(engineRoot, directory), { withFileTypes: true })
      .filter(entry => entry.isFile() && entry.name.endsWith('.js'))
      .map(entry => { const path = patch.targetDiscovery.directory + '/' + entry.name;
        return [path, readFileSync(join(engineRoot, directory, entry.name), 'utf8')] })
    target = resolveEnginePatchTarget(patch, entries)
  }
  const paths = [target, ...(patch.additionalTargets ?? [])]
  if (paths.some(path => typeof path !== 'string' || !path.startsWith(TARGET_PREFIX)) || new Set(paths).size !== paths.length) {
    throw new Error('invalid/duplicate engine patch targets: ' + patch.id)
  }
  return paths.map((target, i) => ({ ...patch, target,
    marker: i === 0 ? patch.marker : patch.targetMarkers?.[target] ?? patch.marker }))
}

/** Exact reviewed verifiers supplement markers for unchanged upstream bytes and two-file PTC state. */
export function verifyCanonicalEnginePatch(engineRoot, patch) {
  if (patch.verifier === undefined) return
  let plan
  if (patch.id === 'pi-upstream-streaming-020' && patch.verifier === 'pi-upstream-streaming-020') {
    plan = planPiStreaming(join(engineRoot, 'node_modules/@earendil-works/pi-ai'))
  } else if (patch.id === 'ptc-android-native-A1' && patch.verifier === 'ptc-android-native-A1') {
    plan = planPtcAndroid(join(engineRoot, 'node_modules/@deepseek-ai/dsh-ptc-runtime-node'))
  } else throw new Error('unknown engine patch verifier: ' + patch.id)
  if (plan.some(file => file.before !== file.after)) throw new Error('incomplete canonical engine patch: ' + patch.id)
}

/** 对一个补丁目标：找出全部副本、必要时将已验证逻辑目标的完整字节写给其余副本。 */
function reconcileTarget(engineRoot, files, patch) {
  if (!patch.target.startsWith(TARGET_PREFIX)) throw new Error(`补丁目标不在引擎根内：${patch.id}（${patch.target}）`)
  const targetRel = patch.target.slice(TARGET_PREFIX.length)
  const marker = String(patch.marker ?? '').replace(/（.*$/, '').trim()
  const copies = files.filter((file) => matchesPatchTarget(relative(engineRoot, file).split(sep).join(posix.sep), targetRel))
  if (copies.length === 0) throw new Error(`补丁目标在引擎树里找不到：${patch.id}（${targetRel}）`)
  const canonicalFile = join(engineRoot, targetRel)
  const canonicalBytes = readFileSync(canonicalFile)
  if (!patch.verifier && (!marker || !canonicalBytes.toString('utf8').includes(marker))) {
    // 规范目标（targetRel 那份）是其余副本的**来源**，它自己缺 marker 就等于补丁根本没打上。
    // 措辞保留旧实现的判据短语（补丁完全没打上）：这是同一事实，且 reconcile 的回归用例按它断言。
    throw new Error(`补丁完全没打上（规范补丁目标缺 marker）：${patch.id}（${marker}）`)
  }
  const rows = copies.map((file) => ({
    file, path: relative(engineRoot, file).split(sep).join(posix.sep), size: statSync(file).size,
    patched: readFileSync(file).equals(canonicalBytes),
  }))
  const canonical = { file: canonicalFile, path: targetRel }
  const reconciled = []
  for (const row of rows) {
    if (row.patched) continue
    copyFileSync(canonical.file, row.file)
    reconciled.push({ path: row.path, sizeBefore: row.size, sizeAfter: statSync(row.file).size, copiedFrom: canonical.path })
  }
  return {
    id: patch.id,
    target: targetRel,
    marker,
    copies: rows.map(({ path, size, patched: ok }) => ({ path, size, patched: ok })),
    reconciled,
  }
}

/** 全量对账。返回报告；有副本被改写时也会体现在报告里。 */
export function reconcileEnginePatchCopies(engineRoot, registry, { files = null } = {}) {
  const patches = registry.patches.filter((patch) => patch.scope === 'engine' && patch.overlayCheck !== false)
  for (const patch of patches) verifyCanonicalEnginePatch(engineRoot, patch)
  const allFiles = files ?? collectPhysicalFiles(engineRoot)
  const targets = patches.flatMap(patch => enginePatchTargets(patch, engineRoot)).map((patch) => reconcileTarget(engineRoot, allFiles, patch))
  return {
    engineRoot: engineRoot.split(sep).join(posix.sep),
    patchCount: targets.length,
    reconciledCopyCount: targets.reduce((sum, item) => sum + item.reconciled.length, 0),
    targets,
  }
}

if (process.argv[1] && resolve(process.argv[1]).endsWith('reconcile-engine-patch-copies.mjs')) {
  const [engineRootArg, reportArg] = process.argv.slice(2)
  if (!engineRootArg) {
    console.error('usage: node reconcile-engine-patch-copies.mjs <engineRoot> [reportPath]')
    process.exit(2)
  }
  const engineRoot = resolve(engineRootArg)
  const registry = JSON.parse(readFileSync('scripts/patches/registry.json', 'utf8'))
  const report = reconcileEnginePatchCopies(engineRoot, registry)
  if (reportArg) {
    writeFileSync(resolve(reportArg), `${JSON.stringify(report, null, 2)}\n`)
  }
  for (const target of report.targets) {
    const detail = target.reconciled.length
      ? `收敛 ${target.reconciled.length} 份副本（${target.reconciled.map((r) => r.path).join(', ')}）`
      : `${target.copies.length} 份副本均已一致`
    console.log(`${target.id}: ${detail}`)
  }
  console.log(`引擎补丁副本对账完成：${report.patchCount} 个目标，改写 ${report.reconciledCopyCount} 份副本`)
}
