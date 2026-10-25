#!/usr/bin/env node
// Adapter installed temporarily at scripts/check-engine-overlay.mjs by the
// ARM64 source audit workflow so build-apk keeps its normal gate invocation.
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'

const checker = resolve('scripts/source-build/check-dsh-source-snapshot.mjs')
execFileSync(process.execPath, [checker, ...process.argv.slice(2)], { stdio: 'inherit' })
