#!/usr/bin/env node
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const VERSION_PATTERN = /^\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?$/
const SUFFIX_PATTERN = /^-[A-Za-z0-9][A-Za-z0-9.-]*$/

export function resolveVersionSuffix(baseVersion, requestedVersion = '', requestedSuffix = '') {
  if (!VERSION_PATTERN.test(baseVersion)) throw new Error(`invalid Gradle base version: ${baseVersion}`)
  if (requestedSuffix && !SUFFIX_PATTERN.test(requestedSuffix)) {
    throw new Error(`version suffix must start with '-' and contain only letters, digits, dots, or dashes: ${requestedSuffix}`)
  }

  if (requestedSuffix) {
    const resolvedVersion = `${baseVersion}${requestedSuffix}`
    if (requestedVersion && requestedVersion !== baseVersion && requestedVersion !== resolvedVersion) {
      throw new Error(`requested version ${requestedVersion} does not equal base ${baseVersion} or base+suffix ${resolvedVersion}`)
    }
    return { version: resolvedVersion, suffix: requestedSuffix }
  }

  if (!requestedVersion || requestedVersion === baseVersion) return { version: baseVersion, suffix: '' }
  const prefix = `${baseVersion}-`
  if (requestedVersion.startsWith(prefix)) {
    const suffix = requestedVersion.slice(baseVersion.length)
    if (SUFFIX_PATTERN.test(suffix)) return { version: requestedVersion, suffix }
  }
  throw new Error(`requested version ${requestedVersion} must equal Gradle base ${baseVersion} or append an explicit suffix`)
}

function main(args) {
  const options = { base: '', version: '', suffix: '' }
  for (let i = 0; i < args.length; i += 1) {
    const key = args[i]
    if (key === '--base') options.base = args[++i] ?? ''
    else if (key === '--version') options.version = args[++i] ?? ''
    else if (key === '--suffix') options.suffix = args[++i] ?? ''
    else if (key === '--help' || key === '-h') {
      console.log('Usage: node scripts/resolve-version-suffix.mjs --base VERSION [--version VERSION] [--suffix -suffix]')
      return 0
    } else throw new Error(`unknown argument: ${key}`)
  }
  const result = resolveVersionSuffix(options.base, options.version, options.suffix)
  process.stdout.write(`${JSON.stringify(result)}\n`)
  return 0
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main(process.argv.slice(2))
  } catch (error) {
    console.error(`version resolution failed: ${error.message}`)
    process.exitCode = 1
  }
}
