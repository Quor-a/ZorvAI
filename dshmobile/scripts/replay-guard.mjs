#!/usr/bin/env node
// replay-guard.mjs — Git Data API 重放的**安全预检**（0.14.1 W1 固化；来源：上一轮推送受阻时的一次性脚本）
//
// 为什么必须固化：当 `git push` 走不通（github.com:443 不通 / 代理只对部分目标有效）时，唯一通路是
// 用 Git Data API 在远端**重建提交**。那条路上的致命失败模式是**静默回退别人的内容**：
// 重放以「我的父提交树」为 base_tree，只写我改过的那几个 blob——若远端目标分支上这些路径**已经不是**
// 我基线里的那份（别人先合了改动、或我先前的提交被 squash 过），重放就会把它们覆盖回旧内容，
// 而且**全链零报错**：commit 建成、CI 绿、评审看不出来。
//
// 判据（逐文件三方比对）：对每个我改动过的路径，比
//   mine   = 我的父提交里的 blob（重放的 base_tree 就是它）
//   remote = 远端目标分支上**现在**的 blob（GitHub contents API）
// mine == remote ⇒ 干净增量（重放不会回退任何东西）；不等或远端缺席 ⇒ 必须人工合并，**禁止重放**。
//
// 用法：
//   node scripts/replay-guard.mjs --repo <仓目录> --parent <rev> --head <rev> --gh <owner/name> --base <远端分支>
//   node scripts/replay-guard.mjs --self-test        # 反向对照（离线，无需 token/网络）
// 退出码：0 = 干净增量（可重放）；1 = 存在 DIFF 或执行失败。
//
// 令牌（非泄露纪律）：只从环境变量 `GITHUB_TOKEN` 或 `--token-file`（缺省
// `~/.codex/credentials/github_token.txt`）读取，**只进 Authorization 头**、不进 URL；本脚本任何
// 输出（含错误路径）都不得包含令牌明文。令牌缺席时判红而不是降级为「跳过」——本预检的全部价值
// 就在于比对远端真值，取不到远端就等于没预检。
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const argv = process.argv.slice(2)
const argOf = (name) => { const i = argv.indexOf('--' + name); return i >= 0 ? argv[i + 1] : undefined }
const SELF_TEST = argv.includes('--self-test')
const fail = (msg) => { console.error('REPLAY-GUARD FAILED：' + msg); process.exit(1) }

/** git 调用（静默：失败由调用点决定语义）。 */
const git = (cwd, args) => execFileSync('git', ['-C', cwd, ...args], { encoding: 'utf8' })

/** 某 rev 下该路径的 blob SHA；缺席/出错各返回可区分的哨兵串。 */
export const blobAt = (repoDir, rev, path) => {
  try {
    const out = git(repoDir, ['ls-tree', rev, '--', path]).trim()
    return out === '' ? '(absent)' : out.split(/\s+/)[2]
  } catch {
    return '(error)'
  }
}

/**
 * 逐文件比对「我的增量」与「远端现状」。
 *
 * @param opts - `{ repoDir, parent, head, remoteBlob }`：`remoteBlob(path)` 异步返回远端 blob SHA。
 * @returns `{ rows, clean, dirty }`：`rows` 每项 `{ path, mine, remote, ok }`。
 */
export const compareIncrements = async ({ repoDir, parent, head, remoteBlob }) => {
  const paths = git(repoDir, ['diff-tree', '-r', '--no-commit-id', '--name-only', parent, head])
    .split('\n').map((s) => s.trim()).filter(Boolean)
  const rows = []
  let clean = 0
  let dirty = 0
  for (const path of paths) {
    const mine = blobAt(repoDir, parent, path)
    const remote = await remoteBlob(path)
    const ok = mine === remote
    if (ok) clean += 1; else dirty += 1
    rows.push({ path, mine, remote, ok })
  }
  return { rows, clean, dirty }
}

/** GitHub contents API 取远端 blob SHA（只读，不打令牌）。 */
const makeRemoteBlob = (ghRepo, baseRef, token) => async (path) => {
  const url = 'https://api.github.com/repos/' + ghRepo + '/contents/'
    + path.split('/').map(encodeURIComponent).join('/') + '?ref=' + encodeURIComponent(baseRef)
  let res
  try {
    res = await fetch(url, {
      headers: {
        Authorization: 'Bearer ' + token,
        Accept: 'application/vnd.github+json',
        'User-Agent': 'dsh-mobile-replay-guard',
        'X-GitHub-Api-Version': '2022-11-28',
      },
    })
  } catch (e) {
    // 网络错误文本可能带 URL（不含令牌）——仍裁到一行，避免刷屏掩盖判据。
    fail('取远端 blob 失败（' + path + '）：' + String(e && e.message).split('\n')[0].slice(0, 160))
  }
  if (res.status === 404) return '(absent)'
  if (!res.ok) fail('GitHub contents API ' + res.status + '（' + path + '）——令牌权限/仓库名/分支名先核对；本预检不降级')
  const body = await res.json()
  return body.sha
}

