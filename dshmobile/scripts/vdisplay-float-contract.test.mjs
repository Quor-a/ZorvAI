import test from 'node:test'
import assert from 'node:assert/strict'
import { booleanReadbackMatches } from './vdisplay-float-contract.mjs'

test('false is a successful Boolean setter result when authoritative readback is false', () => {
  assert.equal(booleanReadbackMatches(false, false), true)
})

test('Boolean setter readback rejects a state that did not converge', () => {
  assert.equal(booleanReadbackMatches(false, true), false)
  assert.equal(booleanReadbackMatches(true, false), false)
})

test('Boolean setter readback rejects non-Boolean values', () => {
  assert.equal(booleanReadbackMatches(false, null), false)
  assert.equal(booleanReadbackMatches(true, 'true'), false)
})
