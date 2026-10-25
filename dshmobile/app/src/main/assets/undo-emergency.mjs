// dsh-undo-emergency.mjs — dsh-undo-savepoint 安卓急救命令行（PRD D6 方案 a）
// 与引擎平级：运行在 Termux 运行时（引擎同款 node）内，即使 DSH 完全起不来也可执行
// 等价撤销/安全模式/回退。快照格式与 dsh-undo-savepoint 插件/工具完全一致
// （manifest.json + files[] + content-addressed blobs + safe-mode 备份状态）。
//
// 用法：
//   node dsh-undo-emergency.mjs list                       列出全部快照
//   node dsh-undo-emergency.mjs restore <id|latest>         恢复到指定快照
//   node dsh-undo-emergency.mjs restore-last-good           恢复 crash 归因得出的最后良好快照
//   node dsh-undo-emergency.mjs undo                        撤销上一次自动快照（等价 restore auto-latest）
//   node dsh-undo-emergency.mjs safe-mode on|off|status     安全模式：on=摘除第三方条目、保留产品自有插件
//   node dsh-undo-emergency.mjs boot-state                  显示插件崩溃归因状态（crashed/lastGoodAt/crashReason）
//
// 环境变量：DSH_HOME（默认 ~/.dsh）｜DSH_UNDO_ROOT（默认 $DSH_HOME/undo-snapshots）
//          DSH_UNDO_PROFILE（默认 web；scoped 存储 <root>/<profile>/ 优先，兼容 flat 旧库）
// 安全边界：本工具只写配置文件与插件代码树（同快照范围），不触碰用户数据目录
// （sessions/storages/凭据真实值）；敏感文件快照为脱敏副本，真实值在本机 vault 中，
// 恢复时优先从 vault 取真实值（与插件 applySnapshot 语义一致），vault 缺失才写占位。
import { readdirSync, readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync, rmSync, cpSync, renameSync, realpathSync, lstatSync } from 'node:fs'
import { join, basename, dirname, sep, resolve } from 'node:path'
import { homedir } from 'node:os'
import { createHash, randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const DSH_HOME = process.env.DSH_HOME || join(homedir(), '.dsh')
const UNDO_ROOT = process.env.DSH_UNDO_ROOT || join(DSH_HOME, 'undo-snapshots')
const PROFILE = process.env.DSH_UNDO_PROFILE || 'web'
const PROFILE_ROOT = join(DSH_HOME, 'profiles', PROFILE)

function storeDirs() {
  const scoped = join(UNDO_ROOT, PROFILE)
  const scopedExists = existsSync(join(scoped, 'manual')) || existsSync(join(scoped, 'auto'))
  const flat = existsSync(join(UNDO_ROOT, 'manual')) || existsSync(join(UNDO_ROOT, 'auto'))
  if (scopedExists && !flat) return { root: scoped, blobs: join(scoped, 'blobs') }
  // 两种都存在时 scoped 优先（新布局），blobs 在各自根下
  if (scopedExists || !flat) return { root: scoped, blobs: join(scoped, 'blobs'), scoped: true }
  return { root: UNDO_ROOT, blobs: join(UNDO_ROOT, 'blobs'), scoped: false }
}

function listSnapshots() {
  const { root } = storeDirs()
  const out = []
  for (const kind of ['auto', 'manual']) {
    const dir = join(root, kind)
    if (!existsSync(dir)) continue
    for (const id of readdirSync(dir)) {
      const mf = join(dir, id, 'manifest.json')
      if (!existsSync(mf)) continue
      try {
        const m = JSON.parse(readFileSync(mf, 'utf8'))
        out.push({ id, kind, time: m.time, reason: m.reason ?? '', files: (m.files ?? []).length, plugins: (m.plugins ?? []).length })
      } catch (e) {
        out.push({ id, kind, error: 'manifest 读取失败' })
      }
    }
  }
  return out.sort((a, b) => (a.id < b.id ? 1 : -1))
}

function destToTarget(name) {
  // name: 'profile-cordis.patch.yml' / 'home-settings.yaml'（路径分隔符已被 - 化）
  if (name.startsWith('profile-')) {
    const rel = name.slice('profile-'.length)
    return join(PROFILE_ROOT, rel)
  }
  if (name.startsWith('home-')) {
    return join(DSH_HOME, name.slice('home-'.length))
  }
  return null
}

/** 读取插件崩溃归因状态（auto/boot-state.json），读不到返回 null。 */
function readBootState() {
  const { root } = storeDirs()
  const p = join(root, 'auto', 'boot-state.json')
  if (!existsSync(p)) return null
  try { return JSON.parse(readFileSync(p, 'utf8')) } catch { return null }
}

/**
 * 最后良好快照（对齐插件 lastGoodSnapshot，v0.3 模块 3 语义）：
 * 时间不晚于 lastGoodAt 的最新非 pre-restore 快照。
 * boot-state.json 缺失时直接取最新 auto（退化语义，等价旧版 undo）。
 */
function lastGoodSnapshot() {
  const boots = readBootState()
  const at = boots?.lastGoodAt
  const t = typeof at === 'string' ? Date.parse(at) : NaN
  const snaps = listSnapshots().filter((s) => s.kind === 'auto' && !s.error)
  if (Number.isNaN(t)) return { snap: snaps[0] ?? null, source: 'fallback-latest' }
  const good = snaps.find((s) => (s.time ? Date.parse(s.time) : NaN) <= t)
  return { snap: good ?? null, source: 'lastGoodAt' }
}

/** vault 真实值查表：<autoDir>/env-vault/<sha1>.env（与插件 readVault 同布局）。 */
function readVaultFile(sha1) {
  const { root } = storeDirs()
  const p = join(root, 'auto', 'env-vault', `${sha1}.env`)
  if (!existsSync(p)) return null
  try { return readFileSync(p) } catch { return null }
}

/** 从快照恢复：配置文件 + 插件代码树（blob 解引用，跨机安全：dir 取 node_modules 后相对段） */
function restore(idOrLatest, { pretend = false } = {}) {
  const { root, blobs } = storeDirs()
  const snaps = listSnapshots()
  const target = idOrLatest === 'latest'
    ? snaps.find((s) => s.kind === 'auto')
    : snaps.find((s) => s.id === idOrLatest)
  if (!target) {
    console.log(`未找到快照 '${idOrLatest}'（可用：${snaps[0]?.id ?? '无'}）`)
    return false
  }
  const snapDir = join(root, target.kind, target.id)
  const m = JSON.parse(readFileSync(join(snapDir, 'manifest.json'), 'utf8'))
  console.log(`恢复快照 ${target.id}（${target.kind}，${m.reason ?? '无原因'}）`)
  let restored = 0
  for (const f of m.files ?? []) {
    const dest = destToTarget(f.name)
    if (!dest) { console.log(`  跳过（未知目标）${f.name}`); continue }
    const src = join(snapDir, f.name)
    if (!existsSync(src)) { console.log(`  跳过（快照缺文件）${f.name}`); continue }
    // 敏感文件：vault 有真实值 → 完整还原（本机）；缺 → 写脱敏占位并提示
    let buf = readFileSync(src)
    let sensitiveNote = ''
    if (/^home-\.env$|^profile-\.env$|^home-\.credentials\.yaml$/.test(f.name)) {
      const ref = m.envVaultRefs?.[f.name]
      if (typeof ref === 'string' && ref !== '') {
        const real = readVaultFile(ref)
        if (real !== null) buf = real
        else sensitiveNote = `  ⚠️ vault 缺失：${f.name} 写入脱敏占位（请手动补真实值）`
      } else if (m.sensitiveMode === 'redact') {
        sensitiveNote = `  ⚠️ 快照本身脱敏：${f.name} 写入脱敏占位（请手动补真实值）`
      }
    }
    if (!pretend) {
      mkdirSync(dirname(dest), { recursive: true })
      writeFileSync(dest, buf)
    }
    console.log(`  ${f.name} -> ${dest}${sensitiveNote}`)
    restored++
  }
  for (const p of m.plugins ?? []) {
    const nmIdx = p.dir?.split(sep).lastIndexOf('node_modules')
    const relNm = nmIdx >= 0 ? p.dir.split(sep).slice(nmIdx + 1).join(sep) : p.name
    const pkgDir = join(PROFILE_ROOT, 'node_modules', relNm)
    for (const f of p.files ?? []) {
      const blob = join(blobs, f.hash)
      if (!existsSync(blob)) { console.log(`  blob 缺失 ${f.hash.slice(0, 8)}（${p.name} ${f.path}）`); continue }
      const dest = join(pkgDir, f.path)
      if (!pretend) {
        mkdirSync(dirname(dest), { recursive: true })
        copyFileSync(blob, dest)
      }
      console.log(`  [插件] ${p.name} ${f.path} -> ${dest}`)
      restored++
    }
  }
  for (const f of m.profileFiles ?? []) {
    const blob = join(blobs, f.hash)
    if (!existsSync(blob)) continue
    const rel = f.path.startsWith('./') ? f.path.slice(2) : f.path
    const dest = join(PROFILE_ROOT, rel)
    if (!pretend) { mkdirSync(dirname(dest), { recursive: true }); copyFileSync(blob, dest) }
    console.log(`  [profile] ${rel} -> ${dest}`)
    restored++
  }
  console.log(`完成：还原 ${restored} 项。重启 DSH（壳应用重启引擎）后生效。`)
  return true
}

/** restore-last-good：boot-state.json 归因 → 最后良好快照 → 恢复。 */
function restoreLastGood({ pretend = false } = {}) {
  const { snap, source } = lastGoodSnapshot()
  boots = readBootState()
  if (!snap) {
    console.log('无可用最后良好快照（boot-state=' + JSON.stringify(boots ?? {}) + '）')
    return false
  }
  console.log(`目标快照来源：${source}${source === 'lastGoodAt' ? `（lastGoodAt=${boots?.lastGoodAt}）` : ''}`)
  return restore(snap.id, { pretend })
}

function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex') }

function safeModeAtomicWrite(target, bytes) {
  mkdirSync(dirname(target), { recursive: true })
  const tmp = `${target}.safe-mode-${randomBytes(6).toString('hex')}.tmp`
  try {
    writeFileSync(tmp, bytes)
    renameSync(tmp, target)
  } finally {
    try { rmSync(tmp, { force: true }) } catch {}
  }
}

function safeModeBackup(autoDir, value, id, prefix) {
  if (typeof value !== 'string' || value === '') return null
  try {
    const root = realpathSync(autoDir)
    const candidate = resolve(value)
    const expectedName = `${prefix}${id}.yml`
    if (basename(candidate) !== expectedName || realpathSync(dirname(candidate)) !== root) return null
    // Permit a symlink alias for autoDir's parent; never follow a symlink backup.
    const info = lstatSync(candidate)
    if (!info.isFile() || info.isSymbolicLink()) return null
    const canonical = join(root, expectedName)
    if (realpathSync(candidate) !== canonical) return null
    return canonical
  } catch { return null }
}

function safeModeVerifyBackup(path, expectedSha) {
  try {
    if (!path || !existsSync(path)) return false
    const bytes = readFileSync(path)
    return typeof expectedSha !== 'string' || expectedSha === '' || sha256(bytes) === expectedSha
  } catch { return false }
}

export function safeMode(action, { atomicWrite = safeModeAtomicWrite } = {}) {
  const { root } = storeDirs()
  const autoDir = join(root, 'auto')
  // 与插件一致的状态文件名（v0.3 插件用 safe-mode.json；旧急救 CLI 误用
  // safe-mode-state.json 造成两侧状态互相不可见）
  const stateFile = join(autoDir, 'safe-mode.json')
  const patch = join(PROFILE_ROOT, 'cordis.patch.yml')
  const homePatch = join(DSH_HOME, 'cordis.patch.yml')
  const id = new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14) + '-' + Math.random().toString(16).slice(2, 6)
  if (action === 'on') {
    mkdirSync(autoDir, { recursive: true })
    // 兼容旧急救 CLI 写的 safe-mode-state.json：迁移后仍按 active 状态幂等处理。
    const legacy = join(autoDir, 'safe-mode-state.json')
    if (existsSync(legacy) && !existsSync(stateFile)) {
      try { copyFileSync(legacy, stateFile) } catch { /* 保留旧 marker 并拒绝下方写入 */ }
    }
    if (existsSync(stateFile)) {
      try {
        const prev = JSON.parse(readFileSync(stateFile, 'utf8'))
        if (prev?.active) { console.log('安全模式已在开启状态'); return true }
      } catch {
        console.error('安全模式状态文件损坏，已拒绝覆盖；请先恢复状态文件')
        return false
      }
    }
    let hardEntries
    try {
      const filesRoot = dirname(dirname(DSH_HOME))
      const manifest = JSON.parse(readFileSync(join(filesRoot, '.plugin-hard-manifest.json'), 'utf8'))
      const installedFingerprint = readFileSync(join(filesRoot, '.snapshot-fingerprint'), 'utf8').trim()
      if (manifest.schema !== 1 || manifest.complete !== true || manifest.fingerprint !== installedFingerprint ||
        !Array.isArray(manifest.entries) || manifest.entries.length === 0 || manifest.entries.some((entry) =>
          !entry || typeof entry.id !== 'string' || entry.id.length === 0 || typeof entry.name !== 'string' || entry.name.length === 0)) throw new Error('invalid identities')
      hardEntries = manifest.entries
    } catch {
      console.error('安全模式未生效：本版本插件归属清单缺失或损坏，无法安全区分产品插件与用户插件；原配置未改动。')
      return false
    }
    const backup = join(autoDir, `safe-mode-backup-${id}.yml`)
    const homeBackup = join(autoDir, `safe-mode-home-backup-${id}.yml`)
    // 先做变更前建档（等价 Windows 版 pre-snapshot）。快照失败时原配置仍未动。
    createManifestSnapshot('safe-mode-before')
    const patchBytes = existsSync(patch) ? readFileSync(patch) : Buffer.from('[]\n')
    atomicWrite(backup, patchBytes)
    const homeExisted = existsSync(homePatch)
    const homeBytes = homeExisted ? readFileSync(homePatch) : null
    if (homeBytes) atomicWrite(homeBackup, homeBytes)
    if (!safeModeVerifyBackup(backup, sha256(patchBytes)) ||
      (homeBytes && !safeModeVerifyBackup(homeBackup, sha256(homeBytes)))) {
      console.log('安全模式备份校验失败，拒绝进入；原配置未改动')
      return false
    }
    // 与插件核心（core.mjs 的 undo-safe-align-S1）同口径：只摘第三方 insert 子条目，
    // 保留我方装配的插件与全部顶层 disable 行。原实现整份覆写成最小文件，
    // 会摘掉 12 个 @dsh-android/* 引用与 7 条 disabled（含安全关键的 client-hmr）。
    // Publisher namespace is not product ownership: user-mounted official DSH packages are Soft.
    const filterThirdPartyInserts = (text, ownershipEntries) => {
      const lines = String(text).split('\n')
      const out = []
      let i = 0
      while (i < lines.length) {
        if (!/^- insert:\s*$/.test(lines[i])) { out.push(lines[i]); i += 1; continue }
        let end = i + 1
        while (end < lines.length && !/^-/.test(lines[end])) end += 1
        const body = lines.slice(i + 1, end)
        const firstItem = body.find((l) => /^(\s*)-\s+(id|name):/.test(l))
        const itemIndent = firstItem ? firstItem.length - firstItem.replace(/^\s+/, '').length : null
        if (itemIndent === null) { out.push(lines[i]); out.push(...body); i = end; continue }
        const chunks = []
        let cur = null
        for (const line of body) {
          const m = /^(\s*)-\s+/.exec(line)
          if (m && m[1].length === itemIndent) { if (cur) chunks.push(cur); cur = [line] }
          else if (cur) cur.push(line)
        }
        if (cur) chunks.push(cur)
        const kept = []
        for (const chunk of chunks) {
          const nm = /^\s*-?\s*name:\s*['"]?([^'"\s]+)/m.exec(chunk.join('\n'))
          const id = /^\s*-\s+id:\s*['"]?([^'"\s]+)/m.exec(chunk[0])?.[1] ?? null
          if (nm && !ownershipEntries.some((entry) => entry.id === id && entry.name === nm[1])) continue
          kept.push(...chunk)
        }
        if (kept.length === 0) { i = end; continue }
        out.push(lines[i]); out.push(...kept); i = end
      }
      return out.join('\n')
    }
    const minimal = filterThirdPartyInserts(patchBytes.toString('utf8'), hardEntries)
    // Marker is the recovery authority. Persist it atomically before touching any live config.
    const state = {
      active: true, enteredAt: new Date().toISOString(), backup, homeBackup,
      snapshotId: id, homeExisted,
      backupSha256: sha256(patchBytes), homeBackupSha256: homeBytes ? sha256(homeBytes) : '',
    }
    try {
      atomicWrite(stateFile, Buffer.from(JSON.stringify(state, null, 2)))
      mkdirSync(PROFILE_ROOT, { recursive: true })
      atomicWrite(patch, Buffer.from(minimal))
      if (homeExisted) atomicWrite(homePatch, Buffer.from('# dsh-undo-savepoint SAFE MODE (home level)\n[]\n'))
    } catch (error) {
      console.error(`安全模式写入未完成；状态与备份已保留，可修复原因后重试 off：${error.message}`)
      return false
    }
    rmSync(legacy, { force: true })
    console.log(`安全模式 ON（建档 ${id}）。已摘除第三方插件条目、保留产品自有插件；重启 DSH 生效。`)
    return true
  }
  if (action === 'off') {
    if (!existsSync(stateFile)) { console.log('安全模式未开启'); return true }
    let st
    try { st = JSON.parse(readFileSync(stateFile, 'utf8')) } catch {
      console.error('安全模式状态文件损坏；保留状态与备份，拒绝还原')
      return false
    }
    const backupPath = safeModeBackup(autoDir, st.backup, st.snapshotId, 'safe-mode-backup-')
    const homeBackupPath = st.homeExisted
      ? safeModeBackup(autoDir, st.homeBackup, st.snapshotId, 'safe-mode-home-backup-') : null
    if (!st.active || !safeModeVerifyBackup(backupPath, st.backupSha256) ||
      (st.homeExisted && !safeModeVerifyBackup(homeBackupPath, st.homeBackupSha256))) {
      console.error('安全模式备份缺失、损坏或路径无效；保留状态与文件，拒绝还原')
      return false
    }
    try {
      atomicWrite(patch, readFileSync(backupPath))
      if (st.homeExisted) atomicWrite(homePatch, readFileSync(homeBackupPath))
    } catch (error) {
      console.error(`安全模式还原未完成；状态与备份已保留，可修复原因后重试：${error.message}`)
      return false
    }
    rmSync(stateFile, { force: true })
    console.log('安全模式 OFF：已还原 patch（重启 DSH 恢复完整插件）')
    return true
  }
  if (action === 'status') {
    if (existsSync(stateFile)) {
      const st = JSON.parse(readFileSync(stateFile, 'utf8'))
      console.log(`安全模式：开启中（进入于 ${st.enteredAt}）`)
    } else {
      console.log('安全模式：未开启')
    }
    return true
  }
  console.log('safe-mode 用法：node dsh-undo-emergency.mjs safe-mode on|off|status')
  return false
}

function createManifestSnapshot(reason) {
  // 与插件一致的最小建档：profile/home 配置文件的现价拷贝（不建插件树 blob，避免重复实现；
  // 完整快照由插件在 DSH 可启动时生成；本工具保住"回退入口"而非"全量备份"）。
  const { root } = storeDirs()
  const dir = join(root, 'manual', 'emg-' + new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14) + '-0000')
  mkdirSync(dir, { recursive: true })
  const files = []
  const spec = [
    ['profile', 'cordis.patch.yml'], ['profile', 'package.json'], ['profile', 'cordis.yml'],
    ['profile', 'pnpm-workspace.yaml'], ['profile', 'pnpm-lock.yaml'],
    ['home', 'cordis.patch.yml'], ['home', 'settings.yaml'],
  ]
  for (const [rootKey, rel] of spec) {
    const src = rootKey === 'profile' ? join(PROFILE_ROOT, rel) : join(DSH_HOME, rel)
    if (!existsSync(src)) continue
    const destName = `${rootKey}-${rel.replaceAll('/', '-')}`
    copyFileSync(src, join(dir, destName))
    files.push({ name: destName, size: 0 })
  }
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify({ id: basename(dir), time: new Date().toISOString(), kind: 'manual', reason, files, plugins: [], profileFiles: [], sensitiveMode: 'redact', redacted: [], envVaultRefs: {} }, null, 2))
  return basename(dir)
}

