const VERSION = /^v?(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?(?:\.(0|[1-9]\d*))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/

export function parseVersion(value) {
  if (typeof value !== 'string') return null
  const match = VERSION.exec(value.trim())
  if (!match) return null
  const prerelease = match[4] ? match[4].split('.') : []
  if (prerelease.some((part) => !part || (/^\d+$/.test(part) && part.length > 1 && part[0] === '0'))) return null
  return {
    major: Number(match[1]),
    minor: Number(match[2] ?? 0),
    patch: Number(match[3] ?? 0),
    prerelease,
  }
}

function comparePrerelease(a, b) {
  if (!a.length && !b.length) return 0
  if (!a.length) return 1
  if (!b.length) return -1
  const length = Math.max(a.length, b.length)
  for (let index = 0; index < length; index += 1) {
    if (a[index] === undefined) return -1
    if (b[index] === undefined) return 1
    const leftNumeric = /^\d+$/.test(a[index])
    const rightNumeric = /^\d+$/.test(b[index])
    if (leftNumeric && rightNumeric) {
      const left = BigInt(a[index])
      const right = BigInt(b[index])
      if (left !== right) return left < right ? -1 : 1
    } else if (leftNumeric !== rightNumeric) {
      return leftNumeric ? -1 : 1
    } else if (a[index] !== b[index]) {
      return a[index] < b[index] ? -1 : 1
    }
  }
  return 0
}

export function compareVersions(left, right) {
  const a = parseVersion(left)
  const b = parseVersion(right)
  if (!a || !b) throw new TypeError(`Invalid semantic version: ${!a ? left : right}`)
  for (const key of ['major', 'minor', 'patch']) {
    if (a[key] !== b[key]) return a[key] < b[key] ? -1 : 1
  }
  return comparePrerelease(a.prerelease, b.prerelease)
}
