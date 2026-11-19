import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, realpathSync, statSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'

// v2: the isolated workspace is a synthetic subtree commit when the source lives in a
// subdirectory of a larger repository. v1 assumed the source path itself was a Git root.
const MARKER = '.dsh-local-source-workspace.json'
const MARKER_SCHEMA = 2
const gitEnv = { ...process.env, GIT_LFS_SKIP_SMUDGE: '1', GIT_OPTIONAL_LOCKS: '0' }
for (const name of ['GIT_DIR', 'GIT_COMMON_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES']) delete gitEnv[name]
const git = (root, ...args) => execFileSync('git', ['-c', 'filter.lfs.process=', '-c', 'filter.lfs.smudge=', '-c', 'filter.lfs.required=false', '-C', root, ...args], { env: gitEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()

function canonicalPath(path) {
  if (existsSync(path)) return realpathSync(path)
  return join(canonicalPath(dirname(path)), basename(path))
}

function contains(parent, child) {
  const rel = relative(parent, child)
  return rel === '' || (!isAbsolute(rel) && rel !== '..' && !rel.startsWith('../') && !rel.startsWith('..\\'))
}

function rejectSourceLinks(workspace, sourceRoot, directory = workspace) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === '.git') {
        const worktree = canonicalPath(git(directory, 'rev-parse', '--show-toplevel'))
        if (contains(sourceRoot, worktree) || contains(worktree, sourceRoot)) throw new Error(`来源构建 Git 工作树可触及原仓：${relative(workspace, path)}`)
      }
      rejectSourceLinks(workspace, sourceRoot, path)
    }
    else if (entry.isSymbolicLink()) {
      const target = canonicalPath(resolve(directory, readlinkSync(path)))
      if (contains(sourceRoot, target) || contains(target, sourceRoot)) throw new Error(`来源构建链接可触及原工作树：${relative(workspace, path)}`)
    } else if (entry.name === '.git' && entry.isFile()) {
      const gitdir = /^gitdir:\s*(.+)\s*$/m.exec(readFileSync(path, 'utf8'))?.[1]
      if (gitdir) {
        const target = canonicalPath(resolve(directory, gitdir.trim()))
        if (contains(sourceRoot, target) || contains(target, sourceRoot)) throw new Error(`来源构建 Git 链接可触及原工作树：${relative(workspace, path)}`)
      }
    }
  }
}

export function sourceCommit(source) {
  return git(source, 'rev-parse', 'HEAD')
}

/** Path of `source` relative to its git worktree root ('' when it IS the root). */
function sourceGitPrefix(source) {
  const prefix = git(source, 'rev-parse', '--show-prefix')  // e.g. 'dshmobile/' or ''
  return prefix ? prefix.replace(/\\/g, '/') : ''
}

/** Git repository that actually owns `source` (the parent repo for vendored subtrees). */
function sourceGitRoot(source) {
  return git(source, 'rev-parse', '--show-toplevel')
}

/** Deterministic commit whose root tree is `prefix` (or the original root when empty). */
function makeWorkspaceCommit(workspace, parentCommit, prefix) {
  const tree = git(workspace, 'rev-parse', prefix ? `${parentCommit}:${prefix}` : `${parentCommit}^{tree}`)
  if (!prefix) return parentCommit
  const message = `chore(source): isolated ${prefix} subtree of ${parentCommit}\n`
  const env = {
    ...gitEnv,
    GIT_AUTHOR_NAME: 'dsh-source-chain',
    GIT_AUTHOR_EMAIL: 'source-chain@local',
    GIT_AUTHOR_DATE: '2000-01-01T00:00:00+0000',
    GIT_COMMITTER_NAME: 'dsh-source-chain',
    GIT_COMMITTER_EMAIL: 'source-chain@local',
    GIT_COMMITTER_DATE: '2000-01-01T00:00:00+0000',
  }
  return execFileSync('git', ['-C', workspace, 'commit-tree', tree, '-m', message], {
    env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }).trim()
}

/** Fixed DSH release key is kept outside the repository by the pre-commit hook. */
const EXTERNAL_DEBUG_KEYSTORE = process.env.DSH_DEBUG_KEYSTORE
  || join(homedir(), '.dsh-source-build', 'debug.keystore')

