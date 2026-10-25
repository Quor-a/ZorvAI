/**
 * `dsh-resource://file/…` address parsing for the Android open-with entries.
 *
 * The grammar is upstream's (`@deepseek-ai/dsh-util-workspace-path`), which the
 * Files tab uses to name a row: `dsh-resource://file/session/<sessionId>/<path>`
 * for a workspace path, `dsh-resource://file/absolute/<path>` for a path the
 * Session does not root. The package is not a shared module-table seat, so this
 * module re-states the parse rule the Android side needs; a change upstream is
 * caught by `tests/address.spec.ts`.
 */

/** Parts of a file resource address this plugin acts on. */
export type ParsedFileAddress =
  | { readonly scope: 'session'; readonly sessionId: string; readonly path: string }
  | { readonly scope: 'absolute'; readonly path: string }

/** The scheme and type every file address opens with. */
const FILE_ADDRESS_PREFIX = 'dsh-resource://file/'

/** Whether a decoded first segment is a Windows drive (`C:`). */
function isDriveSegment(segment: string | undefined): boolean {
  return segment !== undefined && /^[A-Za-z]:$/.test(segment)
}

/**
 * Read a file address back into its parts.
 * @param address - a candidate address.
 * @returns the parts, or `undefined` when the string is not a file address in a known scope or a segment is malformed.
 */
export function parseFileAddress(address: string): ParsedFileAddress | undefined {
  try {
    if (!address.startsWith(FILE_ADDRESS_PREFIX)) return undefined
    const end = address.search(/[?#]/)
    const [scope, ...rest] = address.slice(FILE_ADDRESS_PREFIX.length, end === -1 ? undefined : end).split('/')
    if (scope === 'session') {
      const [id, ...segments] = rest
      if (id === undefined || id === '' || segments.length === 0) return undefined
      return { scope, sessionId: decodeURIComponent(id), path: segments.map(decodeURIComponent).join('/') }
    }
    if (scope === 'absolute') {
      const unc = rest[0] === '' && rest.length > 1
      const segments = (unc ? rest.slice(1) : rest).map(decodeURIComponent)
      if (segments.length === 0 || segments[0] === '') return undefined
      if (unc) return { scope, path: `//${segments.join('/')}` }
      return { scope, path: isDriveSegment(segments[0]) ? segments.join('/') : `/${segments.join('/')}` }
    }
    return undefined
  } catch {
    // `decodeURIComponent` throws URIError on a malformed escape; the address is then simply unusable.
    return undefined
  }
}

/**
 * Resolve a parsed address to the absolute device path the shell can open.
 * @param parsed - the parsed address.
 * @param sessionRoot - the Session's workspace directory, from its summary.
 * @returns the absolute path, or `undefined` when a relative path has no known root.
 */
export function resolveAbsolutePath(parsed: ParsedFileAddress, sessionRoot: string | undefined): string | undefined {
  if (parsed.scope === 'absolute') return parsed.path
  if (parsed.path.startsWith('/')) return parsed.path
  if (sessionRoot === undefined || sessionRoot === '') return undefined
  const root = sessionRoot.replace(/\/+$/, '')
  return parsed.path === '' ? root : `${root}/${parsed.path}`
}
