/**
 * The Android shell's native "open with" channel.
 *
 * The shell exposes `androidBridge.openPathChooser(path, mode)` (Kotlin
 * `PathOpen`), which shows the system chooser over the installed file managers
 * (MT Manager, the system DocumentsUI) and answers with a JSON result. Every
 * caller here degrades to "unavailable" when the bridge (or that method) is
 * missing, which is the desktop/non-shell case.
 */
import type { OpenPathMode, OpenPathResult } from '../android-bridge.ts'

/** Exact wire shape `openPathChooser` answers with. */
type BridgeAnswer = { ok?: boolean; launched?: boolean; reason?: string }

/**
 * Whether the running host can raise the native chooser.
 * @returns true when the shell injected the method.
 */
export function chooserAvailable(): boolean {
  return typeof window.androidBridge?.openPathChooser === 'function'
}

/**
 * Ask the shell to open a path through the system chooser.
 * @param path - absolute device path.
 * @param mode - `folder` targets file managers on the directory, `view` on the file.
 * @returns the shell's outcome; `{ ok: false, reason: 'unavailable' }` without a shell.
 */
export function openPathChooser(path: string, mode: OpenPathMode = 'view'): OpenPathResult {
  const bridge = window.androidBridge
  if (typeof bridge?.openPathChooser !== 'function') return { ok: false, reason: 'unavailable' }
  try {
    const raw = bridge.openPathChooser(path, mode)
    if (typeof raw !== 'string' || raw === '') return { ok: false, reason: 'empty-answer' }
    const answer = JSON.parse(raw) as BridgeAnswer
    if (answer.ok === true) return { ok: true }
    return { ok: false, reason: typeof answer.reason === 'string' ? answer.reason : 'refused' }
  } catch (error) {
    // A bridge that threw (or answered non-JSON) is a shell-side failure, not a
    // user-level refusal; both surface through the same dialog copy.
    return { ok: false, reason: error instanceof Error ? error.message : 'bridge-error' }
  }
}