let boots = null
if (!new URL(import.meta.url).search && process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [cmd, arg] = process.argv.slice(2)
  if (!cmd) {
    console.log(`用法：node dsh-undo-emergency.mjs <list|restore <id|latest>|restore-last-good|undo|safe-mode on|off|status|boot-state>
DSH_HOME=${DSH_HOME} / 存储=${UNDO_ROOT} / 档案=${PROFILE}`)
    process.exit(1)
  }
  let ok = false
  switch (cmd) {
  case 'list': {
    const snaps = listSnapshots()
    if (!snaps.length) console.log('暂无快照。')
    for (const s of snaps) {
      console.log(`${s.id}  [${s.kind}]  ${s.time ?? ''}  ${s.reason ?? ''}${s.error ? '（' + s.error + '）' : ''}  文件${s.files} 插件${s.plugins}`)
    }
    ok = true
    break
  }
  case 'restore': ok = restore(arg || 'latest'); break
  case 'restore-last-good': ok = restoreLastGood(); break
  case 'undo': ok = restore('latest'); break
  case 'boot-state': {
    const s = readBootState()
    console.log(s ? JSON.stringify(s, null, 2) : 'boot-state.json 不存在（插件尚未启动过或快照为空）')
    ok = true
    break
  }
  case 'safe-mode': ok = safeMode(arg ?? 'status'); break
  default: console.log('未知命令：' + cmd)
  }
  process.exit(ok ? 0 : 1)
}
