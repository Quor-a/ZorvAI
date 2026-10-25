import { spawnSync } from 'node:child_process'

// Read only fixed requested members in one streaming pass. No archive path is extracted to disk.
export function readSnapshotRouteFiles(snapshot, members) {
  const code = `import json,sys,tarfile
wanted=set(json.loads(sys.argv[2])); out={name:None for name in wanted}; seen=set()
with tarfile.open(sys.argv[1], 'r|*') as archive:
 for entry in archive:
  name=entry.name.removeprefix('./')
  if name not in wanted: continue
  if name in seen: raise ValueError('duplicate route member: '+name)
  seen.add(name)
  if not entry.isfile() or entry.size > 16*1024*1024: raise ValueError('invalid route member: '+name)
  out[name]=archive.extractfile(entry).read().decode('utf-8')
print(json.dumps(out))`
  const result = spawnSync('python', ['-c', code, snapshot, JSON.stringify(members)], {
    encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
  })
  if (result.error || result.status !== 0) throw new Error(result.error?.message ?? String(result.stderr).trim())
  return JSON.parse(result.stdout)
}

export function snapshotRouteVerdicts(files, markers, retiredPackages, profiles = ['web', 'headless']) {
  const verdicts = []
  for (const profile of profiles) {
    const prefix = 'home/.dsh/profiles/' + profile + '/'
    const patch = files[prefix + 'cordis.patch.yml']
    verdicts.push({ label: profile + '/cordis.patch.yml', ok: typeof patch === 'string', detail: 'profile patch missing' })
    for (const [path, marker] of markers) {
      const content = files[prefix + 'node_modules/' + path]
      const packageName = path.startsWith('@') ? path.split('/').slice(0, 2).join('/') : path.split('/')[0]
      const retired = retiredPackages.has(packageName)
      // Conservative: any uncommented mention of the package or its historic id prevents exemption.
      const patchText = typeof patch === 'string' ? patch.replace(/^\s*#.*$/gm, '') : null
      const historicId = packageName.replace(/^dsh-/, '')
      const unmounted = patchText !== null && !patchText.includes(packageName) && !patchText.includes(historicId)
      const absentRetired = content == null && retired && unmounted
      verdicts.push({ label: profile + '/' + path,
        ok: absentRetired || (typeof content === 'string' && content.includes(marker)),
        detail: absentRetired ? 'explicitly retired and unmounted' : 'required member or its own auth marker missing',
      })
    }
  }
  return verdicts
}
