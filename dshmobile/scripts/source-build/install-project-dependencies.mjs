#!/usr/bin/env node
// CI resolves the stale transitive graph before npm ci, then records the actual lock bytes.
// Source-only adaptation updates declarations, not fabricated resolved graphs or hashes.
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { checkPackageLockRoot } from './check-package-lock-roots.mjs'

const [directoryArg, reportArg] = process.argv.slice(2)
if (!directoryArg || !reportArg || process.argv.length !== 4) {
  throw new Error('usage: node install-project-dependencies.mjs <package-directory> <lock-resolution-report.json>')
}
const directory = resolve(directoryArg)
const manifestPath = join(directory, 'package.json')
const lockPath = join(directory, 'package-lock.json')
if (!existsSync(manifestPath) || !existsSync(lockPath)) throw new Error('project package manifest/lock missing: ' + directoryArg)
const sha256 = file => createHash('sha256').update(readFileSync(file)).digest('hex')
const inputLockSha256 = sha256(lockPath)
const manifestSha256 = sha256(manifestPath)
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
// --ignore-scripts resolves metadata without executing lifecycle scripts; npm ci follows only this realized graph.
execFileSync(npm, ['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: directory, stdio: 'inherit' })
if (sha256(manifestPath) !== manifestSha256) throw new Error('lock-only resolution unexpectedly modified package.json')
const errors = checkPackageLockRoot(directory)
if (errors.length) throw new Error(errors.join('\n'))
const resolvedLockSha256 = sha256(lockPath)
execFileSync(npm, ['ci', '--no-audit', '--no-fund'], { cwd: directory, stdio: 'inherit' })
if (sha256(lockPath) !== resolvedLockSha256 || sha256(manifestPath) !== manifestSha256) {
  throw new Error('npm ci modified its resolved input manifest/lock')
}
const reportPath = resolve(reportArg)
const report = existsSync(reportPath) ? JSON.parse(readFileSync(reportPath, 'utf8')) : { packages: {} }
const key = relative(process.cwd(), directory).replaceAll('\\', '/')
report.packages[key] = {
  manifestSha256, inputLockSha256, resolvedLockSha256,
  lockfileVersion: JSON.parse(readFileSync(lockPath, 'utf8')).lockfileVersion,
  resolution: 'npm install --package-lock-only --ignore-scripts; npm ci',
  nodeVersion: process.version,
}
mkdirSync(dirname(reportPath), { recursive: true })
writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n')
console.log('resolved project lock: ' + key + ' ' + resolvedLockSha256)
