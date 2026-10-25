// Materializes lib/ from the vendored src/ tree for the Android injection chain.
//
// The snapshot injector only carries `lib/**` plus package.json for @dsh-android packages
// (scripts/inject-all.py build_dsh_replacements), so a source-only package would arrive without
// code. Copying src/ into lib/ keeps the committed tree source-only (no build artifacts in git)
// while giving the chain something to inject. Intra-package imports stay relative and flat
// (`./version.js` etc.), so a flat copy preserves them exactly.
import { cp, mkdir, readdir, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const lib = join(here, 'lib')
await rm(lib, { recursive: true, force: true })
await mkdir(lib, { recursive: true })
await cp(join(here, 'src'), lib, { recursive: true })
console.log('dsh-linux-bootstrap: lib/ rebuilt from src/ (' + (await readdir(lib)).length + ' entries)')
