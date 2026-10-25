/**
 * The open-with type's pure decisions: what it claims and how it names a tab.
 *
 * Split from the component so the rules are testable without React, the store
 * engine, or a DOM.
 */
import type { SidebarRightTabDefinition } from '@deepseek-ai/dsh-client-ui-sidebar-right/client'
import { parseFileAddress } from './address.ts'

/** This type's identity: the registry id and the key its body registers under. */
export const EXTERNAL_OPEN_ID = '@dsh-android/client-ui-responsive/open-with'

/** This type's kind discriminator. */
export const EXTERNAL_OPEN_KIND = 'open-with'

/** The address family this type claims. */
const FILE_ADDRESS_PATTERN = 'dsh-resource://file/**'

/**
 * Suffixes whose content is not text and has no preview renderer, so the phone
 * answer is "hand it to another application": archives, Android/iOS packages,
 * disk images, installers, databases, machine code, fonts.
 */
const EXTERNAL_ONLY_EXTENSIONS: readonly string[] = [
  '7z', 'a', 'aab', 'aar', 'apk', 'apks', 'bin', 'bz2', 'cab', 'class', 'dat', 'db', 'deb', 'dex',
  'dll', 'dmg', 'dylib', 'exe', 'gz', 'img', 'iso', 'jar', 'lz4', 'lzma', 'msi', 'msix', 'o', 'pak',
  'rar', 'rpm', 'so', 'sqlite', 'sqlite3', 'tar', 'tgz', 'ttf', 'otf', 'wasm', 'xapk', 'xz', 'zip',
  'zst',
]

/**
 * The lowercase suffix of a path, without its dot.
 * @param path - decoded file path.
 * @returns the suffix, or an empty string when the name has none.
 */
export function extensionOf(path: string): string {
  const name = path.replace(/\\/g, '/').split('/').pop() ?? ''
  const dot = name.lastIndexOf('.')
  return dot <= 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/**
 * Whether a path's content has no preview and belongs to another application.
 * @param path - decoded file path.
 * @returns true for the curated suffix list.
 */
export function isExternalOnlyPath(path: string): boolean {
  return EXTERNAL_ONLY_EXTENSIONS.includes(extensionOf(path))
}

/**
 * The decoded last segment of an address, used as the tab's chip title.
 * @param address - a `dsh-resource://file/…` address.
 * @returns the decoded name, or the address when it has no segment.
 */
export function basenameOf(address: string): string {
  const name = address.slice(address.lastIndexOf('/') + 1)
  if (name === '') return address
  try {
    return decodeURIComponent(name)
  } catch {
    // A malformed escape is still a name; showing it raw beats refusing the address.
    return name
  }
}

/**
 * The type's registry definition.
 * @param claimedByAnother - asks the registry whether a builtin or extension type already welcomes the address.
 * @returns the definition to register.
 */
export function externalOpenDefinition(claimedByAnother: (address: string) => boolean): SidebarRightTabDefinition {
  return {
    id: EXTERNAL_OPEN_ID,
    kind: EXTERNAL_OPEN_KIND,
    patterns: [FILE_ADDRESS_PATTERN],
    priority: 'extension',
    canOpen: (address) => {
      const parsed = parseFileAddress(address)
      if (parsed === undefined) return false
      // Absolute addresses have no other claimant at all (the text preview takes
      // Session-scoped ones only), so they always land here.
      if (parsed.scope === 'absolute') return true
      if (!isExternalOnlyPath(parsed.path)) return false
      return !claimedByAnother(address)
    },
    title: basenameOf,
  }
}
