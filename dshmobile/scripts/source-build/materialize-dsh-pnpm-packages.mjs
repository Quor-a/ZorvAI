#!/usr/bin/env node
// Give checked-in snapshot scanners regular top-level package files while
// preserving pnpm's physical dependency directories and every reverse link.
import { readFileSync, existsSync, lstatSync, mkdirSync, readdirSync, realpathSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'

const [engineRootArg, sourceManifestArg, reportArg] = process.argv.slice(2)
if (!engineRootArg || !sourceManifestArg || !reportArg) {
  console.error('usage: node materialize-dsh-pnpm-packages.mjs <engine-root> <source-build-manifest.json> <report.json>')
  process.exit(2)
}

const engineRoot = realpathSync(resolve(engineRootArg))
const sourceManifest = JSON.parse(readFileSync(resolve(sourceManifestArg), 'utf8'))
const reportPath = resolve(reportArg)
const within = (root, candidate) => {
  const rel = relative(root, candidate)
  return rel === '' || (rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel))
}
const entries = sourceManifest.packages.filter((item) => item.name.startsWith('@deepseek-ai/') && item.name !== '@deepseek-ai/dsh')
if (entries.length < 266) throw new Error(`expected at least 266 pinned first-party workspace packages, found ${entries.length}`)

function materializeDependencyLookup(sourceDirs, destinationDir) {
  mkdirSync(destinationDir)
  const names = new Set(sourceDirs.flatMap((dir) => readdirSync(dir)))
  for (const name of names) {
    const candidates = sourceDirs
      .map((dir) => join(dir, name))
      .filter((candidate) => existsSync(candidate))
    if (!candidates.length) continue
    const isScopeDirectory = name.startsWith('@') && candidates.every((candidate) => {
      const stat = lstatSync(candidate)
      return stat.isDirectory() && !stat.isSymbolicLink()
    })
    const destination = join(destinationDir, name)
    if (isScopeDirectory) {
      materializeDependencyLookup(candidates, destination)
      continue
    }
    const preferred = candidates[0]
    const resolved = realpathSync(preferred)
    if (!within(engineRoot, resolved)) throw new Error(`dependency link escapes the engine tree: ${preferred}`)
    const type = lstatSync(preferred).isDirectory() ? 'dir' : 'file'
    symlinkSync(relative(destinationDir, preferred), destination, type)
  }
}

const materialized = []
for (const item of entries) {
  const target = join(engineRoot, 'node_modules', ...item.name.split('/'))
  if (!existsSync(join(target, 'package.json'))) throw new Error(`missing deployed source package ${item.name}@${item.version}`)
  const before = JSON.parse(readFileSync(join(target, 'package.json'), 'utf8'))
  if (before.name !== item.name || before.version !== item.version) {
    throw new Error(`${item.name} deploy identity mismatch: ${before.name}@${before.version}, expected ${item.version}`)
  }

  const targetStat = lstatSync(target)
  if (!targetStat.isSymbolicLink()) {
    if (!targetStat.isDirectory()) throw new Error(`${item.name} top-level target is not a directory`)
    materialized.push({ name: item.name, version: item.version, alreadyMaterialized: true })
    continue
  }

  const physicalTarget = realpathSync(target)
  if (!within(engineRoot, physicalTarget) || physicalTarget === target) {
    throw new Error(`${item.name} pnpm target escapes the engine tree or is self-referential`)
  }
  const linkBackup = join(dirname(target), `.${basename(target)}.source-link-backup-${process.pid}`)
  if (existsSync(linkBackup)) throw new Error(`materialization backup already exists: ${linkBackup}`)
  const packageNodeModules = join(physicalTarget, 'node_modules')
  // Node searches a package-local node_modules first, then the node_modules
  // directory beside its pnpm virtual-store package path.
  const pnpmPeerNodeModules = dirname(dirname(physicalTarget))
  const physicalDependencyLookupRoots = [...new Set([packageNodeModules, pnpmPeerNodeModules]
    .filter(existsSync)
    .map((candidate) => realpathSync(candidate)))]
  if (!physicalDependencyLookupRoots.length
    || physicalDependencyLookupRoots.some((candidate) => !within(engineRoot, candidate))) {
    throw new Error(`${item.name} dependency lookup roots are missing or escape the engine tree`)
  }

  renameSync(target, linkBackup)
  mkdirSync(target)
  materializeDependencyLookup(physicalDependencyLookupRoots, join(target, 'node_modules'))

  const movedEntries = []
  for (const child of readdirSync(physicalTarget, { withFileTypes: true })) {
    if (child.name === 'node_modules') continue
    const source = join(physicalTarget, child.name)
    const destination = join(target, child.name)
    renameSync(source, destination)
    symlinkSync(relative(dirname(source), destination), source, child.isDirectory() ? 'dir' : 'file')
    movedEntries.push(child.name)
  }
  rmSync(linkBackup, { force: true })

  const after = JSON.parse(readFileSync(join(target, 'package.json'), 'utf8'))
  if (after.name !== item.name || after.version !== item.version) {
    throw new Error(`${item.name} materialized identity changed: ${after.name}@${after.version}`)
  }
  materialized.push({
    name: item.name,
    version: item.version,
    materializedRegularFiles: true,
    preservedPnpmDependencyDirectories: physicalDependencyLookupRoots.map((root) => relative(engineRoot, root).replaceAll(sep, '/')),
    movedTopLevelEntries: movedEntries,
    physicalStorePath: relative(engineRoot, physicalTarget).replaceAll(sep, '/'),
  })
}

const report = {
  sourceCommit: sourceManifest.commit,
  packageCount: materialized.length,
  convertedSymlinkCount: materialized.filter((item) => item.materializedRegularFiles).length,
  preservedPnpmNodeModulesLinkCount: materialized.filter((item) => item.preservedPnpmDependencyDirectories?.length).length,
  packages: materialized,
  purpose: 'Expose source-built package payload files at their normal node_modules paths for snapshot scanners. The original .pnpm package payloads are replaced by relative links to the top-level files, while each top-level package gets a merged node_modules lookup tree that preserves package-local precedence and falls back to its original pnpm virtual-store links.',
}
mkdirSync(dirname(reportPath), { recursive: true })
writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n')
console.log(`materialized ${report.convertedSymlinkCount} first-party package links; preserved ${report.preservedPnpmNodeModulesLinkCount} pnpm dependency directories`)
