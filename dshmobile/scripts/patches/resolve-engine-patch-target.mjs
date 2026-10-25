/** Resolve unstable generated chunk names by exact package path and reviewed source ownership. */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'

/** Artifact entries use stage-relative paths; missing or duplicate owner chunks fail loudly. */
export function resolveEnginePatchTarget(patch, entries) {
  const rule = patch.targetDiscovery
  if (rule === undefined) return patch.target
  if (rule.directory !== dirname(patch.target).replaceAll('\\', '/') ||
      typeof rule.filenamePrefix !== 'string' || !Array.isArray(rule.contains) || !rule.contains.length) {
    throw new Error('invalid target discovery: ' + patch.id)
  }
  const prefix = rule.directory + '/'
  const matches = [...entries].filter(([path, text]) => path.startsWith(prefix) &&
    !path.slice(prefix.length).includes('/') && path.slice(prefix.length).startsWith(rule.filenamePrefix) &&
    path.endsWith('.js') && rule.contains.every(anchor => text.includes(anchor))).map(([path]) => path)
  if (matches.length !== 1) throw new Error('expected one reviewed owner chunk for ' + patch.id + ', got ' + matches.length)
  return matches[0]
}

/** Read only ordinary files in the declared package lib; never discover across packages. */
export function resolveEnginePatchFile(stageRoot, patch) {
  if (!patch.targetDiscovery) return patch.target
  const directory = patch.targetDiscovery.directory
  const entries = readdirSync(join(stageRoot, directory), { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.js'))
    .map(entry => { const path = directory + '/' + entry.name; return [path, readFileSync(join(stageRoot, path), 'utf8')] })
  return resolveEnginePatchTarget(patch, entries)
}
