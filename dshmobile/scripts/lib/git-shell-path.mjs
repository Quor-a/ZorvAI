// git-shell-path.mjs — 快照内 git 的编译期 SHELL_PATH 等长重定位（issue apk#247）。
//
// 背景：git 编译期把 SHELL_PATH 写死为 /data/data/com.termux/files/usr/bin/sh（38 B）。
// 该前缀在应用域不存在，而 git **没有为 shell 路径提供任何运行时覆盖点**——对照：
// --exec-path 有 GIT_EXEC_PATH（本仓 wrapper 已覆盖，issue apk#80/#87）、CA 有
// GIT_SSL_CAINFO。git 的 credential.helper / `!` 前缀 alias / hook / rebase --exec
// 一律经 run-command 走 shell，故它们全部以 `cannot exec` 失败。
//
// 修法：在**白名单** ELF 内做**等长**原地替换（旧串 38 B → `/system/bin/sh` + NUL 填充）。
// 文件长度与 ELF 节表/偏移全不变，因此不违反 relocate-snapshot.py 头部那条
// 「ELF 不做变长重写」的禁令。
//
// 为什么是白名单而不是「所有含旧串的 ELF」：快照内另有 node / dash / make / tar.real
// 等 20+ 个 ELF 也含同一字面量（多为 help 文本或编译期默认值），它们不在本 issue 的
// 因果链上，一律维持原状。
//
// **顺序约束（本模块的调用点必须晚于 build-snapshot-013.mjs 的第 7d 步）**：
// usr/bin/git.real 是在第 7d 步由基座解压出来的 usr/bin/git 改名而来；7d 之前它
// 并不存在（或会被 7d 的 rmSync + rename 覆盖）。所以本步**不能**与 fix-shebang.py
// （第 6 步）同一阶段，必须落在 7d 之后、第 8 步归档之前。
import { readFileSync, writeFileSync, existsSync, readdirSync, lstatSync } from 'node:fs'
import { join } from 'node:path'

/** git 编译期写死的 shell 路径（38 B，Termux 前缀，应用域不存在）。 */
export const SHELL_PATH_OLD = Buffer.from('/data/data/com.termux/files/usr/bin/sh')
/** 等长替身的目标：Android 原生 shell（14 B，处处可达）。 */
export const SHELL_PATH_NEW = '/system/bin/sh'

if (SHELL_PATH_NEW.length >= SHELL_PATH_OLD.length) {
  throw new Error('等长替换要求新串严格短于旧串')
}

/** 旧串的等长替身：新串 + NUL 填充到旧串长度。 */
export function shellPathPad() {
  return Buffer.concat([
    Buffer.from(SHELL_PATH_NEW),
    Buffer.alloc(SHELL_PATH_OLD.length - SHELL_PATH_NEW.length),
  ])
}

/** 白名单判据：只有 git 家族的这两个位置带"Shell 路径"这层语义。 */
export function isGitShellPathElf(rel) {
  return rel === 'bin/git.real' || rel.startsWith('libexec/git-core/')
}

/**
 * 就地在 buf 内把旧串替换为等长替身，返回替换次数。
 * 等长由构造保证：只做原地覆写，绝不改变 length。
 */
export function replaceShellPath(buf) {
  const pad = shellPathPad()
  let count = 0
  let from = 0
  for (;;) {
    const at = buf.indexOf(SHELL_PATH_OLD, from)
    if (at < 0) break
    pad.copy(buf, at)
    count += 1
    from = at + pad.length
  }
  return count
}

/**
 * 对 usr 树白名单内的**实体** ELF 施加等长替换（幂等）。
 *
 * 只认实体文件（lstat 而非 stat）：真品 libexec/git-core 有 152 个符号链接，
 * 其中 git-shell -> ../../bin/git-shell、scalar -> ../../bin/scalar **指向白名单
 * 之外**；若按 stat 跟随链接，就会借白名单越权改写 usr/bin/ 下的文件——白名单
 * 本就是为了防这个。
 *
 * @param {string} usrRoot 快照 usr 根（build-snapshot-013.mjs 的 U）
 * @returns {{scanned:number, files:number, hits:number, skippedLinks:number}}
 */
export function relocateGitShellPath(usrRoot) {
  const targets = []
  let skippedLinks = 0
  const gitReal = join(usrRoot, 'bin', 'git.real')
  if (existsSync(gitReal) && lstatSync(gitReal).isFile()) targets.push(['bin/git.real', gitReal])
  const coreDir = join(usrRoot, 'libexec', 'git-core')
  if (existsSync(coreDir)) {
    for (const name of readdirSync(coreDir).sort()) {
      const rel = 'libexec/git-core/' + name
      if (!isGitShellPathElf(rel)) continue
      const p = join(coreDir, name)
      try {
        if (lstatSync(p).isFile()) {
          targets.push([rel, p])
        } else {
          skippedLinks += 1
        }
      } catch {
        /* 悬空链接/竞态：跳过 */
      }
    }
  }
  let files = 0
  let hits = 0
  for (const [rel, p] of targets) {
    const buf = readFileSync(p)
    const before = buf.length
    const n = replaceShellPath(buf)
    if (n === 0) continue
    writeFileSync(p, buf)
    // 等长自证（就地覆写的必然结果，此处按产出物复核而非按推理）
    const after = readFileSync(p).length
    if (after !== before) {
      throw new Error(`等长替换被破坏: ${rel} ${before} -> ${after}`)
    }
    files += 1
    hits += n
  }
  return { scanned: targets.length, files, hits, skippedLinks }
}
