/**
 * DSH Mobile settings client for the experimental Linux environment.
 *
 * This section is only reachable when the Linux bootstrap plugin is present and
 * enabled. The plugins ship in the built-in (hard) plugin directory but are not
 * enabled by default: presence is not activation, so the section appears only
 * after the user turns the system on.
 *
 * Everything here is opt-in. Nothing in this UI enables the Linux runtime on its
 * own: installing, reinstalling and uninstalling all go through explicit,
 * confirmed actions on the engine service.
 */
import React, { useEffect, useState } from 'react'
import { EXPERIMENTAL_NOTICE, autoUpdateAvailable, describeVersionStatus } from '../src/status.js'

export const name = 'dsh-linux-bootstrap-client'
// `slots` is declared next to the service the section renders from. The DSH
// Mobile host gates slot access on the declared injection set, and its own
// client plugins declare it too (`dsh-client-ui-responsive`: `slots`, `theme`,
// ...), so a client that calls `ctx.slots` without declaring it can end up
// with no slot registry at all.
export const inject = ['slots', 'linuxBootstrap']

const h = React.createElement

function ReleaseLink({ label, url }) {
  if (typeof url !== 'string' || !url.startsWith('https://github.com/')) return null
  return h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, `${label} Release`)
}

export function LinuxEnvironmentSection({ linuxBootstrap }) {
  const [status, setStatus] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [runtimeId, setRuntimeId] = useState('debian-minimal')

  useEffect(() => {
    if (!linuxBootstrap) {
      setError('Linux bootstrap 服务不可用：插件未注入')
      return undefined
    }
    let active = true
    const update = (next) => {
      if (active) setStatus(next)
    }
    let unsubscribe
    try {
      unsubscribe = linuxBootstrap.subscribe(update)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    }
    Promise.resolve(linuxBootstrap.status()).then(update).catch((cause) => {
      if (active) setError(cause instanceof Error ? cause.message : String(cause))
    })
    return () => {
      active = false
      if (typeof unsubscribe === 'function') unsubscribe()
      else unsubscribe?.unsubscribe?.()
    }
  }, [linuxBootstrap])

  const run = async (action) => {
    setBusy(true)
    setError('')
    try {
      const next = await action()
      if (next && typeof next === 'object' && 'installed' in next) setStatus(next)
      else setStatus(await linuxBootstrap.status())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  const installed = Boolean(status?.installed)
  const autoUpdateEnabled = status?.autoUpdate ?? false
  const urls = status?.pluginReleaseUrls ?? {}
  const runtimes = Array.isArray(status?.runtimes) ? status.runtimes : []
  const selectedRuntime = status?.installed && status?.runtime?.id ? status.runtime.id : runtimeId
  const confirmInstall = () =>
    window.confirm(
      '安装 Linux 环境会下载 Bionilux、Debian minimal rootfs 与共享 glibc Node.js（数百 MB），' +
        '并在应用私有目录中启用运行时插件。是否继续？',
    )
  const confirmRemove = () => window.confirm('卸载会删除已安装的 Linux 运行时与其中的软件包和文件，是否继续？')

  return h(
    'section',
    { className: 'linux-environment-settings', 'aria-label': 'Linux环境', 'data-experimental': 'true' },
    h('h2', null, 'Linux环境'),
    h('p', { className: 'linux-environment-notice' }, EXPERIMENTAL_NOTICE),
    h('p', { role: 'status', 'aria-live': 'polite' }, describeVersionStatus(status)),
    runtimes.length > 1
      ? h(
          'label',
          { className: 'linux-environment-runtime' },
          'Linux 运行时：',
          h(
            'select',
            {
              value: selectedRuntime,
              disabled: busy || installed,
              onChange: (event) => setRuntimeId(event.currentTarget.value),
            },
            runtimes.map((runtime) =>
              h('option', { key: runtime.id, value: runtime.id }, `${runtime.label}（${runtime.architecture}）`),
            ),
          ),
        )
      : null,
    h(
      'label',
      null,
      h('input', {
        type: 'checkbox',
        checked: Boolean(autoUpdateEnabled),
        disabled: busy || !linuxBootstrap || !autoUpdateAvailable(status),
        onChange: (event) => run(() => linuxBootstrap.setAutoUpdateEnabled(event.currentTarget.checked)),
      }),
      '自动更新（仅在已启用的 Linux 环境的 DSH Mobile 版本变化后检查并安装较新的 Linux 运行时）',
    ),
    h(
      'div',
      { className: 'linux-environment-actions' },
      h(
        'button',
        {
          type: 'button',
          disabled: busy || !linuxBootstrap,
          onClick: () => run(() => linuxBootstrap.checkForUpdates({ source: 'manual' })),
        },
        '手动检查更新',
      ),
      h(
        'button',
        {
          type: 'button',
          disabled: busy || !linuxBootstrap,
          onClick: () => {
            if (!confirmInstall()) return undefined
            return run(() => linuxBootstrap.install({ runtimeId: selectedRuntime, confirmed: true, activate: true }))
          },
        },
        installed ? '安装 Linux 更新' : '安装 Linux 环境',
      ),
      h(
        'button',
        {
          type: 'button',
          disabled: busy || !linuxBootstrap || !installed,
          onClick: () => {
            if (!confirmInstall()) return undefined
            return run(() => linuxBootstrap.reinstall({ confirmed: true, activate: true }))
          },
        },
        '重新安装',
      ),
      h(
        'button',
        {
          type: 'button',
          disabled: busy || !linuxBootstrap || !installed,
          onClick: () => {
            if (!confirmRemove()) return undefined
            return run(() => linuxBootstrap.uninstall({ confirmed: true }))
          },
        },
        '卸载 Linux 环境',
      ),
    ),
    h(
      'p',
      { className: 'linux-environment-links' },
      '插件下载页：',
      ReleaseLink({ label: '引导插件', url: urls.bootstrap }),
      ' ',
      ReleaseLink({ label: '运行时插件', url: urls.runtime }),
    ),
    error ? h('p', { role: 'alert' }, error) : null,
  )
}

export function apply(ctx, config = {}) {
  const linuxBootstrap = ctx.linuxBootstrap
  if (!linuxBootstrap) throw new Error('The linuxBootstrap service injection is required')
  const order = Number.isFinite(config.order) ? config.order : 80
  const Section = (props) => h(LinuxEnvironmentSection, { ...props, linuxBootstrap })
  const register = () => ctx.slots.inject('settings.section', () => ctx.slots.register({
    name: 'settings.section',
    id: 'linux-environment',
    order,
    label: () => 'Linux环境',
  }, Section))
  // The slot registry hands back a disposer; routing it through the context
  // removes the section on reload or uninstall instead of leaking it.
  if (typeof ctx.effect === 'function') ctx.effect(register)
  else register()
}

export default { name, inject, apply, LinuxEnvironmentSection }
