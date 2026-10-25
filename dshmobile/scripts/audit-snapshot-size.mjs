// audit-snapshot-size.mjs — reproducible size and file-count report for a runtime snapshot.
// Usage: node scripts/audit-snapshot-size.mjs <snapshot.tar.xz> [--out report.json]
import { execFileSync } from 'node:child_process'
import { existsSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const [snapshotArg, ...rest] = process.argv.slice(2)
const outIndex = rest.indexOf('--out')
const outputPath = outIndex >= 0 ? rest[outIndex + 1] : undefined
if (!snapshotArg || (outIndex >= 0 && !outputPath)) {
  console.error('usage: node audit-snapshot-size.mjs <snapshot.tar.xz> [--out report.json]')
  process.exit(2)
}
const snapshot = resolve(snapshotArg)
if (!existsSync(snapshot)) {
  console.error('snapshot not found: ' + snapshot)
  process.exit(2)
}

const analyzer = String.raw`
import collections, json, os, sys, tarfile
path = sys.argv[1]
roots = collections.Counter()
usr = collections.Counter()
home = collections.Counter()
extensions = collections.Counter()
extension_counts = collections.Counter()
node_packages = collections.Counter()
node_package_counts = collections.Counter()
classes = collections.Counter()
entries = collections.Counter()
with tarfile.open(path, 'r:xz') as archive:
    for member in archive:
        if member.isdir(): entries['directories'] += 1
        elif member.isfile(): entries['files'] += 1
        elif member.issym(): entries['symlinks'] += 1
        else: entries['other'] += 1
        if not member.isfile():
            continue
        name = member.name.strip('./')
        parts = name.split('/')
        size = member.size
        roots[parts[0] if parts else ''] += size
        if name.startswith('usr/'):
            usr['/'.join(parts[:3])] += size
        if name.startswith('home/.dsh/'):
            home['/'.join(parts[:4])] += size
        base = parts[-1].lower()
        extension = os.path.splitext(base)[1] or '[none]'
        extensions[extension] += size
        extension_counts[extension] += 1
        if '/node_modules/' in name:
            if base.endswith(('.d.ts', '.d.mts', '.d.cts')): classes['type-declarations'] += size
            elif '/src/' in name and base.endswith(('.ts', '.tsx', '.mts', '.cts')): classes['typescript-source'] += size
            elif any(marker in name for marker in ('/test/', '/tests/', '/__tests__/', '/fixtures/', '/examples/')): classes['tests-and-fixtures'] += size
            elif base in ('readme.md', 'changelog.md') or '/docs/' in name: classes['documentation'] += size
        marker = 'usr/lib/node_modules/'
        if name.startswith(marker):
            rest = name[len(marker):].split('/')
            if rest:
                package = '/'.join(rest[:2]) if rest[0].startswith('@') and len(rest) > 1 else rest[0]
                node_packages[package] += size
                node_package_counts[package] += 1
def rows(counter, counts=None, limit=50):
    return [{'name': key, 'bytes': value, 'mib': round(value / 1048576, 2), 'files': (counts or {}).get(key)} for key, value in counter.most_common(limit)]
report = {
  'snapshotBytes': os.path.getsize(path),
  'snapshotMiB': round(os.path.getsize(path) / 1048576, 2),
  'entries': dict(entries),
  'rawRegularFileBytes': sum(roots.values()),
  'rawRegularFileMiB': round(sum(roots.values()) / 1048576, 2),
  'rootDirectories': rows(roots, limit=10),
  'usrTopLevel': rows(usr, limit=40),
  'homeTopLevel': rows(home, limit=40),
  'fileExtensions': rows(extensions, extension_counts, limit=40),
  'globalNodePackages': rows(node_packages, node_package_counts, limit=50),
  'nonRuntimeCandidateClasses': [{'name': key, 'bytes': value, 'mib': round(value / 1048576, 2)} for key, value in classes.most_common()]
}
print(json.dumps(report, ensure_ascii=False, indent=2))
`

try {
  const json = execFileSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', analyzer, snapshot], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (outputPath) writeFileSync(resolve(outputPath), json)
  process.stdout.write(json)
} catch (error) {
  if (error.stdout) process.stdout.write(String(error.stdout))
  if (error.stderr) process.stderr.write(String(error.stderr))
  process.exit(error.status ?? 1)
}
