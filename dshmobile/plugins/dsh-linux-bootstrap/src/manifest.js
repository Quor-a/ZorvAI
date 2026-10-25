import { parseVersion } from './version.js'

export const MANIFEST_NAME = 'linux-release.json'
export const BOOTSTRAP_ASSET_NAME = 'linux-bootstrap-plugin.tar.xz'
export const RUNTIME_ASSET_NAME = 'linux-runtime-plugin.tar.xz'
export const RUNTIME_PACKAGE_NAME = '@dsh-android/linux-runtime'
export const RUNTIME_ENTRY = 'lib/index.js'

const RELEASE_DOWNLOAD = /^\/([^/]+)\/([^/]+)\/releases\/download\/([^/]+)\/([^/]+)$/
const RELEASE_PAGE = /^\/([^/]+)\/([^/]+)\/releases\/tag\/([^/]+)$/
const GITHUB_HOSTS = new Set(['github.com', 'api.github.com'])

export function parseGithubUrl(value, { allowApi = false } = {}) {
  let url
  try { url = new URL(value) } catch { throw new TypeError(`Invalid GitHub URL: ${value}`) }
  if (url.protocol !== 'https:' || url.username || url.password || url.port || !GITHUB_HOSTS.has(url.hostname)) {
    throw new TypeError('URL must use HTTPS on github.com')
  }
  if (url.hostname === 'api.github.com' && !allowApi) throw new TypeError('Expected a GitHub release URL')
  return url
}

function releaseAssetUrl(asset, expected, release) {
  if (!asset || typeof asset !== 'object') throw new TypeError(`Invalid ${expected} asset`)
  const { url, sha256, size, unpackedSize } = asset
  if (typeof url !== 'string' || !/^[a-f0-9]{64}$/.test(sha256 ?? '') || !Number.isSafeInteger(size) || size < 0 || !Number.isSafeInteger(unpackedSize) || unpackedSize < 0) {
    throw new TypeError(`${expected} asset requires HTTPS url, SHA-256, size, and unpackedSize`)
  }
  const parsed = parseGithubUrl(url)
  const match = RELEASE_DOWNLOAD.exec(parsed.pathname)
  if (!match || parsed.search || parsed.hash) throw new TypeError(`${expected} asset URL must be a versioned GitHub release download`)
  if (match[1] !== release.owner || match[2] !== release.repo || decodeURIComponent(match[3]) !== release.tag) {
    throw new TypeError(`${expected} asset must belong to the manifest's GitHub release`)
  }
  const fileName = decodeURIComponent(match[4])
  if (expected === 'plugins.bootstrap' && fileName !== BOOTSTRAP_ASSET_NAME) throw new TypeError(`plugins.bootstrap URL must end in ${BOOTSTRAP_ASSET_NAME}`)
  if (expected === 'plugins.runtime' && fileName !== RUNTIME_ASSET_NAME) throw new TypeError(`plugins.runtime URL must end in ${RUNTIME_ASSET_NAME}`)
  if (asset.pluginReleaseUrl !== undefined) validateReleasePage(asset.pluginReleaseUrl, release)
  return { ...asset, url: parsed.href }
}

function validateReleasePage(value, expected) {
  if (typeof value !== 'string') throw new TypeError('pluginReleaseUrl must be a URL')
  const url = parseGithubUrl(value)
  const match = RELEASE_PAGE.exec(url.pathname)
  if (!match || url.search || url.hash || match[1] !== expected.owner || match[2] !== expected.repo || decodeURIComponent(match[3]) !== expected.tag) {
    throw new TypeError('pluginReleaseUrl must be the matching HTTPS GitHub release page')
  }
  return url.href
}

export function validateManifestShape(manifest, latestTag) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new TypeError('Release manifest must be a JSON object')
  if (manifest.schemaVersion !== 1) throw new TypeError('Unsupported release manifest schemaVersion')
  if (typeof manifest.version !== 'string' || !parseVersion(manifest.version)) throw new TypeError('Manifest version must be semantic version')
  if (typeof manifest.minMobileVersion !== 'string' || !parseVersion(manifest.minMobileVersion)) throw new TypeError('Manifest minMobileVersion must be semantic version')
  const version = manifest.version.replace(/^v/, '')
  const tag = `v${version}`
  if (latestTag && latestTag !== tag && latestTag !== version) throw new TypeError('Latest release tag does not match manifest version')
  if (!manifest.plugins || typeof manifest.plugins !== 'object') throw new TypeError('Manifest plugins are required')
  if (!Array.isArray(manifest.runtimes) || manifest.runtimes.length === 0) throw new TypeError('Manifest runtimes are required')

  const pluginAssets = [manifest.plugins.bootstrap, manifest.plugins.runtime]
  const firstUrl = pluginAssets.find((asset) => typeof asset?.url === 'string')?.url
  if (!firstUrl) throw new TypeError('Manifest plugin asset URLs are required')
  const first = parseGithubUrl(firstUrl)
  const match = RELEASE_DOWNLOAD.exec(first.pathname)
  if (!match) throw new TypeError('Plugin asset must use a versioned GitHub release URL')
  const release = { owner: match[1], repo: match[2], tag }
  if (decodeURIComponent(match[3]) !== tag) throw new TypeError('Plugin asset tag does not match manifest version')

  const plugins = {
    bootstrap: releaseAssetUrl(manifest.plugins.bootstrap, 'plugins.bootstrap', release),
    runtime: releaseAssetUrl(manifest.plugins.runtime, 'plugins.runtime', release),
  }
  const runtimes = manifest.runtimes.map((runtime) => {
    if (!runtime || typeof runtime !== 'object' || !/^[a-z0-9][a-z0-9-]*$/.test(runtime.id ?? '') || typeof runtime.label !== 'string' || typeof runtime.architecture !== 'string' || typeof runtime.backend !== 'string') {
      throw new TypeError('Each runtime requires id, label, architecture, and backend')
    }
    const copy = { ...runtime }
    for (const field of ['backendAsset', 'rootfsAsset', 'nodeAsset']) {
      if (copy[field] != null) copy[field] = releaseAssetUrl(copy[field], `runtimes.${runtime.id}.${field}`, release)
    }
    return copy
  })
  if (!runtimes.some(({ id }) => id === 'debian-minimal')) throw new TypeError('Manifest must include the debian-minimal runtime')
  const pluginReleaseUrl = manifest.pluginReleaseUrl === undefined ? undefined : validateReleasePage(manifest.pluginReleaseUrl, release)
  return { ...manifest, version, releaseVersion: version, plugins, runtimes, ...(pluginReleaseUrl ? { pluginReleaseUrl } : {}), _release: release }
}

export function findRuntime(manifest, runtimeId) {
  return manifest.runtimes.find((runtime) => runtime.id === runtimeId) ?? null
}
