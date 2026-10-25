/**
 * Copy for our own injected surfaces.
 *
 * Upstream owns `ctx.locale`, but most of our UI is imperative DOM built outside
 * a slot, so it cannot receive the `t` seat. The engine keeps
 * `<html lang>` in sync with the active locale, and a language change reloads
 * this page, so reading that attribute at call time is enough.
 */

/**
 * True when the active dsh locale is English.
 *
 * Chinese stays the default: an empty `<html lang>` (tests, hosts that have not
 * synchronized the attribute yet) must not flip every surface to English.
 */
export function isEnglish(): boolean {
  try {
    return document.documentElement.lang.toLowerCase().startsWith('en')
  } catch {
    return false
  }
}

/** Pick Chinese or English copy for the active locale. */
export function tr(zh: string, en: string): string {
  return isEnglish() ? en : zh
}

/**
 * Candidate labels for an upstream control we match by text.
 *
 * Upstream localizes its own copy, so a claimed action must accept both
 * languages regardless of the active one.
 */
export function both(zh: string, en: string): string[] {
  return [zh, en]
}
