// Materializes lib/ from src/ for the Android injection chain, and bakes a build
// stamp so a page running an older bundle can notice and reload itself.
//
// The snapshot injector carries only `lib/**` plus package.json for @dsh-android
// packages, so a source-only package would arrive without code. Copying src/ into
// lib/ keeps the committed tree source-only while giving the chain something to
// inject; intra-package imports stay relative (`./session-delete.js`).
import { createHash } from 'node:crypto'
import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const lib = join(here, 'lib')
await rm(lib, { recursive: true, force: true })
await mkdir(lib, { recursive: true })
await cp(join(here, 'src'), lib, { recursive: true })

// Stamp = hash of the source client; the host serves it and the client compares.
const source = await readFile(join(here, 'src', 'client.js'), 'utf8')
const stamp = createHash('sha256').update(source).digest('hex').slice(0, 12)
await writeFile(join(lib, 'build.json'), JSON.stringify({ stamp }) + '\n')
const clientPath = join(lib, 'client.js')
const built = (await readFile(clientPath, 'utf8')).replace("'__BUILD_STAMP__'", "'" + stamp + "'")
await writeFile(clientPath, built)

console.log('dsh-session-delete: lib/ rebuilt from src/ (' + (await readdir(lib)).length + ' entries, stamp ' + stamp + ')')
