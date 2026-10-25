/**
 * Export-result channel: the shell-overlay dialog's data source.
 *
 * A plain observable rather than a framework store: this plugin's only shared
 * state is one dialog's payload, and 0.1.5's `dsh-client-store` is a separate
 * package whose own third-party imports (zustand) are not in the browser module
 * table for a feature plugin. The slot framework binds this source into a
 * `useExportResult` hook through the registration's `hooks` compartment.
 */

/** Wire payload pushed by the Android shell (JSON-serializable only). */
export type ExportResultPayload = {
  ok: boolean
  title: string
  detail: string
}

/** Dialog state: one open dialog at a time; the latest result replaces the last. */
export interface ExportResultSnapshot {
  readonly open: boolean
  readonly ok: boolean
  readonly title: string
  readonly detail: string
}

/** Bare observable source consumed by the framework's hook binding. */
export interface ExportResultSource {
  /** @returns the same snapshot until the dialog state changes. */
  readonly getSnapshot: () => ExportResultSnapshot
  /** @param listener - change observer. @returns its disposer. */
  readonly subscribe: (listener: () => void) => () => void
}

/** Host-owned channel: the plugin writes, the dialog component reads. */
export class ExportResultChannel implements ExportResultSource {
  private listeners = new Set<() => void>()
  private snapshot: ExportResultSnapshot = { open: false, ok: true, title: '', detail: '' }

  /** @returns the current dialog state. */
  readonly getSnapshot = (): ExportResultSnapshot => this.snapshot

  /**
   * @param listener - change observer.
   * @returns its disposer.
   */
  readonly subscribe = (listener: () => void): () => void => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  /**
   * Open the dialog with one outcome; a second result supersedes a still-open first.
   * @param payload - the outcome to show.
   */
  show(payload: ExportResultPayload): void {
    this.snapshot = { open: true, ok: payload.ok, title: payload.title, detail: payload.detail }
    this.publish()
  }

  /** Fold the dialog; the last result stays recorded. */
  close(): void {
    this.snapshot = { ...this.snapshot, open: false }
    this.publish()
  }

  private publish(): void {
    for (const listener of [...this.listeners]) listener()
  }
}

/**
 * Report one user-facing outcome through the shell-overlay dialog.
 *
 * The dialog entry subscribes to the `dsh:export-result` DOM event, which the
 * shell's export bridge and this plugin's own native-action failures both use:
 * one surface, one dismissal, no second dialog implementation.
 * @param payload - the outcome to show.
 */
export function reportUserFacingResult(payload: ExportResultPayload): void {
  window.dispatchEvent(new CustomEvent<ExportResultPayload>('dsh:export-result', { detail: payload }))
}
