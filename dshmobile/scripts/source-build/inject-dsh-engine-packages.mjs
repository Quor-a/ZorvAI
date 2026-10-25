#!/usr/bin/env node
// Copy source-built first-party package payloads into the pnpm deploy tree
// without replacing pnpm's package symlinks or their sibling dependency links.
import { execFileSync } from 'node:child_process'
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { checkDshRuntimeDependencies } from './check-dsh-runtime-dependencies.mjs'

const [deployArg, cacheArg, overlayArg, reportArg] = process.argv.slice(2)
if (!deployArg || !cacheArg || !overlayArg || !reportArg) {
  console.error('usage: node inject-dsh-engine-packages.mjs <pnpm-deploy-root> <source-overlay-cache> <engine-overlay.json> <report.json>')
  process.exit(2)
}

const deployRoot = realpathSync(resolve(deployArg))
const cacheRoot = realpathSync(resolve(cacheArg))
const overlay = JSON.parse(readFileSync(resolve(overlayArg), 'utf8'))
const sourceManifestPath = join(cacheRoot, 'source-build-manifest.json')
const sourceManifest = JSON.parse(readFileSync(sourceManifestPath, 'utf8'))
const exported = new Map(sourceManifest.packages.map((item) => [`${item.name}@${item.version}`, item]))
const packages = Object.entries(overlay.packages ?? {})
  .filter(([name]) => name.startsWith('@deepseek-ai/'))
  .sort(([a], [b]) => a.localeCompare(b))
if (packages.length < 200) throw new Error(`expected the pinned first-party package set, found ${packages.length}`)

const within = (root, candidate) => {
  const rel = relative(root, candidate)
  return rel === '' || (rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel))
}
const installed = []
const packageRoot = join(deployRoot, 'node_modules')

for (const [name, version] of packages) {
  const packed = exported.get(`${name}@${version}`)
  if (!packed) throw new Error(`source-build manifest is missing ${name}@${version}`)
  const archive = resolve(cacheRoot, packed.file)
  if (!within(cacheRoot, archive) || !existsSync(archive)) throw new Error(`invalid or missing source package archive: ${packed.file}`)

  const target = resolve(packageRoot, ...name.split('/'))
  if (!within(deployRoot, target)) throw new Error(`package target escaped pnpm deploy tree: ${name}`)
  mkdirSync(dirname(target), { recursive: true })

  let physicalTarget = target
  let wasSymlink = false
  if (existsSync(target)) {
    const stat = lstatSync(target)
    if (stat.isSymbolicLink()) {
      wasSymlink = true
      physicalTarget = realpathSync(target)
      if (!within(deployRoot, physicalTarget)) throw new Error(`${name} pnpm link escapes the deploy tree: ${physicalTarget}`)
    } else if (!stat.isDirectory()) {
      throw new Error(`${name} deploy target is not a directory`)
    }
  } else {
    mkdirSync(target, { recursive: true })
  }

  for (const child of readdirSync(physicalTarget, { withFileTypes: true })) {
    if (child.name === 'node_modules') continue
    rmSync(join(physicalTarget, child.name), { recursive: true, force: true })
  }
  execFileSync('tar', ['-xzf', archive, '-C', physicalTarget, '--strip-components=1', '--no-same-owner'], { stdio: 'inherit' })

  if (wasSymlink && (!lstatSync(target).isSymbolicLink() || realpathSync(target) !== physicalTarget)) {
    throw new Error(`${name} pnpm deploy link changed while injecting its source-built package`)
  }
  const manifest = JSON.parse(readFileSync(join(target, 'package.json'), 'utf8'))
  if (manifest.name !== name || manifest.version !== version) {
    throw new Error(`${name} injected manifest mismatch: ${manifest.name}@${manifest.version}`)
  }
  installed.push({ name, version, deployPath: relative(deployRoot, target).replaceAll(sep, '/'), preservedPnpmLink: wasSymlink })
}

const dependencyCheck = checkDshRuntimeDependencies(deployRoot)

const report = {
  source: sourceManifest.source,
  sourceCommit: sourceManifest.commit,
  packageCount: installed.length,
  preservedPnpmLinkCount: installed.filter((item) => item.preservedPnpmLink).length,
  dependencyCount: dependencyCheck.dependencyCount,
  dependencyPresence: dependencyCheck.dependencyPresence,
  runtimeDependencyCheck: dependencyCheck,
  packages: installed,
}
writeFileSync(resolve(reportArg), JSON.stringify(report, null, 2) + '\n')
console.log(`injected ${installed.length} source-built first-party packages; preserved ${report.preservedPnpmLinkCount} pnpm links; found ${dependencyCheck.dependencyCount} required package links in the deploy tree`)
