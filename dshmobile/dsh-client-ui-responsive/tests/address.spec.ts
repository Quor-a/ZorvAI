// @vitest-environment node
// Address parsing for the open-with entry: the grammar belongs to the upstream
// resource model (`@deepseek-ai/dsh-util-workspace-path`), which is not a shared
// module-table seat, so this plugin restates it and pins the rule here.
import { describe, expect, it } from 'vitest'
import { parseFileAddress, resolveAbsolutePath } from '../src/client/mobile/address.ts'

describe('parseFileAddress', () => {
  it('reads a session-scoped path and decodes its segments', () => {
    expect(parseFileAddress('dsh-resource://file/session/root-1/src/a%20b%23c.ts'))
      .toEqual({ scope: 'session', sessionId: 'root-1', path: 'src/a b#c.ts' })
  })

  it('reads an absolute path with its leading slash', () => {
    expect(parseFileAddress('dsh-resource://file/absolute/tmp/x.zip'))
      .toEqual({ scope: 'absolute', path: '/tmp/x.zip' })
  })

  it('keeps a drive letter literal', () => {
    expect(parseFileAddress('dsh-resource://file/absolute/C:/x/y.txt'))
      .toEqual({ scope: 'absolute', path: 'C:/x/y.txt' })
  })

  it('ignores query and fragment suffixes', () => {
    expect(parseFileAddress('dsh-resource://file/session/s/a.zip?line=3#top'))
      .toEqual({ scope: 'session', sessionId: 's', path: 'a.zip' })
  })

  it('refuses other schemes, unknown scopes, and malformed escapes', () => {
    expect(parseFileAddress('https://example.com/a.zip')).toBeUndefined()
    expect(parseFileAddress('dsh-resource://note/session/s/a')).toBeUndefined()
    expect(parseFileAddress('dsh-resource://file/other/a.zip')).toBeUndefined()
    expect(parseFileAddress('dsh-resource://file/session/s/%E0%A4%A')).toBeUndefined()
    expect(parseFileAddress('dsh-resource://file/session/s')).toBeUndefined()
  })
})

describe('resolveAbsolutePath', () => {
  it('joins a relative path onto the session root', () => {
    expect(resolveAbsolutePath({ scope: 'session', sessionId: 's', path: 'a/b.zip' }, '/data/ws'))
      .toBe('/data/ws/a/b.zip')
  })

  it('keeps an absolute path untouched', () => {
    expect(resolveAbsolutePath({ scope: 'session', sessionId: 's', path: '/tmp/a.zip' }, '/data/ws'))
      .toBe('/tmp/a.zip')
  })

  it('maps the workspace root itself', () => {
    expect(resolveAbsolutePath({ scope: 'session', sessionId: 's', path: '' }, '/data/ws/')).toBe('/data/ws')
  })

  it('reports a relative path without a known root', () => {
    expect(resolveAbsolutePath({ scope: 'session', sessionId: 's', path: 'a.zip' }, undefined)).toBeUndefined()
    expect(resolveAbsolutePath({ scope: 'session', sessionId: 's', path: 'a.zip' }, '')).toBeUndefined()
  })

  it('needs no root for an absolute address', () => {
    expect(resolveAbsolutePath({ scope: 'absolute', path: '/tmp/a.zip' }, undefined)).toBe('/tmp/a.zip')
  })
})
