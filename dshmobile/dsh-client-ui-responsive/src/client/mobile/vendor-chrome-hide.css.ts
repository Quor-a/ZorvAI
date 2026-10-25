/**
 * Vendor-chrome hiding for the Android header (0.14.2 P5).
 *
 * The user reported two controls in the Session header as useless and asked for
 * them to go away (2026-09-26, verbatim): 「那个文件夹按钮完全无用，绿点也无用，这俩隐藏掉」.
 *
 * - The folder button is this plugin's own contribution, so it is retired at its
 *   registration site (`index.ts`), not here.
 * - The green dot belongs to the vendored `dsh-undo-savepoint` package
 *   (`dsh-mobile-apk/vendor/`, a pinned third-party copy). Editing vendor source
 *   would put this change into the mirror surface for no benefit, so it is
 *   hidden with one CSS override keyed on the vendor's own published attribute.
 *
 * The attribute selector is the vendor's own `data-undo-header` (published by
 * `vendor/dsh-undo-savepoint/lib/client.js`), never a hashed CSS-Module class
 * name: hashed names are unreachable from another package and would silently
 * stop matching on the next vendor bump.
 *
 * Scoped to no width query: this plugin only ever runs inside the Android shell,
 * and the dot is unwanted in both portrait and landscape. The 767px phone form
 * does NOT cover the landscape device (1600px wide), so a width-gated rule would
 * leave the dot visible there.
 */
export const VENDOR_CHROME_HIDE_CSS: string = `
/* The undo-savepoint status dot: no action, no readable state, removed by user
   request. Hidden rather than unmounted because the vendor owns the element. */
[data-undo-header] .u_dot {
  display: none !important;
}
`
