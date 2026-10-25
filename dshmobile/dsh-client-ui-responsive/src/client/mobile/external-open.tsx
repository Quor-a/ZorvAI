/**
 * "Open with" tab body: what the right Sidebar shows for a file no preview can
 * render (archives, packages, binaries, installers). The type's claims live in
 * `external-open-paths.ts`; this file is only the card and its two gestures.
 *
 * The body reads no file content: it names the file and hands the absolute
 * device path to the shell's native chooser, so opening a 200 MB archive costs
 * nothing.
 */
import { useState } from 'react'
import type { PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import { parseFileAddress, resolveAbsolutePath } from './address.ts'
import { basenameOf } from './external-open-paths.ts'
import { chooserAvailable, openPathChooser } from './open-path.ts'
import { sessionCwd } from './session-cwd.ts'
import { reportUserFacingResult } from '../export-result.ts'
import css from './ExternalOpen.module.css'

/** The directory holding a path (the chooser's `folder` target). */
function parentDirectory(path: string): string {
  const cut = path.replace(/\/+$/, '').lastIndexOf('/')
  return cut <= 0 ? path : path.slice(0, cut)
}

/** One action's failure text, shown in the card and in the shared result dialog. */
type Failure = { readonly title: string; readonly detail: string }

/**
 * The tab body: name the file, then hand it to the system chooser.
 * @param props - the session-scoped tab share (runtime hooks + the tab reader).
 * @returns the card, or an explanation when this host cannot open paths.
 */
export function ExternalOpenTab({ sessionId, useSessions, useTabInfo }: PropsRuntime<'sidebar.right.pane.tab'>) {
  const info = useTabInfo()
  const cwd = useSessions(state => sessionCwd(state, sessionId))
  const address = info.tab.navigation.address
  const parsed = parseFileAddress(address)
  const absolute = parsed === undefined ? undefined : resolveAbsolutePath(parsed, cwd)
  const [failure, setFailure] = useState<Failure | null>(null)

  const hand = (target: string, mode: 'view' | 'folder'): void => {
    const result = openPathChooser(target, mode)
    if (result.ok) {
      setFailure(null)
      return
    }
    const next: Failure = result.reason === 'no-handler'
      ? { title: '没有可用的文件管理器', detail: '设备上没有能打开该路径的应用，可先安装 MT 管理器。' }
      : { title: '打开失败', detail: `调用系统选择器失败（${result.reason ?? 'unknown'}）。` }
    setFailure(next)
    reportUserFacingResult({ ok: false, ...next })
  }

  return (
    <div className={css.card}>
      <p className={css.name}>{basenameOf(address)}</p>
      <p className={css.path}>{absolute ?? address}</p>
      <p className={css.hint}>该格式没有内置预览，可交给设备上的应用打开。</p>
      {chooserAvailable() && absolute !== undefined
        ? (
          <div className={css.actions}>
            <button type="button" className={css.primary} onClick={() => { hand(parentDirectory(absolute), 'folder') }}>
              打开所在文件夹
            </button>
            <button type="button" className={css.secondary} onClick={() => { hand(absolute, 'view') }}>
              用其它应用打开
            </button>
          </div>
        )
        : (
          <p className={css.hint}>{absolute === undefined ? '无法确定该文件的设备路径。' : '当前环境不支持调用系统应用（请在安卓应用内打开）。'}</p>
        )}
      {failure !== null && <p className={css.failure}>{failure.title}：{failure.detail}</p>}
    </div>
  )
}
