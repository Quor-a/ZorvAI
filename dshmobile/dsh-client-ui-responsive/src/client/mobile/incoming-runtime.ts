import type { IncomingDraftRuntime } from './incoming-draft.ts'

/** Service faces reflect rc.2: Sessions owns identities; uiWorkspace owns visible navigation. */
interface RuntimeServices {
  sessions(): { refresh(): Promise<void>; scope(id: unknown): unknown | undefined }
  workspaces(): { create(input: { path: string }): Promise<{ workspaceId: unknown }> }
  uiWorkspace(): { connectWorkspace(id: unknown): Promise<unknown>; openSession(id: unknown): void }
  conversation(): {
    addFiles(id: unknown, files: readonly File[]): boolean
    input: { for(scope: unknown): { notify(level: 'info' | 'error', text: string): void } }
  }
}

/** Resolve each root service at use time, after Cordis has materialized its scoped face. */
export function createIncomingDraftRuntime(services: RuntimeServices): IncomingDraftRuntime {
  const { sessions, workspaces, uiWorkspace, conversation } = services
  return {
  refreshSessions: () => sessions().refresh(),
  createSession: async (cwd) => {
    document.documentElement.setAttribute('data-dsh-incoming-draft-poll', 'workspace-create')
    const workspace = await workspaces().create({ path: cwd })
    document.documentElement.setAttribute('data-dsh-incoming-draft-poll', 'workspace-created')
    document.documentElement.setAttribute('data-dsh-incoming-draft-poll', 'workspace-connect')
    const sessionId = await uiWorkspace().connectWorkspace(workspace.workspaceId)
    document.documentElement.setAttribute('data-dsh-incoming-draft-poll', 'workspace-connected')
    return String(sessionId)
  },
  openSession: (sessionId) => { uiWorkspace().openSession(sessionId) },
  sessionScope: (sessionId) => sessions().scope(sessionId),
  attachGenericFile: (sessionId, file) => {
    try {
      return conversation().addFiles(sessionId, [file])
    } catch {
      // The target can be released between session navigation and draft admission.
      return false
    }
  },
  notify: (scope, text) => {
    try { conversation().input.for(scope).notify('error', text) } catch { /* target scope ended */ }
  },
  }
}
