/** Language-mode helpers shared by the settings row and the engine bridge. */

/** Three selectable modes; stored values match the shell's preference. */
export type UiLanguageMode = 'system' | 'zh' | 'en'

/** Ordered modes for the settings menu. */
export const UI_LANGUAGE_MODES: readonly UiLanguageMode[] = ['system', 'zh', 'en']

/** Narrow unknown bridge output to a declared mode. */
export function asUiLanguageMode(value: unknown): UiLanguageMode {
  return value === 'zh' || value === 'en' ? value : 'system'
}

/** Read the shell preference; hosts without the bridge follow the page language. */
export function readUiLanguage(): UiLanguageMode {
  try {
    const fromBridge = window.androidBridge?.getUiLanguage?.()
    if (fromBridge !== undefined) return asUiLanguageMode(fromBridge)
  } catch {
    // Bridge absent or threw: fall back to the browser preference below.
  }
  // Hosts without the shell bridge: follow the page language, which is what
  // dsh already resolved. An absent language stays neutral instead of
  // reporting a choice the user never made.
  try {
    const lang = document.documentElement.lang.toLowerCase()
    if (lang.startsWith('zh')) return 'zh'
    if (lang.startsWith('en')) return 'en'
    return 'system'
  } catch {
    return 'system'
  }
}

/** dsh locale implied by a mode, using the page language when following the system. */
export function dshLocaleFor(mode: UiLanguageMode): 'zh' | 'en' {
  if (mode === 'zh' || mode === 'en') return mode
  try {
    return navigator.language.toLowerCase().startsWith('zh') ? 'zh' : 'en'
  } catch {
    return 'en'
  }
}
