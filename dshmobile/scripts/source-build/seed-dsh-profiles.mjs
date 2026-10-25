#!/usr/bin/env node
// Seed only the shipped profile templates from the pinned DSH TypeScript source.
import { pathToFileURL } from 'node:url'
import { resolve, join } from 'node:path'

const [sourceRootArg, dshHomeArg] = process.argv.slice(2)
if (!sourceRootArg || !dshHomeArg) {
  console.error('usage: seed-dsh-profiles.mjs <deepseek-harness-source-root> <dsh-home>')
  process.exit(2)
}
const sourceRoot = resolve(sourceRootArg)
const dshHome = resolve(dshHomeArg)
const source = pathToFileURL(join(sourceRoot, 'packages/boot/app-boot/src/profile.ts')).href
const { initProfile, PROFILE_TEMPLATES } = await import(source)
for (const name of ['web', 'headless']) {
  const template = PROFILE_TEMPLATES[name]
  if (!template) throw new Error(`pinned DSH source does not define the ${name} profile`)
  initProfile(join(dshHome, 'profiles', name), template.bundles, 'startup')
}
console.log('profile manifests generated from pinned DeepSeek Harness source: web, headless')
