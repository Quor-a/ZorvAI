// @vitest-environment jsdom
// The native chooser channel: availability, answer decoding, and the failure
// reasons every caller renders.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { chooserAvailable, openPathChooser } from '../src/client/mobile/open-path.ts'

afterEach(() => {
  delete (window as { androidBridge?: unknown }).androidBridge
})

describe('chooserAvailable', () => {
  it('is false without a shell bridge', () => {
    expect(chooserAvailable()).toBe(false)
  })

  it('is true once the shell injected the method', () => {
    window.androidBridge = { openPathChooser: () => '{"ok":true}' }
    expect(chooserAvailable()).toBe(true)
  })
})

describe('openPathChooser', () => {
  it('reports unavailable without the method', () => {
    expect(openPathChooser('/tmp/a.zip')).toEqual({ ok: false, reason: 'unavailable' })
  })

  it('passes the path and mode to the shell and decodes a success', () => {
    const bridge = vi.fn(() => '{"ok":true,"launched":"bin.mt.plus"}')
    window.androidBridge = { openPathChooser: bridge }
    expect(openPathChooser('/tmp/a.zip', 'folder')).toEqual({ ok: true })
    expect(bridge).toHaveBeenCalledWith('/tmp/a.zip', 'folder')
  })

  it('surfaces the refusal reason the shell reported', () => {
    window.androidBridge = { openPathChooser: () => '{"ok":false,"reason":"no-handler"}' }
    expect(openPathChooser('/tmp/a.zip')).toEqual({ ok: false, reason: 'no-handler' })
  })

  it('treats an empty or non-JSON answer as a bridge failure', () => {
    window.androidBridge = { openPathChooser: () => '' }
    expect(openPathChooser('/tmp/a.zip').ok).toBe(false)
    window.androidBridge = { openPathChooser: () => 'not json' }
    expect(openPathChooser('/tmp/a.zip').ok).toBe(false)
  })

  it('turns a throwing bridge into a failure result', () => {
    window.androidBridge = { openPathChooser: () => { throw new Error('boom') } }
    expect(openPathChooser('/tmp/a.zip')).toEqual({ ok: false, reason: 'boom' })
  })
})
