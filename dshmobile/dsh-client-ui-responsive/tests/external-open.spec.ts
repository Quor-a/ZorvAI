// @vitest-environment node
// The open-with type's decisions: which suffixes it claims, what it shows as a
// chip, and that it steps aside for any builtin or extension type that already
// welcomes the address.
import { describe, expect, it } from 'vitest'
import {
  EXTERNAL_OPEN_ID, EXTERNAL_OPEN_KIND, basenameOf, extensionOf, externalOpenDefinition, isExternalOnlyPath,
} from '../src/client/mobile/external-open-paths.ts'

describe('extensionOf / isExternalOnlyPath', () => {
  it('reads the suffix case-insensitively through both separators', () => {
    expect(extensionOf('a/b/C.ZIP')).toBe('zip')
    expect(extensionOf('a\\b\\c.7z')).toBe('7z')
  })

  it('treats a dotfile, a trailing dot, and a bare name as extensionless', () => {
    expect(extensionOf('.env')).toBe('')
    expect(extensionOf('notes.')).toBe('')
    expect(extensionOf('README')).toBe('')
  })

  it('claims archives, packages, and binaries', () => {
    for (const path of ['a.zip', 'app.apk', 'lib.so', 'img.iso', 'f.tar.gz']) {
      expect(isExternalOnlyPath(path), path).toBe(true)
    }
  })

  it('leaves previewable content to upstream', () => {
    for (const path of ['a.md', 'a.ts', 'a.png', 'a.pdf', 'a.html', 'a.txt']) {
      expect(isExternalOnlyPath(path), path).toBe(false)
    }
  })

  it('does not treat a suffix-less archive name as a claim', () => {
    expect(isExternalOnlyPath('archive')).toBe(false)
  })
})

describe('basenameOf', () => {
  it('decodes the last segment', () => {
    expect(basenameOf('dsh-resource://file/session/s/a%20b.zip')).toBe('a b.zip')
  })

  it('shows a malformed escape raw instead of refusing it', () => {
    expect(basenameOf('dsh-resource://file/session/s/%E0%A4%A')).toBe('%E0%A4%A')
  })
})

describe('externalOpenDefinition', () => {
  const definition = externalOpenDefinition(() => false)

  it('is an extension-band type claiming the file address family', () => {
    expect(definition.id).toBe(EXTERNAL_OPEN_ID)
    expect(definition.kind).toBe(EXTERNAL_OPEN_KIND)
    expect(definition.priority).toBe('extension')
    expect(definition.patterns).toEqual(['dsh-resource://file/**'])
  })

  it('opens a claimed archive', () => {
    expect(definition.canOpen?.('dsh-resource://file/session/s/a.zip')).toBe(true)
  })

  it('declines a previewable file', () => {
    expect(definition.canOpen?.('dsh-resource://file/session/s/a.md')).toBe(false)
  })

  it('declines an archive another type already welcomes', () => {
    const guarded = externalOpenDefinition(() => true)
    expect(guarded.canOpen?.('dsh-resource://file/session/s/a.zip')).toBe(false)
  })

  it('takes absolute addresses nothing else claims', () => {
    expect(definition.canOpen?.('dsh-resource://file/absolute/tmp/a.zip')).toBe(true)
  })

  it('declines foreign addresses', () => {
    expect(definition.canOpen?.('https://example.com/a.zip')).toBe(false)
  })
})
