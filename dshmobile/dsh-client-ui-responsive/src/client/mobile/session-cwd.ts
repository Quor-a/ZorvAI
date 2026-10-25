/**
 * One Session's workspace directory, read from the runtime session list.
 *
 * The session-list snapshot is typed loosely here (this plugin declares only the
 * slice it consumes), so the read goes through one narrow shape guard instead of
 * casting at every call site.
 */

/** The runtime subscription hook shape this module needs (global standard props). */
export type UseSessionsLike = <T>(selector: (state: unknown) => T) => T

/**
 * Read one Session's workspace directory.
 * @param state - the runtime's session-list snapshot.
 * @param sessionId - the Session whose summary is read.
 * @returns the directory, or `undefined` when the summary lacks one.
 */
export function sessionCwd(state: unknown, sessionId: string): string | undefined {
  const byId = (state as { byId?: Record<string, { cwd?: unknown } | undefined> }).byId
  const cwd = byId?.[sessionId]?.cwd
  return typeof cwd === 'string' && cwd !== '' ? cwd : undefined
}
