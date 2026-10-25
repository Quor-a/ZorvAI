// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { IncomingDraftConsumer, type IncomingDraftFetch } from '../src/client/mobile/incoming-draft.ts'
import { createIncomingDraftRuntime } from '../src/client/mobile/incoming-runtime.ts'

afterEach(() => vi.unstubAllGlobals())

it('opens through rc.2 uiWorkspace and retains the target scope before claiming the file', async () => {
  const scope = {}; let opened = false
  const sessions = { refresh: vi.fn(async () => {}), scope: vi.fn(() => opened ? scope : undefined) }
  // Actual rc.2 ISessions deliberately has no open method.
  const navigation = { connectWorkspace: vi.fn(async () => 'session-fixture'), openSession: vi.fn(() => { opened = true }) }
  const addFiles = vi.fn((_id: unknown, _files: readonly File[]) => true)
  const runtime = createIncomingDraftRuntime({
    sessions: () => sessions,
    workspaces: () => ({ create: async () => ({ workspaceId: 'workspace-fixture' }) }),
    uiWorkspace: () => navigation,
    conversation: () => ({ addFiles, input: { for: () => ({ notify: vi.fn() }) } }),
  })
  const original = window.androidBridge
  Object.defineProperty(window, 'androidBridge', { configurable: true, value: { incomingWorkspacePath: () => '/fixture/incoming' } })
  const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body, blob: async () => new Blob(['old bytes']) })
  const fetchImpl = vi.fn<IncomingDraftFetch>()
    .mockResolvedValueOnce(json({ items: [{ entryId: 'entry-fixture', state: 'received', name: 'old.txt', bytes: 9 }] }))
    .mockResolvedValueOnce(json({ ok: true, ticket: 'fixture-ticket', name: 'old.txt' }))
    .mockResolvedValueOnce(json({}))
    .mockResolvedValueOnce(json({ ok: true }))
  try {
    await new IncomingDraftConsumer(fetchImpl, runtime).poll()
    expect(navigation.openSession).toHaveBeenCalledWith('session-fixture')
    expect(opened).toBe(true)
    expect(addFiles).toHaveBeenCalledTimes(1)
    expect(addFiles.mock.calls[0]?.[0]).toBe('session-fixture')
    expect(String(fetchImpl.mock.calls[3]?.[1]?.body)).toContain('draft-ready')
    expect('open' in sessions).toBe(false)
  } finally {
    Object.defineProperty(window, 'androidBridge', { configurable: true, value: original })
  }
})
