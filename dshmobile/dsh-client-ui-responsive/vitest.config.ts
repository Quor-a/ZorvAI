import { defineConfig } from 'vitest/config'

/**
 * Vendored engine packages (node_modules/@deepseek-ai/*) ship compiled `lib/index.js` files whose
 * top-level imports include bare `./X.module.css` / `./Y.css`. Vitest externalizes node_modules by
 * default, so Node's own ESM loader resolves those files and dies with:
 *
 *   TypeError: Unknown file extension ".css" for ...\dsh-client-ui-primitives\lib\StateDot.module.css
 *
 * Inlining the scoped engine packages routes them through Vite's pipeline instead, where CSS imports
 * are stubbed for tests. Scoped to @deepseek-ai/* only: our own `src/**` tests keep default behavior.
 *
 * Why this file exists at all: the 0.14.3 browser/panel specs are the first tests in this package to
 * import engine packages that carry CSS, so the gap surfaced only once the build gate ran them.
 */
export default defineConfig({
  test: {
    server: { deps: { inline: [/@deepseek-ai\//] } },
  },
})
