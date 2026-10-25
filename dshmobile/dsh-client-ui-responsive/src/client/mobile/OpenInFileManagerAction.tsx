/**
 * Session-header "open in file manager": the Android replacement for upstream's
 * desktop `open-in-app` split button (both upstream rows are disabled in the
 * Android profile — their host catalog probes Finder/Terminal/editors).
 *
 * It opens the Session's workspace directory through the shell's native
 * chooser (MT Manager, the system file manager), which is the one meaningful
 * "open outside the app" gesture a phone has. Renders nothing without the
 * shell bridge, so a browser host never grows the control.
 */
import type { PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import { chooserAvailable, openPathChooser } from './open-path.ts'
import { sessionCwd } from './session-cwd.ts'
import { reportUserFacingResult } from '../export-result.ts'
import { describeCallReason } from '../user-copy.ts'
import css from './OpenInFileManagerAction.module.css'

/** Label used for both the accessible name and the tooltip. */
const LABEL = '在文件中打开'

/**
 * The header button.
 * @param props - the session-scoped utility share.
 * @returns the button, or null when the host cannot open paths.
 */
export function OpenInFileManagerAction({ sessionId, useSessions }: PropsRuntime<'conversation.session.header.utilities'>) {
  const cwd = useSessions(state => sessionCwd(state, sessionId))
  if (!chooserAvailable() || cwd === undefined) return null
  return (
    <button
      type="button"
      className={css.button}
      aria-label={LABEL}
      title={LABEL}
      onClick={() => {
        const result = openPathChooser(cwd, 'folder')
        if (!result.ok) {
          // P3-1/P3-6：`reason` 只进 detail 的诊断尾部（`data-*` 不可用时由调用方日志兜底），
          // 用户看到的正文一律由唯一真源翻译（旧文案把码直接印在括号里）。
          reportUserFacingResult({
            ok: false,
            title: '无法打开文件管理器',
            detail: describeCallReason(result.reason),
          })
        }
      }}
    >
      <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
        <path
          d="M1.75 4.25c0-.55.45-1 1-1h3.1c.3 0 .58.13.77.36l.86 1.03h5.77c.55 0 1 .45 1 1v6.11c0 .55-.45 1-1 1H2.75c-.55 0-1-.45-1-1V4.25Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.2"
          strokeLinejoin="round"
        />
      </svg>
    </button>
  )
}
