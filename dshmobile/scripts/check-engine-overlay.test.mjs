import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const source = readFileSync(new URL('./check-engine-overlay.mjs', import.meta.url), 'utf8')
const registry = JSON.parse(readFileSync(new URL('./patches/registry.json', import.meta.url), 'utf8'))
const patch = registry.patches.find(p => p.id === 'client-registry-scan-C4')
// Execute the gate's actual metadata and assertion blocks, not a duplicate implementation.
const mapStart = source.indexOf('    const marker = String(patch.targetMarkers')
const mapEnd = source.indexOf('\n  }\n  if (patch.verifier)', mapStart)
const checkStart = source.indexOf('    if (!meta.verifier && !content.includes(meta.marker))')
const checkEnd = source.indexOf('\n    checked++', checkStart)
assert.ok(mapStart > 0 && mapEnd > mapStart && checkStart > 0 && checkEnd > checkStart)
const metadataCode = source.slice(mapStart, mapEnd)
const assertionCode = source.slice(checkStart, checkEnd)
function metadata(patch, path, want) { runInNewContext(metadataCode, { patch, path, want }); return want.get(path) }
function failures(meta, content) { const fails = []; runInNewContext(assertionCode, { meta, content, fails }); return fails }

test('artifact gate rejects old C4-only bytes while keeping both markers mandatory', () => {
  assert.deepEqual(patch.requiredMarkers, ['dsh-mobile gateway client source G1'])
  const want = new Map(), meta = metadata(patch, patch.target, want)
  assert.equal(failures(meta, patch.marker).length, 1)
  assert.match(failures(meta, patch.marker)[0], /gateway client source G1/)
  assert.match(failures(meta, patch.requiredMarkers[0])[0], /registry scan C4/)
  assert.equal(failures(meta, patch.marker + '\n' + patch.requiredMarkers[0]).length, 0)
})

test('later companion metadata cannot erase main required markers', () => {
  const want = new Map()
  metadata(patch, patch.target, want)
  const companion = { id: 'companion', target: 'other.js', marker: 'companion-marker', verifier: 'exact-verifier', requiredMarkers: ['only-for-other-target'] }
  const meta = metadata(companion, patch.target, want)
  assert.deepEqual(Array.from(meta.requiredMarkers), patch.requiredMarkers)
  assert.match(failures(meta, 'companion-marker')[0], /gateway client source G1/)
  assert.equal(failures(meta, 'companion-marker\n' + patch.requiredMarkers[0]).length, 0)
})
