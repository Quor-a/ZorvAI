#!/usr/bin/env node
// 内置预设载体断言（来源审计链专用）。
//
// 与 scripts/check-engine-overlay.mjs 的 CARRIERS **同源**：那份是权威源，本文件是它在
// 「已解压的引擎树」上的等价实现（权威源按 tar 列目录计数，此处按目录读文件计数）。
// 漂移由 preset-carriers.test.mjs 双向复核——它会读权威源文本，逐条比对两侧的载体路径集合。
//
// 为什么是这两条载体（0.14.2 追版重锚，权威源注释同步）：0.1.7 起 `dsh-agent-presets`
// 被拆成 agent-preset + agent-preset-registry，预设载体形态随之改变：
// agent-preset 出 `skills/`（15 项），web-app 出 `presets/*.patch.yml`（4 项）。
// 只断「包在场」是冗余（overlay 已覆盖包版本），断「目录非空」才挡得住
// 「发布了包但 files 漏项」——那是上游发布回归，产品面表现为「没有可用预设」。
import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

/** 载体目录相对**引擎根**（`usr/lib/node_modules/@deepseek-ai/dsh`）的路径。 */
export const PRESET_CARRIERS = [
  { label: 'agent-preset skills/', path: 'node_modules/@deepseek-ai/dsh-agent-preset/skills' },
  { label: 'web-app presets/', path: 'node_modules/@deepseek-ai/dsh-web-app/presets' },
]

/** 递归数目录下的**文件**数（与权威源只对 `m.isfile()` 计数同口径；目录本身不计）。 */
export function countCarrierFiles(dir) {
  let count = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) count += countCarrierFiles(join(dir, entry.name))
    else if (entry.isFile()) count += 1
  }
  return count
}

/**
 * 逐个载体断言「目录在场且非空」。返回逐载体计数供 provenance 落盘。
 * 目录缺席与目录空都判红：前者是包没进快照，后者是包进了但内容没发布。
 */
export function checkPresetCarriers(engineRoot) {
  const carriers = PRESET_CARRIERS.map((carrier) => {
    const dir = join(engineRoot, carrier.path)
    return { label: carrier.label, path: carrier.path, fileCount: existsSync(dir) ? countCarrierFiles(dir) : 0 }
  })
  const empty = carriers.filter((carrier) => carrier.fileCount < 1)
  if (empty.length > 0) {
    throw new Error('source snapshot preset carriers are empty: '
      + empty.map((carrier) => `${carrier.label}(${carrier.path})`).join(', ')
      + '——包在但内容没发布 = 上游 files 漏项，产品面「没有可用预设」')
  }
  return carriers
}
