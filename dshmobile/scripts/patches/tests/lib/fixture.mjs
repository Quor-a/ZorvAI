// fixture.mjs — 补丁测试夹具的版本化取径（0.14.2 T6）。
//
// 为什么存在：夹具目录名里带引擎版本（dsh-client-modules-0.1.7-rc.1），版本单一真源取
// scripts/contract.json 的 baseline ⇒ 追版时「夹具没跟着换」立刻变成测试跑不起来（明确报错），
// 而不是拿上一代产物继续全绿。实锤见 check-patch-fixtures.mjs 头注。
//
// DSH_PATCH_FIXTURE=<ver> 可临时指向另一代夹具（回查历史漂移用），默认取 baseline。
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
export const FIXTURES_DIR = join(HERE, '..', 'fixtures')
export const ENGINE_BASELINE = JSON.parse(readFileSync(join(HERE, '..', '..', '..', 'contract.json'), 'utf8')).baseline
export const FIXTURE_ENGINE = process.env.DSH_PATCH_FIXTURE || ENGINE_BASELINE

/** 取夹具文件绝对路径；缺席时抛出可执行的下一步，而不是 ENOENT 堆栈。 */
export function fixturePath(dirName, ...parts) {
  const p = join(FIXTURES_DIR, dirName, ...parts)
  if (!existsSync(p)) {
    throw new Error(`夹具缺席: ${p}\n  重生成：node scripts/probe-engine-anchors.mjs --fixtures`
      + `（从构建期同一批 tgz 写出，与快照引擎树逐字节同源）`)
  }
  return p
}

/** 按当前引擎代取夹具：<pkgShort>-<engine> */
export function versionedFixture(pkgShort, ...parts) {
  return fixturePath(`${pkgShort}-${FIXTURE_ENGINE}`, ...parts)
}
