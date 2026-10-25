// 断言指定的 LFS 路径已真正落地（而不是留在约 130 字节的 pointer 文本形态）。
//
// 为什么需要这个守卫（0.14.5，LFS 流量治理的配套）：
//   CI 里把 actions/checkout 的 lfs:true 换成「按需 git lfs pull --include=...」，
//   可避免 git lfs fetch --all 把历史里已删除的大对象一并拉入（实测本仓历史含 2 个共 294 MB
//   的 snapshots/* 死对象）。但按需拉取一旦失败，工作区留下的仍是 pointer 文本——它看起来是个
//   文件（existsSync 为真、能被 cp/tar 读到），实际只有约 130 字节。缺了这个守卫，失败会以
//   「tar 解压出错」之类的下游症状出现，而不是在源头判红。
//
// 用法：node scripts/require-lfs-materialized.mjs <path> [...]
//       node scripts/require-lfs-materialized.mjs --self-test
import { existsSync, readFileSync, statSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// LFS pointer 的规范首行；GitHub/Git LFS 客户端一律以它开头。
const POINTER_MAGIC = 'version https://git-lfs.github.com/spec/v1'
// pointer 文件实测约 130 字节；给足余量，但远小于任何真实底座归档。
const POINTER_SIZE_CEILING = 1024

export function checkMaterialized(path) {
  if (!existsSync(path)) return { ok: false, reason: '不存在', bytes: 0 }
  let st
  try { st = statSync(path) } catch (e) { return { ok: false, reason: 'stat 失败: ' + (e && e.message), bytes: 0 } }
  if (!st.isFile()) return { ok: false, reason: '不是普通文件', bytes: 0 }
  if (st.size <= POINTER_SIZE_CEILING) {
    let head = ''
    try { head = readFileSync(path, 'utf8') } catch { /* 读不出按 pointer 处理 */ }
    if (head.startsWith(POINTER_MAGIC)) {
      return { ok: false, reason: '仍是 LFS pointer（未落地；git lfs pull 未覆盖该路径或已失败）', bytes: st.size }
    }
    return { ok: false, reason: '体积过小（' + st.size + ' B），不像真实归档', bytes: st.size }
  }
  return { ok: true, reason: '', bytes: st.size }
}

function selfTest() {
  const d = mkdtempSync(join(tmpdir(), 'lfsguard-'))
  let checks = 0, failed = 0
  const ok = (cond, label) => { checks++; if (!cond) { failed++; console.error('  FAIL ' + label) } else console.log('  ok   ' + label) }

  const real = join(d, 'real.tar.xz')
  writeFileSync(real, Buffer.alloc(4096, 7))
  ok(checkMaterialized(real).ok, '真实归档判 ok')

  const ptr = join(d, 'pointer.tar.xz')
  writeFileSync(ptr, POINTER_MAGIC + String.fromCharCode(10) + 'oid sha256:deadbeef' + String.fromCharCode(10) + 'size 150648576' + String.fromCharCode(10))
  const r2 = checkMaterialized(ptr)
  ok(!r2.ok, 'LFS pointer 判红（未落地）')
  ok(r2.reason.includes('pointer'), '判红原因指明是 pointer 而非泛泛失败')

  ok(!checkMaterialized(join(d, 'nope.tar.xz')).ok, '缺席判红')

  const tiny = join(d, 'tiny.tar.xz')
  writeFileSync(tiny, 'abc')
  ok(!checkMaterialized(tiny).ok, '过小文件判红')

  ok(!checkMaterialized(d).ok, '目录判红')

  const big = join(d, 'big.tar.xz')
  writeFileSync(big, Buffer.concat([Buffer.from(POINTER_MAGIC), Buffer.alloc(4096, 1)]))
  ok(checkMaterialized(big).ok, '大文件即使含 pointer 字样也判 ok（阈值分流正确）')

  rmSync(d, { recursive: true, force: true })
  console.log('')
  console.log('REQUIRE-LFS-MATERIALIZED ' + (failed === 0 ? 'PASSED' : 'FAILED') + '（' + checks + ' 项检查，' + failed + ' 项失败）')
  process.exitCode = failed === 0 ? 0 : 1
}

const argv = process.argv.slice(2)
if (argv.includes('--self-test')) selfTest()
else if (argv.length === 0) {
  console.error('用法：node scripts/require-lfs-materialized.mjs <path> [...] | --self-test')
  process.exit(2)
} else {
  let bad = 0
  for (const p of argv) {
    const r = checkMaterialized(p)
    if (r.ok) console.log('OK   ' + p + '（' + r.bytes + ' B）')
    else { console.error('FAIL ' + p + ' -> ' + r.reason); bad++ }
  }
  if (bad) {
    console.error('')
    console.error('REQUIRE-LFS-MATERIALIZED FAILED（' + bad + ' 项未落地）——底座归档未取到，禁止继续构建。')
    console.error('后续 tar/快照装配会以难懂的形态失败，或产出体积异常的假产物。')
    console.error('修法：git lfs pull --include=<上方路径>（确认 git lfs install --local 已执行）')
    process.exit(1)
  }
  console.log('REQUIRE-LFS-MATERIALIZED PASSED（' + argv.length + ' 项均已落地）')
}