function ensureDebugKeystore(workspace, sourceRoot) {
  const target = join(workspace, 'keystore', 'debug.keystore')
  if (existsSync(target)) return
  const sources = [join(sourceRoot, 'keystore', 'debug.keystore'), EXTERNAL_DEBUG_KEYSTORE]
  const found = sources.find(existsSync)
  if (!found) {
    throw new Error(`来源链缺少固定签名密钥：${sources.join(' 或 ')}；可设置 DSH_DEBUG_KEYSTORE 指向项目外副本。`)
  }
  mkdirSync(dirname(target), { recursive: true })
  copyFileSync(found, target)
}

export function committedWorkflow(source, commit) {
  // dshmobile is vendored as a subdirectory of the QuroAI git root, while the source
  // chain expects dshmobile to be the repository root. Prefix the in-tree path so
  // `git show <commit>:<path>` resolves correctly in both layouts.
  const prefix = sourceGitPrefix(source)
  return git(source, 'show', `${commit}:${prefix}.github/workflows/build-apk-source.yml`)
}

/** A private shallow checkout keeps workflow cleanup away from developer files. */
export function prepareSourceWorkspace(source, commit, requested = null) {
  const sourceRoot = realpathSync(source)
  const parentRoot = sourceGitRoot(sourceRoot)
  const prefix = sourceGitPrefix(sourceRoot)
  const tempRoot = canonicalPath(tmpdir())
  if (!requested && contains(sourceRoot, tempRoot)) throw new Error('临时目录位于原工作树内；请用 --build-workspace 指定外部目录。')
  const workspace = requested ? canonicalPath(resolve(requested)) : mkdtempSync(join(tempRoot, 'dsh-source-chain-'))
  if (contains(sourceRoot, workspace) || contains(workspace, sourceRoot)) {
    throw new Error('来源构建目录必须在原工作树之外，且不能是它的父目录。')
  }
  const marker = join(workspace, MARKER)
  if (existsSync(workspace) && readdirSync(workspace).length) {
    if (!existsSync(marker)) throw new Error(`拒绝使用非空且未登记的来源构建目录：${workspace}`)
    const saved = JSON.parse(readFileSync(marker, 'utf8'))
    if (saved.source !== sourceRoot || saved.sourceCommit !== commit || saved.schema !== MARKER_SCHEMA) {
      throw new Error('来源构建目录的原仓或 HEAD 不匹配；请指定新的空目录。')
    }
    const expectedCommit = makeWorkspaceCommit(workspace, commit, prefix)
    const expectedTree = git(workspace, 'rev-parse', `${expectedCommit}^{tree}`)
    // A worktree or linked .git would let destructive steps reach the source repository.
    if (!statSync(join(workspace, '.git')).isDirectory() || realpathSync(join(workspace, '.git')) !== join(workspace, '.git') || existsSync(join(workspace, '.git/commondir')) || existsSync(join(workspace, '.git/objects/info/alternates')) || git(workspace, 'rev-parse', '--show-toplevel') !== workspace || sourceCommit(workspace) !== saved.workspaceCommit || saved.workspaceCommit !== expectedCommit || git(workspace, 'rev-parse', 'HEAD^{tree}') !== expectedTree) {
      throw new Error('来源构建目录不再是登记 HEAD 的独立子树检出。')
    }
    for (const path of ['.deploy-tmp', 'vendor/dshmarketplace-plugin', 'scripts/snapshot-config', 'out', 'base', 'app']) {
      if (!contains(workspace, canonicalPath(join(workspace, path)))) throw new Error(`来源构建写入路径逃出隔离目录：${path}`)
    }
    ensureDebugKeystore(workspace, sourceRoot)
    rejectSourceLinks(workspace, sourceRoot)
    return { workspace, commit: expectedCommit }
  }
  mkdirSync(workspace, { recursive: true })
  // Fetch only this commit, without copying LFS objects or historical runtime archives.
  // LFS stays as pointers, matching the source workflow checkout (lfs: false).
  // The source may be a vendored subdirectory (`dshmobile/`): fetch the owning repo,
  // then materialize that subtree as the workspace root so workflow paths resolve.
  git(workspace, 'init', '-q')
  git(workspace, 'fetch', '--depth=1', '--no-tags', parentRoot, commit)
  const buildCommit = makeWorkspaceCommit(workspace, commit, prefix)
  git(workspace, 'checkout', '--detach', buildCommit)
  ensureDebugKeystore(workspace, sourceRoot)
  rejectSourceLinks(workspace, sourceRoot)
  writeFileSync(marker, JSON.stringify({ schema: MARKER_SCHEMA, source: sourceRoot, sourceCommit: commit, workspaceCommit: buildCommit }, null, 2) + '\n', { flag: 'wx' })
  return { workspace, commit: buildCommit }
}
