/**
 * Export-result dialog: the `shell.overlay` entry that renders the Android
 * shell's session-export outcome (and this plugin's own native-action
 * failures). Pure component: state arrives through the framework-bound
 * `useExportResult` hook, dismissal through the injected callback. The markup
 * reuses the web-ui dialog conventions (role=dialog / aria-modal) and the
 * shared design tokens, so the dialog matches the app's modal surfaces.
 */
import { useEffect } from 'react'
import type { PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type { ExportResultSnapshot } from './export-result.ts'
import css from './ExportResultDialog.module.css'

/** Composed props: the overlay runtime share plus the injected dialog face. */
export interface ExportResultDialogProps extends PropsRuntime<'shell.overlay'> {
  /** Framework-bound reader over the channel's snapshot. */
  useExportResult: <T>(selector: (snapshot: ExportResultSnapshot) => T) => T
  /** Fold the dialog. */
  close: () => void
}

/** The single entry component; renders nothing while no result is open. */
export function ExportResultDialog({ useExportResult, close }: ExportResultDialogProps) {
  const state = useExportResult(snapshot => snapshot)

  useEffect(() => {
    if (!state.open) return
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => { window.removeEventListener('keydown', onKeyDown) }
  }, [state.open, close])

  if (!state.open) return null
  return (
    <div className={css.backdrop} onClick={() => close()}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="dsh-export-result-title"
        className={css.dialog}
        onClick={(event) => { event.stopPropagation() }}
      >
        <h2 id="dsh-export-result-title" className={css.title}>{state.title}</h2>
        <p className={css.detail} data-status={state.ok ? 'success' : 'error'}>{state.detail}</p>
        <div className={css.actions}>
          <button type="button" className={css.button} onClick={() => close()}>关闭</button>
        </div>
      </div>
    </div>
  )
}
