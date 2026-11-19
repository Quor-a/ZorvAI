/** Retain the exact official 0.2.2-rc.5 pi-ai streaming patch after raw npm overlay extraction. */
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const OFFICIAL_PI_PATCH_SHA256 = '4e1c90fd00aea42989664504668633033c1962e53c07860901edb4f72d843765'
export const PI_AI_VERSION = '1.3.0'
export const PI_STREAMING_FILES = Object.freeze([
  'dist/api/anthropic-messages.js', 'dist/api/bedrock-converse-stream.js',
  'dist/api/mistral-conversations.js', 'dist/api/openai-completions.js',
  'dist/api/openai-responses-shared.js', 'dist/api/pi-messages.js',
])
const PATCH = join(dirname(fileURLToPath(import.meta.url)), 'upstream', 'pi-ai-1.3.0.patch')

/**
 * Parse one `diff --git` section into ordered hunks. Each hunk is {before, after, indexLine}
 * with exact context (no fuzzy matching). A section may contain multiple @@ hunks (pi-ai
 * 1.3.0 openai-completions.js has three).
 */
function parseSection(section) {
  const lines = section.split('\n')
  const header = /^a\/(\S+) b\/(\S+)$/.exec(lines[0])
  if (!header || header[1] !== header[2]) throw new Error('malformed pi patch diff header')
  const file = header[1]
  const hunkStarts = lines.flatMap((line, i) => line.startsWith('@@ ') ? [i] : [])
  if (!hunkStarts.length) throw new Error('pi patch section has no hunk: ' + file)
  const hunks = hunkStarts.map((start, idx) => {
    const end = idx + 1 < hunkStarts.length ? hunkStarts[idx + 1] : lines.length
    const body = lines.slice(start + 1, end)
    while (body.length && body[body.length - 1] === '') body.pop()
    if (body.some(line => line && !/^[ +\-]/.test(line))) {
      throw new Error('invalid pi patch hunk context in ' + file)
    }
    const before = body.filter(line => line[0] !== '+').map(line => line.slice(1)).join('\n') + '\n'
    const after = body.filter(line => line[0] !== '-').map(line => line.slice(1)).join('\n') + '\n'
    return { before, after }
  })
  return { file, hunks }
}

/**
 * Apply all hunks of a section to the original text, in order. Tolerates already-patched
 * sections (every hunk already present in `after` form) for idempotency. Drift throws.
 */
function applySection(original, section) {
  let text = original
  let alreadyApplied = 0
  for (const hunk of section.hunks) {
    const rawMatches = text.split(hunk.before).length - 1
    const patchedMatches = text.split(hunk.after).length - 1
    if (rawMatches === 1 && patchedMatches === 0) {
      text = text.replace(hunk.before, hunk.after)
    } else if (rawMatches === 0 && patchedMatches === 1) {
      alreadyApplied++
    } else {
      throw new Error(`pi patch context drift in ${section.file}: before=${rawMatches} after=${patchedMatches}`)
    }
  }
  if (alreadyApplied !== 0 && alreadyApplied !== section.hunks.length) {
    throw new Error('pi patch partially applied (mixed hunk states) in ' + section.file)
  }
  return { text, applied: alreadyApplied === 0 }
}

/**
 * Prepare all file replacements before any write; exact upstream context, no fuzzy patching.
 * @param packageRoot - extracted pi-ai package root (disposable build stage).
 * @param read - stage reader, including the unified runner's cached prior patches.
 * @returns complete idempotent replacement plan; drift/malformed patch/version throws.
 */
export function planPiStreaming(packageRoot, read = file => readFileSync(join(packageRoot, file), 'utf8')) {
  const manifest = JSON.parse(read('package.json'))
  if (manifest.name !== '@earendil-works/pi-ai' || manifest.version !== PI_AI_VERSION) {
    throw new Error(`pi-upstream-streaming-020 requires @earendil-works/pi-ai@${PI_AI_VERSION}`)
  }
  const bytes = readFileSync(PATCH)
  if (createHash('sha256').update(bytes).digest('hex') !== OFFICIAL_PI_PATCH_SHA256) {
    throw new Error('official pi-ai streaming patch SHA-256 mismatch')
  }
  const sections = bytes.toString('utf8').split(/^diff --git /m).slice(1).map(parseSection)
  if (sections.length !== PI_STREAMING_FILES.length) {
    throw new Error('official pi-ai patch section count mismatch')
  }
  const byFile = new Map(sections.map(s => [s.file, s]))
  const plans = PI_STREAMING_FILES.map(file => {
    const section = byFile.get(file)
    if (!section) throw new Error('official pi-ai patch missing target: ' + file)
    const original = read(file)
    const { text } = applySection(original, section)
    return { file, before: original, after: text }
  })
  return plans
}
