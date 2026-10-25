import test from 'node:test'
import assert from 'node:assert/strict'
import { isTaskSubmitted, detectBlocker, packageActivityRecords, hasNewTargetActivity, sfMappingForAlias, PRIVILEGE_STATUS_PROBE, readPrivilegeStatusFromPage, isKnownScreenScope, reconcileCreatedVdisplay, restoreScopeVerified, shouldDestroyVdisplay } from './verify-screen-scope-matrix.mjs'

test('scope restoration detects a setter that succeeds without changing the source of truth', async () => {
  let current = 'real-only'
  const result = await restoreScopeVerified('virtual-only', async () => true, async () => current)
  assert.deepEqual(result, {
    ok: false,
    actual: 'real-only',
    reason: 'scope readback mismatch: expected virtual-only, got real-only',
  })
})

test('scope restoration failure is surfaced instead of swallowed', async () => {
  const result = await restoreScopeVerified('all', async () => { throw new Error('bridge closed') }, async () => 'all')
  assert.equal(result.ok, false)
  assert.match(result.reason, /bridge closed/)
})

test('plain wire string readback is accepted only when it matches the original value', async () => {
  const result = await restoreScopeVerified('virtual-only', async () => true, async () => 'virtual-only')
  assert.deepEqual(result, { ok: true, actual: 'virtual-only' })
})

test('pre-existing virtual displays are never owned by this run; keep preserves a newly created one', () => {
  assert.equal(shouldDestroyVdisplay({ createdByRun: false, keep: false }), false)
  assert.equal(shouldDestroyVdisplay({ createdByRun: true, keep: true }), false)
  assert.equal(shouldDestroyVdisplay({ createdByRun: true, keep: false }), true)
})

test('an accepted create with a missing display id is marked inconclusive but still cleaned up', () => {
  const result = reconcileCreatedVdisplay({
    hadVirtualBefore: false,
    createReceipt: { ok: true, state: 'active', selected: 'virtual-1' },
    observedVirtual: { alias: 'virtual-1', displayId: 42 },
  })
  assert.deepEqual(result, { owned: true, receiptMatches: false })
  assert.equal(shouldDestroyVdisplay({ createdByRun: result.owned, keep: false }), true)
})

test('unknown original scopes fail closed instead of being normalized and written back', () => {
  assert.equal(isKnownScreenScope('all'), true)
  assert.equal(isKnownScreenScope('real-only'), true)
  assert.equal(isKnownScreenScope('virtual-only'), true)
  assert.equal(isKnownScreenScope('future-value'), false)
})

test('privilege state is read in the authenticated page without copying cookies to the host', async () => {
  const expected = { gates: { shizukuReady: true } }
  const result = await readPrivilegeStatusFromPage(async (expressions) => {
    assert.deepEqual(expressions, [PRIVILEGE_STATUS_PROBE])
    assert.match(expressions[0], /credentials: 'same-origin'/)
    assert.match(expressions[0], /fetch\('\/api\/android\/privilege\/status'/)
    return [{ status: 200, ok: true, body: expected }]
  })
  assert.deepEqual(result, expected)
})

test('an unauthorized or missing page response cannot pass the privilege probe', async () => {
  await assert.rejects(readPrivilegeStatusFromPage(async () => [{ status: 403, ok: false, body: null }]), /HTTP 403/)
  await assert.rejects(readPrivilegeStatusFromPage(async () => [{ __error: 'page gone' }]), /unavailable/)
})

test('busy, dirty, modal-blocked or missing composers cannot count as submitted model tasks', () => {
  for (const receipt of ['generation-active', 'composer-not-empty', 'dialog-open', 'no-composer', undefined, { __error: 'CDP rejected' }]) {
    assert.equal(isTaskSubmitted(receipt), false)
  }
  assert.equal(isTaskSubmitted('clicked'), true)
  assert.equal(isTaskSubmitted('enter'), true)
})

test('session device-control permission gate is reported as a blocker', () => {
  const detail = detectBlocker('会话档位是 workspace-write，所有设备控制操作都被门禁挡下。请先执行 /permission danger-full-access。')
  assert.match(detail, /会话权限档位阻止/)
  assert.equal(detectBlocker('任务已完成。'), '')
})

test('a pre-existing package ActivityRecord on the target display cannot prove this run launched it', () => {
  const dump = 'Display #0 (activities from top to bottom):\n'
    + 'Display #3 (activities from top to bottom):\n'
    + '  ActivityRecord{stable u0 com.example.settings/.Settings t3}\n'
    + 'Display #4 (activities from top to bottom):\n'
    + '  ActivityRecord{stable u0 com.example.settings/.Settings t4}\n'
    + 'Display #5 (activities from top to bottom):\n'
    + '  ActivityRecord{stable u0 com.example.settings/.Settings t5}\n'
  assert.deepEqual(packageActivityRecords('com.example.settings', dump), [
    { displayId: 3, id: 'stable' },
    { displayId: 4, id: 'stable' },
    { displayId: 5, id: 'stable' },
  ])
  assert.equal(hasNewTargetActivity('com.example.settings', 5, dump, dump), false)
})

test('a newly observed ActivityRecord on the target display proves a fresh target placement', () => {
  const before = 'Display #3 (activities from top to bottom):\n'
    + '  ActivityRecord{old u0 com.example.settings/.Settings t3}\n'
  const after = before + 'Display #5 (activities from top to bottom):\n'
    + '  ActivityRecord{new u0 com.example.settings/.Settings t5}\n'
  assert.equal(hasNewTargetActivity('com.example.settings', 5, before, after), true)
})

test('ActivityRecord package matching escapes dots and rejects prefix-similar packages', () => {
  const dump = 'Display #5 (activities from top to bottom):\n'
    + '  ActivityRecord{near1 u0 comXexampleYsettings/.Settings t5}\n'
    + '  ActivityRecord{near2 u0 com.example.settings.extra/.Settings t5}\n'
    + '  ActivityRecord{exact u0 com.example.settings/.Settings t5}\n'
  assert.deepEqual(packageActivityRecords('com.example.settings', dump), [
    { displayId: 5, id: 'exact' },
  ])
})

test('SurfaceFlinger evidence returns only the exact alias token pair', () => {
  const dump = 'Virtual Display 111\n    name="mumu"\n'
    + 'Virtual Display 222\n    name="DSH virtual-1"\n'
  assert.deepEqual(sfMappingForAlias(dump, 'virtual-1'), { token: '222', name: 'DSH virtual-1' })
  assert.equal(sfMappingForAlias(dump, 'virtual-2'), null)
})
