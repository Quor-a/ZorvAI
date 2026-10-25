// patch-reload-startup-n1.test.mjs — N1 撤销不变量（0.14.2）。
//
// 这里**不再**测 N1 补丁本身：0.1.7-rc.1 上游把 patchReload 整个机制删了（全仓 `patchReload` 零命中），
// live reload 的收益改由结构提供——`dsh-client-hmr` 常驻但无 dev watcher 时空转
// （上游 packages/bundle/web-app/cordis.patch.yml 自述）。补丁与 P-AC-23/24 随之撤销。
//
// 撤销最容易复发的两种失败，都由本测试把守：
//   ① 有人把补丁加回来（registry 与 IMPLS 又出现 N1）；
//   ② 构建链又往出厂 profile 清单写这个死键（写了没人读，门禁还会去证明「我们写了一个死键」）。
// 判据取的是**真产物**（随版夹具），不是注释里的说法。
//
// 用法：node scripts/patches/tests/patch-reload-startup-n1.test.mjs
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

const registry = JSON.parse(readFileSync(join(here, '..', 'registry.json'), 'utf8'))
check('registry 里没有 perf-patch-reload-N1', !registry.patches.some((p) => p.id === 'perf-patch-reload-N1'))

const impl = readFileSync(join(here, '..', 'apply-patches.mjs'), 'utf8')
check('apply-patches 里没有 N1 实现', !impl.includes("'perf-patch-reload-N1'"))
check('apply-patches 里不再有 patchReload 锚点（撤销而非重锚）', !impl.includes('patchReload'))

// 真产物面：rc.1 的 dsh-app-boot 必须完全不认识这个键——它一旦被上游悄悄恢复，上面的「已撤销」
// 断言就该翻红重开这条链，而不是让补丁留在冷板凳上。
const boot = readFileSync(versionedFixture('dsh-app-boot', 'lib', 'index.js'), 'utf8')
check('rc.1 dsh-app-boot 无 patchReload（撤销前提仍成立）', !boot.includes('patchReload'),
  '上游又引入该键 => 重开 A1 评估，别直接恢复补丁')

const builder = readFileSync(join(repoRoot, 'scripts', 'build-snapshot-013.mjs'), 'utf8')
// 只判可执行行：撤销说明本身就要提这个键名，判全文等于自己给自己埋假红。
const builderCode = builder.split('\n').filter((l) => !l.trim().startsWith('//')).join('\n')
check('build-snapshot 不写 patchReload', !/\.patchReload\s*=[^=]/.test(builderCode) && !builderCode.includes('seedProfilePatchReload'))
const seed = readFileSync(join(repoRoot, 'scripts', 'lib', 'profile-seed.mjs'), 'utf8')
check('profile-seed 改为剥死键（DEAD_PROFILE_KEYS 含 patchReload）',
  /DEAD_PROFILE_KEYS\s*=\s*\[[^\]]*'patchReload'/.test(seed))

if (failures.length) {
  console.error(`patch-reload-startup-n1: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('patch-reload-startup-n1: 撤销不变量全部成立')
