import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { readSnapshotRouteFiles, snapshotRouteVerdicts } from './lib/snapshot-route-auth.mjs'

const markers = [['dsh-undo-savepoint/lib/index.js', 'UNDO_AUTH'], ['active/lib/index.js', 'ACTIVE_AUTH']]
const retired = new Set(['dsh-undo-savepoint'])
const base = 'home/.dsh/profiles/web/'
const fixture = (patch = '- id: active\n  name: active\n') => ({
  [base + 'cordis.patch.yml']: patch,
  [base + 'node_modules/active/lib/index.js']: 'ACTIVE_AUTH',
})
const accepted = files => snapshotRouteVerdicts(files, markers, retired, ['web']).every(v => v.ok)

test('explicitly retired unmounted package may be absent; active marker remains required', () => {
  assert.equal(accepted(fixture()), true)
})
test('negative: remounted retired package cannot be absent', () => {
  assert.equal(accepted(fixture('- id: undo-savepoint\n  name: dsh-undo-savepoint\n')), false)
})
test('negative: a missing active member or profile patch still rejects', () => {
  const missing = fixture(); delete missing[base + 'node_modules/active/lib/index.js']
  assert.equal(accepted(missing), false)
  const patch = fixture(); delete patch[base + 'cordis.patch.yml']
  assert.equal(accepted(patch), false)
})
test('negative: another file cannot supply this member auth marker', () => {
  const files = fixture()
  files[base + 'node_modules/active/lib/index.js'] = 'unguarded'
  files[base + 'node_modules/dsh-undo-savepoint/lib/index.js'] = 'UNDO_AUTH ACTIVE_AUTH'
  assert.equal(accepted(files), false)
})
test('negative: a retained retired member must still carry its own auth marker', () => {
  const files = fixture()
  files[base + 'node_modules/dsh-undo-savepoint/lib/index.js'] = 'unguarded'
  assert.equal(accepted(files), false)
})
test('archive reader streams exact requested member content and refuses duplicate/non-file members', () => {
  const root = mkdtempSync(join(tmpdir(), 'route-auth-'))
  const archive = join(root, 'fixture.tar.xz')
  const make = entries => {
    const code = `import io,json,sys,tarfile
with tarfile.open(sys.argv[1], 'w:xz') as out:
 for name,text,kind in json.loads(sys.argv[2]):
  item=tarfile.TarInfo(name); payload=text.encode(); item.size=len(payload)
  if kind=='link': item.type=tarfile.SYMTYPE; item.linkname='other'; item.size=0
  out.addfile(item, io.BytesIO(payload) if kind!='link' else None)`
    const result=spawnSync('python',['-c',code,archive,JSON.stringify(entries)],{encoding:'utf8'})
    assert.equal(result.status,0,result.stderr)
  }
  try {
    make([['wanted','OWN','file'],['other','OTHER','file']])
    assert.deepEqual(readSnapshotRouteFiles(archive,['wanted','absent']),{wanted:'OWN',absent:null})
    make([['wanted','A','file'],['wanted','B','file']])
    assert.throws(()=>readSnapshotRouteFiles(archive,['wanted']),/duplicate route member/)
    make([['wanted','','link']])
    assert.throws(()=>readSnapshotRouteFiles(archive,['wanted']),/invalid route member/)
  } finally { rmSync(root,{recursive:true,force:true}) }
})
