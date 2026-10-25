// check-snapshot-file-modes.mjs — validates final snapshot permissions against Android extraction policy.
// Usage: node scripts/check-snapshot-file-modes.mjs <snapshot.tar.xz>
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'

const snapshot = process.argv[2]
if (!snapshot) {
  console.error('usage: node check-snapshot-file-modes.mjs <snapshot.tar.xz>')
  process.exit(2)
}
const path = resolve(snapshot)
if (!existsSync(path)) {
  console.error('snapshot not found: ' + path)
  process.exit(2)
}

const checker = String.raw`
import sys, tarfile
path = sys.argv[1]
invalid = []
counts = {'files': 0, 'dirs': 0, 'symlinks': 0, 'exec': 0, 'data': 0}
with tarfile.open(path, 'r:xz') as archive:
    for member in archive:
        mode = member.mode & 0o777
        if member.isfile():
            counts['files'] += 1
            handle = archive.extractfile(member)
            prefix = handle.read(4) if handle else b''
            if handle:
                handle.close()
            executable = prefix.startswith(b'\x7fELF') or prefix.startswith(b'#!')
            expected = 0o700 if executable else 0o600
            counts['exec' if executable else 'data'] += 1
            if mode != expected:
                invalid.append((member.name, oct(mode), oct(expected)))
        elif member.isdir():
            counts['dirs'] += 1
            if mode != 0o700:
                invalid.append((member.name, oct(mode), oct(0o700)))
        elif member.issym():
            counts['symlinks'] += 1
if invalid:
    print('SNAPSHOT_MODE_CHECK_FAILED entries=' + str(len(invalid)))
    for name, actual, expected in invalid[:20]:
        print(name + ': mode=' + actual + ' expected=' + expected)
    sys.exit(1)
print('SNAPSHOT_MODE_CHECK_PASSED files={files} dirs={dirs} symlinks={symlinks} exec={exec} data={data}'.format(**counts))
`

try {
  const output = execFileSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', checker, path], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 })
  process.stdout.write(output)
} catch (error) {
  if (error.stdout) process.stdout.write(String(error.stdout))
  if (error.stderr) process.stderr.write(String(error.stderr))
  process.exit(error.status ?? 1)
}
