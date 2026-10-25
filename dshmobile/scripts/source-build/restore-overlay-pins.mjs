#!/usr/bin/env node
// 把来源链刻意摘除的第一方 overlay 钉还原回 engine-overlay.json（APK 步骤专用，退出时由
// workflow 的 trap 用 engine-overlay.original.json 覆盖回去）。
//
// **为什么要摘**：来源链的第一方包来自固定源码构建 + 注入；若 overlay 里仍留着它们的
// (name, version)，快照构建器会按登记表去 npm 镜像是拉**上游发布版 tarball** 整目录覆盖
// 已注入的源码产物（build-snapshot-013.mjs 的 overlayExtract 是整目录替换）——源码审计
// 随之失去意义。故构建期摘除，摘掉的清单记进 source-build-policy.json。
//
// **为什么要还原**：APK 步骤里 scripts/check-contract.mjs §7 就按这份清单判：
//   - `overlay.packages['@deepseek-ai/dsh-app-boot']` 定「设备上跑的运行时版本」；
//   - profile patch 里 `@deepseek-ai/*` 的 insert 行按「与运行时同版」判是否会被静默禁用。
// 来源链此前没暴露这条是因为该门禁在拿不到 semver 时 SKIP（见门禁自身的 SKIP 文案）；
// 源码产物树现在能提供 semver，门禁随即真判并要求清单完整（坑 201）。
//
// 还原的是**事实**不是补丁：这些包确实以这些版本进入运行时，且
// check-dsh-source-snapshot.mjs 已按原始 overlay 逐包核验过版本与 tarball 哈希。
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 还原冲突（同名不同版）——登记表内部不自洽时必须判红，不得静默择一。 */
export class OverlayPinConflictError extends Error {}

/**
 * 把 omitted（`[{ name, version }]`）并回 overlay.packages。返回新对象，不改动入参。
 * omitted 为空即判红：那是「摘除清单没被记录下来」的形态，静默放行会让门禁在缺钉状态下跑。
 */
export function mergeFirstPartyPins(overlay, omitted) {
  if (!Array.isArray(omitted) || omitted.length === 0) {
    throw new Error('source-build-policy.json 未记录被摘除的第一方 overlay 钉（omittedFirstPartyPackageOverlays）')
  }
  const packages = { ...(overlay.packages ?? {}) }
  for (const entry of omitted) {
    const name = entry?.name
    const version = entry?.version
    if (typeof name !== 'string' || name.length === 0 || typeof version !== 'string' || version.length === 0) {
      throw new Error(`被摘除的 overlay 钉形态不合法: ${JSON.stringify(entry)}`)
    }
    const existing = packages[name]
    if (existing !== undefined && existing !== version) {
      throw new OverlayPinConflictError(`第一方 overlay 钉冲突: ${name} 既有 ${existing}，摘除清单说 ${version}`)
    }
    packages[name] = version
  }
  return { ...overlay, packages }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [overlayArg, policyArg] = process.argv.slice(2)
  if (!overlayArg || !policyArg) {
    console.error('usage: node restore-overlay-pins.mjs <engine-overlay.json> <source-build-policy.json>')
    process.exit(2)
  }
  const overlayPath = resolve(overlayArg)
  const policyPath = resolve(policyArg)
  const policy = JSON.parse(readFileSync(policyPath, 'utf8'))
  const omitted = policy.omittedFirstPartyPackageOverlays
  const overlay = JSON.parse(readFileSync(overlayPath, 'utf8'))
  const restored = mergeFirstPartyPins(overlay, omitted)
  writeFileSync(overlayPath, JSON.stringify(restored, null, 2) + '\n')
  policy.contractOverlayRestoration = {
    file: overlayArg.replaceAll('\\', '/'),
    packages: omitted.length,
    packagesRestored: omitted.map(({ name, version }) => `${name}@${version}`),
    source: 'source-build-policy.json 的 omittedFirstPartyPackageOverlays（构建期摘除时记录）',
    reason: 'check-contract.mjs §7 按这份清单定运行时版本并对 profile 的 @deepseek-ai/* insert 行判同版；'
      + '摘除只是为了让快照构建器不可按登记表回拉上游发布版 tarball 覆盖已注入的源码产物，'
      + '这些包确实以这些版本进入运行时，且已由 check-dsh-source-snapshot.mjs 按原始 overlay 逐包核验。'
      + 'workflow 的退出 trap 会用 engine-overlay.original.json 覆盖回本文件。',
  }
  writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n')
  console.log(`restored ${omitted.length} first-party overlay pins into ${overlayArg.replaceAll('\\', '/')}`)
}
