/**
 * Stored-session deletion: resolve the session directory, refuse unsafe states,
 * then remove it in two steps (rename out of the way, then delete).
 *
 * The engine has no `session/delete` RPC (see issue #493), so this module owns
 * the on-disk half: sessions live under `<DSH_HOME>/sessions/<cwd-key>/<id>/`
 * with `session.lock` and `session.v4.jsonl.zstd`. Indexes reconcile from the
 * persistence listing, so a directory that disappears stops being listed.
 *
 * Safety rules:
 * - only `session-<uuid>` ids are accepted (no paths, no globs);
 * - a session with running work is refused; a loaded-but-idle one is allowed, and
 *   the caller unloads it through `afterRename` once the directory left the scan
 *   path (see `deleteStoredSession`);
 * - the id must resolve to exactly one directory (two matches are ambiguous and
 *   nothing is deleted);
 * - the directory is renamed first so a concurrent scan cannot observe a
 *   half-deleted session, then removed recursively.
 */
import { readdir, rename, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'

/** Session ids are the storage unit name; anything else is a path-injection attempt. */
export const SESSION_ID_PATTERN = /^session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Structured failure carrying a stable code for the HTTP layer and tests. */
export class SessionDeleteError extends Error {
  /**
   * @param {string} code - stable machine code (`invalid-id` / `not-found` / `ambiguous` / `session-active` / `delete-failed`).
   * @param {string} message - human-readable reason.
   * @param {object} [details] - extra fields for diagnostics.
   */
  constructor(code, message, details) {
    super(message)
    this.name = 'SessionDeleteError'
    this.code = code
    this.details = details
  }
}

/**
 * Validate a session id.
 * @param {unknown} sessionId - caller-supplied id.
 * @returns {string} the id.
 * @throws {SessionDeleteError} `invalid-id` when the shape is wrong.
 */
export function assertSessionId(sessionId) {
  if (typeof sessionId !== 'string' || !SESSION_ID_PATTERN.test(sessionId)) {
    throw new SessionDeleteError('invalid-id', 'session id must be session-<uuid>')
  }
  return sessionId
}

/**
 * Sessions root for a DSH home.
 * @param {string} home - absolute `DSH_HOME`.
 * @returns {string} `<home>/sessions`.
 */
export function sessionsRoot(home) {
  return join(home, 'sessions')
}

/**
 * Locate the one stored directory for a session.
 * @param {string} root - sessions root.
 * @param {string} sessionId - validated id.
 * @returns {Promise<string>} absolute session directory.
 * @throws {SessionDeleteError} `not-found` / `ambiguous`.
 */
export async function findSessionDir(root, sessionId) {
  let entries
  try {
    entries = await readdir(root, { withFileTypes: true })
  } catch (error) {
    if (error?.code === 'ENOENT') throw new SessionDeleteError('not-found', 'no sessions directory', { sessionId })
    throw error
  }
  const hits = []
  for (const entry of entries) {
    if (!entry.isDirectory()) continue
    const candidate = join(root, entry.name, sessionId)
    try {
      if ((await stat(candidate)).isDirectory()) hits.push(candidate)
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error
    }
  }
  if (hits.length === 0) throw new SessionDeleteError('not-found', 'session not found', { sessionId })
  if (hits.length > 1) throw new SessionDeleteError('ambiguous', 'session id appears in more than one workspace directory', { sessionId, paths: hits })
  return hits[0]
}

/**
 * Delete one stored session.
 * @param {object} options - deletion inputs.
 * @param {string} options.home - absolute `DSH_HOME`.
 * @param {string} options.sessionId - session to delete.
 * @param {boolean} options.isActive - whether any provider reports running work for the session.
 * @param {(info: { sessionId: string, dir: string }) => Promise<void>} [options.beforeRemove] - bookkeeping hook (unarchive/unpin).
 * @param {(info: { sessionId: string, dir: string }) => Promise<void>} [options.afterResolve] - runs once the directory is confirmed (unload the live session).
 * @param {(level: 'info'|'warn'|'error', message: string, extra?: object) => void} [options.logger] - step logger.
 * @returns {Promise<{ ok: true, sessionId: string, dir: string }>} outcome.
 * @throws {SessionDeleteError} on every refused state.
 */
export async function deleteStoredSession({ home, sessionId, isActive, beforeRemove, afterResolve, logger }) {
  const log = typeof logger === 'function' ? logger : () => {}
  assertSessionId(sessionId)
  log('info', 'delete requested', { sessionId, isActive: isActive === true })
  if (isActive === true) {
    throw new SessionDeleteError('session-active', 'the conversation is running; stop it before deleting', { sessionId })
  }
  if (typeof home !== 'string' || home === '') throw new SessionDeleteError('delete-failed', 'DSH_HOME is not available')
  const root = sessionsRoot(home)
  const dir = await findSessionDir(root, sessionId)
  log('info', 'resolved session directory', { sessionId, dir })
  if (typeof beforeRemove === 'function') {
    log('info', 'clearing archive/pin membership', { sessionId })
    await beforeRemove({ sessionId, dir })
    log('info', 'archive/pin membership cleared', { sessionId })
  }
  // 目录已确认存在（且归档/置顶清理完成）之后才卸载内存会话：目录不存在 / 不唯一 /
  // 清理失败都发生在卸载之前，避免「报错但会话已经从内存消失、文件还在」。
  // 这个钩子失败必须让整次删除失败（调用方据此返回 unload-failed，文件保持原状）。
  if (typeof afterResolve === 'function') await afterResolve({ sessionId, dir })
  const graveyard = `${dir}.deleting-${Date.now().toString(36)}`
  try {
    log('info', 'moving session directory out of the scan path', { sessionId, from: dir, to: graveyard })
    await rename(dir, graveyard)
    log('info', 'removing session directory', { sessionId, dir: graveyard })
    await rm(graveyard, { recursive: true, force: true })
    log('info', 'session deleted', { sessionId })
  } catch (error) {
    // 并发删除同一会话时目录会先被别人移走：按 not-found 处理，客户端当成功。
    if (error?.code === 'ENOENT') {
      log('warn', 'session directory disappeared before it could be removed', { sessionId, dir })
      throw new SessionDeleteError('not-found', 'session disappeared while deleting', { sessionId, dir })
    }
    log('error', 'session deletion failed', { sessionId, dir, error: String(error?.message ?? error) })
    throw new SessionDeleteError('delete-failed', `could not delete the session directory: ${String(error?.message ?? error)}`, { sessionId, dir })
  }
  return { ok: true, sessionId, dir }
}

/**
 * Sweep graveyard directories left behind by failed deletions.
 *
 * `deleteStoredSession` renames the session directory to
 * `<dir>.deleting-<t>` before removing it; if the process dies (or `rm` fails)
 * between the two steps the graveyard stays forever with nothing to clean it
 * up. Directories carrying the `.deleting-` marker are only ever produced by
 * that rename step, so the suffix alone identifies them — normal session
 * directories and unrelated files are never touched. Best-effort: every
 * failure (missing root, unreadable workspace, busy file) is swallowed; the
 * sweep must never break route registration.
 *
 * @param {string} home - absolute `DSH_HOME`.
 * @returns {Promise<string[]>} removed graveyard paths (empty when none).
 */
export async function sweepGraveyards(home) {
  const removed = []
  if (typeof home !== 'string' || home === '') return removed
  const root = sessionsRoot(home)
  let workspaces
  try {
    workspaces = await readdir(root, { withFileTypes: true })
  } catch {
    return removed // no sessions directory yet: nothing to sweep
  }
  for (const workspace of workspaces) {
    if (!workspace.isDirectory()) continue
    let entries
    try {
      entries = await readdir(join(root, workspace.name), { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || !entry.name.includes('.deleting-')) continue
      const target = join(root, workspace.name, entry.name)
      try {
        await rm(target, { recursive: true, force: true })
        removed.push(target)
      } catch {
        /* busy or unreadable: leave it for the next sweep */
      }
    }
  }
  return removed
}
