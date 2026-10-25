import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dependencyFields = ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies']

function normalized(value) {
  return JSON.stringify(Object.entries(value ?? {}).sort(([left], [right]) => left.localeCompare(right)))
}

export function checkPackageLockRoot(directory) {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'))
  const lock = JSON.parse(fs.readFileSync(path.join(directory, 'package-lock.json'), 'utf8'))
  const root = lock.packages?.['']
  if (!root) return [`${directory}: package-lock.json 缺少 packages[""]`]

  const errors = []
  for (const field of ['name', 'version']) {
    if (manifest[field] !== root[field]) {
      errors.push(`${directory}: ${field} 不一致 (${manifest[field]} != ${root[field]})`)
    }
  }
  for (const field of dependencyFields) {
    if (normalized(manifest[field]) !== normalized(root[field])) {
      errors.push(`${directory}: ${field} 与 package-lock.json 根声明不一致`)
    }
  }
  return errors
}

export function packageDirectories(repoRoot) {
  const candidates = ['dsh-client-ui-responsive', 'dsh-shell-termux']
  const pluginRoot = path.join(repoRoot, 'plugins')
  for (const entry of fs.readdirSync(pluginRoot, { withFileTypes: true })) {
    if (entry.isDirectory()) candidates.push(path.join('plugins', entry.name))
  }
  return candidates
    .map((relative) => path.join(repoRoot, relative))
    .filter((directory) => fs.existsSync(path.join(directory, 'package-lock.json')))
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repoRoot = path.resolve(process.argv[2] ?? '.')
  const directories = packageDirectories(repoRoot)
  // 空过守卫：受检集合是**发现式**的（packageDirectories 只收「存在 package-lock.json」的目录），
  // 旧实现下「锁文件缺席」与「根声明一致」都表现为绿——删掉/改名任一锁文件，该目录即从集合里
  // 静默消失，门禁照印「一致」退 0。而这正是它要防的事：构建链那边（build-apk-source.yml 的
  // 插件循环）按 `if [ -f package-lock.json ]` 决定 `npm ci` 还是 `npm install`，锁一缺就从
  // 「严格按锁文件」降级成「重新解析版本区间」——产出不再可复现，全链仍然绿。
  // 故对**具名**的两个目录（它们不靠发现，是写死在 packageDirectories 里的）断言必须在场。
  const named = ['dsh-client-ui-responsive', 'dsh-shell-termux']
  const errors = [
    ...named
      .filter((relative) => !directories.includes(path.join(repoRoot, relative)))
      .map((relative) => `${relative}: package-lock.json 缺席——该目录已从受检集合消失（构建会降级为不可复现的 npm install）`),
    ...directories.flatMap(checkPackageLockRoot),
  ]
  if (directories.length === 0) {
    errors.push('受检目录为空——锁文件根声明判据没有任何输入（大概率是锁文件被删/被改名）')
  }
  if (errors.length) {
    for (const error of errors) console.error(error)
    process.exitCode = 1
  } else {
    console.log(`package-lock 根声明一致: ${directories.length} 个目录`)
  }
}