const printRows = (rows) => {
  for (const r of rows) {
    console.log((r.ok ? 'OK   ' : 'DIFF ') + r.path + '  mine=' + r.mine.slice(0, 8) + ' remote=' + r.remote.slice(0, 8))
  }
}

// ── 反向对照（--self-test）：离线证明「差异必被抓」与「干净增量必放行」──
if (SELF_TEST) {
  const tmp = mkdtempSync(join(tmpdir(), 'replay-guard-'))
  const base = join(tmp, 'base')
  const remote = join(tmp, 'remote')
  try {
    for (const dir of [base, remote]) {
      execFileSync('git', ['init', '-q', '-b', 'main', dir])
      git(dir, ['config', 'user.email', 'gate@example.invalid'])
      git(dir, ['config', 'user.name', 'gate'])
      // 夹具里不需要行尾转换；开着它只会往判据输出里插一堆 warning 噪声。
      git(dir, ['config', 'core.autocrlf', 'false'])
    }
    // 基线：a.txt 内容 A，b.txt 内容 B
    writeFileSync(join(base, 'a.txt'), 'A\n')
    writeFileSync(join(base, 'b.txt'), 'B\n')
    git(base, ['add', '-A']); git(base, ['commit', '-qm', 'base'])
    const baseRev = git(base, ['rev-parse', 'HEAD']).trim()
    // 远端从同一基线出发：a.txt 被别人改成 A2（我要改 a.txt 就会撞车），b.txt 保持 B
    execFileSync('git', ['-C', remote, 'fetch', '-q', base, baseRev])
    git(remote, ['reset', '-q', '--hard', 'FETCH_HEAD'])
    writeFileSync(join(remote, 'a.txt'), 'A2\n')
    git(remote, ['add', '-A']); git(remote, ['commit', '-qm', 'peer change'])
    // 我在基线上改 a.txt → A3，并新增 c.txt（干净增量）
    writeFileSync(join(base, 'a.txt'), 'A3\n')
    writeFileSync(join(base, 'c.txt'), 'C\n')
    git(base, ['add', '-A']); git(base, ['commit', '-qm', 'mine'])
    const headRev = git(base, ['rev-parse', 'HEAD']).trim()

    const remoteBlob = async (path) => blobAt(remote, 'HEAD', path)
    const r = await compareIncrements({ repoDir: base, parent: baseRev, head: headRev, remoteBlob })
    printRows(r.rows)
    const aRow = r.rows.find((x) => x.path === 'a.txt')
    const cRow = r.rows.find((x) => x.path === 'c.txt')
    const okA = aRow !== undefined && aRow.ok === false            // 远端 ≠ 我的基线 ⇒ 必须报 DIFF
    const okC = cRow !== undefined && cRow.ok === true             // 远端无此文件、我的基线也无 ⇒ 干净
    const okCount = r.dirty === 1 && r.clean === 1
    console.log('REPLAY-GUARD SELF-TEST ' + (okA && okC && okCount ? 'PASSED' : 'FAILED')
      + '（远端漂移必报 DIFF=' + okA + '；干净新增必放行=' + okC + '；计数 clean=' + r.clean + ' dirty=' + r.dirty + '）')
    if (!(okA && okC && okCount)) process.exit(1)
  } finally {
    try { rmSync(tmp, { recursive: true, force: true }) } catch { /* 临时目录清理失败不影响判据 */ }
  }
  process.exit(0)
}

// ── 正检：比对真实远端 ─────────────────────────────────────────────────
const repoDir = argOf('repo')
const parent = argOf('parent')
const head = argOf('head')
const ghRepo = argOf('gh')
const baseRef = argOf('base')
for (const [k, v] of Object.entries({ repo: repoDir, parent, head, gh: ghRepo, base: baseRef })) {
  if (!v) fail('缺参数 --' + k + '（见文件头用法）')
}
if (!existsSync(join(repoDir, '.git'))) fail('--repo 不是 git 工作树：' + repoDir)
const tokenFile = argOf('token-file') ?? join(homedir(), '.codex', 'credentials', 'github_token.txt')
const token = process.env.GITHUB_TOKEN ?? (existsSync(tokenFile) ? readFileSync(tokenFile, 'utf8').trim() : '')
if (token === '') fail('令牌缺席（env GITHUB_TOKEN 或 --token-file）：远端真值取不到时本预检不得以「跳过」结案')

const { rows, clean, dirty } = await compareIncrements({
  repoDir, parent, head, remoteBlob: makeRemoteBlob(ghRepo, baseRef, token),
})
printRows(rows)
console.log('--- 干净增量 ' + clean + ' / 需人工合并 ' + dirty + '（总 ' + rows.length + '）')
if (rows.length === 0) fail('父提交与我的提交之间没有任何文件改动（参数给错了？）')
if (dirty > 0) {
  console.error('REPLAY-GUARD FAILED：' + dirty + ' 个路径上远端与我的基线不同——重放会**回退别人的内容**，'
    + '禁止重建提交；改为先把远端分支拉到本地、在其上重做这批改动')
  process.exit(1)
}
console.log('REPLAY-GUARD PASSED（干净增量，可安全用 Git Data API 重放）')
