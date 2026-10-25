// dsh-mobile safe mode transaction (S2) with CAS recovery failover, web-store resolver and factory-bundle filter (S5).
async function safeModeOwnershipManifest(filesRoot) {
  const readJson = async (path) => { try { return JSON.parse(await fs.readFile(path, 'utf8')) } catch { return null } };
  const valid = (value) => value && value.schema === 2 && value.complete === true &&
    /^[0-9a-f]{64}$/.test(value.fingerprint ?? '') && Array.isArray(value.entries) && value.entries.length > 0 &&
    value.entries.every((entry) => entry && typeof entry.id === 'string' && entry.id.length > 0 && typeof entry.name === 'string' && entry.name.length > 0) &&
    Array.isArray(value.profileEntries) && value.profileEntries.length > 0 &&
    value.profileEntries.every((entry) => entry && typeof entry.id === 'string' && typeof entry.name === 'string' &&
      value.entries.some((hard) => hard.id === entry.id && hard.name === entry.name)) &&
    (value.factoryBundles === undefined || value.factoryBundles === null ||
      (Array.isArray(value.factoryBundles) && value.factoryBundles.every((bundle) => bundle && typeof bundle.name === 'string' && bundle.name.length > 0 &&
        typeof bundle.version === 'string' && bundle.version.length > 0 && /^[0-9a-f]{64}$/.test(bundle.patchSha256 ?? ''))));
  const cache = await readJson(join(filesRoot, '.plugin-hard-manifest.json'));
  const installed = (await fs.readFile(join(filesRoot, '.snapshot-fingerprint'), 'utf8')).trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(installed) || !valid(cache)) throw new Error('ownership cache/fingerprint unavailable');
  const sidecar = async (archive, base) => {
    if (!/^[0-9a-f]{64}$/.test(archive ?? '') || !/^[0-9a-f]{64}$/.test(base ?? '')) return null;
    const value = await readJson(join(filesRoot, `.plugin-hard-manifest-online-${archive}.json`));
    return valid(value) && value.fingerprint === archive && value.baseFingerprint === base ? value : null;
  };
  const markerPath = join(filesRoot, '.snapshot-transaction');
  let marker = null;
  try {
    const fields = Object.fromEntries((await fs.readFile(markerPath, 'utf8')).split(/\r?\n/)
      .map((line) => { const at = line.indexOf('='); return at > 0 ? [line.slice(0, at), line.slice(at + 1)] : null }).filter(Boolean));
    marker = fields;
  } catch { /* no transaction marker */ }
  if (marker) {
    const purpose = marker.purpose || 'FACTORY'; // Legacy markers omitted purpose and mean FACTORY.
    if (!['STAGED', 'SWAPPING', 'SWAPPED', 'ONLINE_COMMITTED'].includes(marker.phase) ||
      !['FACTORY', 'ONLINE_UPDATE'].includes(purpose) || marker.phase === 'SWAPPING' ||
      (marker.phase === 'ONLINE_COMMITTED' && purpose !== 'ONLINE_UPDATE')) {
      throw new Error('snapshot transaction marker is unknown or incomplete');
    }
    if (purpose === 'ONLINE_UPDATE') {
      const base = (marker.baseFingerprint ?? '').toLowerCase();
      const cacheBase = (cache.baseFingerprint || cache.fingerprint).toLowerCase();
      if (!/^[0-9a-f]{64}$/.test(base) || cacheBase !== base) throw new Error('online base ownership does not match');
      if (marker.phase === 'SWAPPED' || marker.phase === 'ONLINE_COMMITTED') {
        const selected = await sidecar((marker.fingerprint ?? '').toLowerCase(), base);
        if (selected) return selected;
        throw new Error('online ownership sidecar missing');
      }
      if (marker.phase === 'STAGED') {
        const prior = (marker.priorFingerprint || installed).toLowerCase();
        if (prior !== base) {
          const selected = await sidecar(prior, base);
          if (selected) return selected;
          throw new Error('prior online ownership sidecar missing');
        }
        if (cache.fingerprint === prior) return cache;
        throw new Error('staged base ownership unavailable');
      }
      throw new Error('unknown online transaction phase');
    }
  }
  try {
    const fields = Object.fromEntries((await fs.readFile(join(filesRoot, '.online-snapshot'), 'utf8')).split(/\r?\n/)
      .map((line) => { const at = line.indexOf('='); return at > 0 ? [line.slice(0, at), line.slice(at + 1)] : null }).filter(Boolean));
    const base = (fields.base ?? '').toLowerCase(), archive = (fields.archive ?? '').toLowerCase();
    const cacheBase = (cache.baseFingerprint || cache.fingerprint).toLowerCase();
    if (archive === installed && base === cacheBase) {
      const selected = await sidecar(archive, base);
      if (selected) return selected;
      throw new Error('committed online ownership sidecar missing');
    }
  } catch (error) {
    if (String(error?.message ?? error).includes('sidecar missing')) throw error;
  }
  if (cache.fingerprint !== installed) throw new Error('cached ownership belongs to another snapshot');
  return cache;
}
function safeModeSha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
async function safeModeAtomicWrite(target, bytes) {
  const dir = dirname(target);
  await fs.mkdir(dir, { recursive: true });
  const temp = join(dir, `.${basename(target)}.safe-mode-${randomBytes(6).toString('hex')}.tmp`);
  try {
    await fs.writeFile(temp, bytes);
    await fs.rename(temp, target);
  } finally {
    await fs.rm(temp, { force: true }).catch(() => {});
  }
}
async function safeModeBackupPath(cfg, value, id, prefix, suffix = '.yml') {
  if (typeof value !== 'string' || value === '' || typeof id !== 'string' || id === '') return null;
  try {
    const root = await fs.realpath(cfg.autoDir);
    const candidate = resolve(value);
    const expectedName = `${prefix}${id}${suffix}`;
    if (basename(candidate) !== expectedName || await fs.realpath(dirname(candidate)) !== root) return null;
    // Missing primary files remain valid recovery candidates when the snapshot id and root bind the path.
    // autoDir and stored paths may use a symlink alias; only allow that on the
    // parent path. The backup file itself must remain a regular, non-symlink file.
    const canonical = join(root, expectedName);
    try {
      const info = await fs.lstat(candidate);
      if (!info.isFile() || info.isSymbolicLink() || await fs.realpath(candidate) !== canonical) return null;
    } catch (error) { if (error?.code !== 'ENOENT') return null; }
    return canonical;
  } catch { return null; }
}
async function safeModeVerifyBackup(path, expectedSha) {
  if (!path) return false;
  try {
    const bytes = await fs.readFile(path);
    return typeof expectedSha !== 'string' || expectedSha === '' || safeModeSha256(bytes) === expectedSha;
  } catch { return false; }
}
async function safeModeReadRegular(path) {
  if (!path) return null;
  let handle;
  try {
    handle = await fs.open(path, fsConstants.O_RDONLY | (fsConstants.O_NOFOLLOW ?? 0));
    const info = await handle.stat();
    if (!info.isFile()) return null;
    return await handle.readFile();
  } catch { return null; } finally { await handle?.close().catch(() => {}); }
}
function safeModeRecoveryObjectName(sha) {
  return typeof sha === 'string' && /^[0-9a-f]{64}$/.test(sha) ? `safe-mode-${sha}.yml` : null;
}
async function safeModeRecoveryPath(autoDir, sha, create = false) {
  const objectName = safeModeRecoveryObjectName(sha);
  if (!objectName) return null;
  try {
    const dir = join(dirname(autoDir), 'safe-mode-recovery');
    if (create) await fs.mkdir(dir, { recursive: true });
    const root = await fs.realpath(dirname(autoDir));
    const recovery = join(root, 'safe-mode-recovery');
    if (await fs.realpath(recovery) !== recovery || (await fs.lstat(recovery)).isSymbolicLink()) return null;
    return join(recovery, objectName);
  } catch { return null; }
}
async function safeModeResolveWebStore(cfg) {
  if (cfg.profileName !== 'web' || cfg.safeModeDefaultWebStore !== true) return { cfg };
  const root = dirname(cfg.autoDir);
  if (basename(cfg.autoDir) !== 'auto') return { cfg };
  const flatAuto = cfg.autoDir;
  const scopedAuto = join(root, 'web', 'auto');
  const markers = ['safe-mode.json', 'safe-mode-state.json'];
  const hasMarker = async (dir) => {
    for (const name of markers) {
      try { await fs.lstat(join(dir, name)); return true; } catch { /* no marker entry */ }
    }
    return false;
  };
  const flat = await hasMarker(flatAuto);
  const scoped = await hasMarker(scopedAuto);
  if (flat && scoped) return { error: `Safe Mode state conflict: both ${flatAuto} and ${scopedAuto} contain marker files; refusing to read or modify either store.` };
  const autoDir = scoped ? scopedAuto : flatAuto;
  return { cfg: { ...cfg, autoDir, manualDir: join(dirname(autoDir), 'manual') } };
}
async function safeModeEnsureRecovery(cfg, bytes, sha, atomicWrite) {
  const target = await safeModeRecoveryPath(cfg.autoDir, sha, true);
  if (!target) throw new Error('invalid recovery digest');
  const existing = await safeModeReadRegular(target);
  if (!existing || safeModeSha256(existing) !== sha) await atomicWrite(target, bytes);
  const verified = await safeModeReadRegular(target);
  if (!verified || !verified.equals(bytes)) throw new Error('recovery copy verification failed');
}
async function safeModeLoadBackup(cfg, primary, expectedSha) {
  if (!primary) return null;
  const primaryBytes = await safeModeReadRegular(primary);
  if (primaryBytes && (typeof expectedSha !== 'string' || expectedSha === '' || safeModeSha256(primaryBytes) === expectedSha)) return primaryBytes;
  // Digest-less legacy state can only authorize its primary backup.
  if (typeof expectedSha !== 'string' || expectedSha === '') return null;
  const copy = await safeModeReadRegular(await safeModeRecoveryPath(cfg.autoDir, expectedSha));
  return copy && safeModeSha256(copy) === expectedSha ? copy : null;
}
async function safeModeBundlePatchSha(packageRoot, declared) {
  const patches = typeof declared === 'string' ? [declared] : declared;
  if (!Array.isArray(patches) || patches.length === 0) throw new Error('bundle patch declaration missing');
  const root = await fs.realpath(packageRoot);
  const hash = createHash('sha256').update(Buffer.from('DSHBNDL1', 'ascii'));
  for (const rel of patches) {
    if (typeof rel !== 'string' || !rel || isAbsolute(rel) || rel.includes('\\') || rel.includes('\0') || rel.split('/').includes('..')) throw new Error('unsafe bundle patch path');
    const target = resolve(root, rel);
    const relFromRoot = relative(root, target);
    if (!relFromRoot || relFromRoot === '..' || relFromRoot.startsWith(`..${sep}`)) throw new Error('bundle patch escapes package');
    let cursor = root;
    for (const segment of relFromRoot.split(sep)) {
      cursor = join(cursor, segment);
      const info = await fs.lstat(cursor);
      if (info.isSymbolicLink() || (cursor === target ? !info.isFile() : !info.isDirectory())) throw new Error('bundle patch path is not regular');
    }
    const pathBytes = Buffer.from(rel, 'utf8');
    const patchBytes = await fs.readFile(target);
    const pathLength = Buffer.alloc(4); pathLength.writeUInt32BE(pathBytes.length);
    const contentLength = Buffer.alloc(8); contentLength.writeBigUInt64BE(BigInt(patchBytes.length));
    hash.update(pathLength).update(pathBytes).update(contentLength).update(patchBytes);
  }
  return hash.digest('hex');
}
async function safeModeSelectFactoryBundles(cfg, pkg, packageText, identities) {
  const nested = pkg.dsh?.profile && Object.hasOwn(pkg.dsh.profile, 'bundles');
  const dotted = Object.hasOwn(pkg, 'dsh.profile.bundles');
  const source = nested ? pkg.dsh.profile.bundles : dotted ? pkg['dsh.profile.bundles'] : undefined;
  if (source === undefined) return { bytes: null, removed: [] };
  if (!Array.isArray(source)) throw new Error('profile bundles must be an array');
  if (source.length === 0) return { bytes: null, removed: [] };
  if (!Array.isArray(identities)) throw new Error('factory bundle identities unavailable for active profile bundles');
  const trusted = new Map(identities.map((entry) => [entry.name, entry]));
  const kept = [], removed = [];
  for (const name of source) {
    if (typeof name !== 'string') { removed.push(String(name)); continue; }
    const expected = trusted.get(name);
    const candidate = expected ? await bundleCheck(cfg, name) : { ok: false };
    let matches = false;
    if (candidate.ok) {
      const packageRoot = await fs.realpath(candidate.dir);
      const packageMeta = await fs.lstat(join(packageRoot, 'package.json'));
      if (!packageMeta.isFile() || packageMeta.isSymbolicLink()) throw new Error('bundle package manifest is not a regular file');
      const packageInfo = JSON.parse(await fs.readFile(join(packageRoot, 'package.json'), 'utf8'));
      if (packageInfo.name === name && packageInfo.version === expected.version) {
        matches = await safeModeBundlePatchSha(packageRoot, packageInfo.dsh?.bundle?.patch) === expected.patchSha256;
      }
    }
    if (matches) kept.push(name); else removed.push(name);
  }
  if (kept.length === source.length && kept.every((name, index) => name === source[index])) return { bytes: null, removed };
  if (nested) pkg.dsh.profile.bundles = kept; else pkg['dsh.profile.bundles'] = kept;
  return { bytes: Buffer.from(JSON.stringify(pkg, null, 2) + '\n', 'utf8'), removed };
}
async function safeModeSet(cfg, on, { atomicWrite = safeModeAtomicWrite } = {}) {
  const selected = await safeModeResolveWebStore(cfg);
  if (selected.error) return { ok: false, error: selected.error };
  cfg = selected.cfg;
  const stateFilePath = join(cfg.autoDir, 'safe-mode.json');
  const legacyStatePath = join(cfg.autoDir, 'safe-mode-state.json');
  try {
    await fs.lstat(legacyStatePath);
    const legacyInfo = await fs.lstat(legacyStatePath);
    if (!legacyInfo.isFile() || legacyInfo.isSymbolicLink()) return { ok: false, error: 'Legacy Safe Mode marker is not a regular file; refusing to follow it.' };
    try { await fs.lstat(stateFilePath); return { ok: false, error: 'Both current and legacy Safe Mode markers exist; refusing to guess the recovery authority.' }; }
    catch (error) { if (error?.code !== 'ENOENT') throw error; }
    const legacyBytes = await fs.readFile(legacyStatePath);
    try { JSON.parse(legacyBytes.toString('utf8')); } catch { return { ok: false, error: 'Legacy Safe Mode marker is corrupt; refusing to treat it as inactive.' }; }
    await atomicWrite(stateFilePath, legacyBytes);
    await fs.rm(legacyStatePath);
  } catch (error) {
    if (error?.code !== 'ENOENT') return { ok: false, error: `Legacy Safe Mode marker migration failed; recovery source was retained: ${String(error?.message ?? error)}` };
  }
  try {
    await fs.lstat(stateFilePath);
    const stateInfo = await fs.lstat(stateFilePath);
    if (!stateInfo.isFile() || stateInfo.isSymbolicLink()) return { ok: false, error: 'Safe-mode state is not a regular file; refusing to follow or overwrite it.' };
    const raw = await fs.readFile(stateFilePath, 'utf8');
    try { JSON.parse(raw); } catch { return { ok: false, error: 'Safe-mode state is corrupt; refusing to treat it as inactive or overwrite its recovery sources.' }; }
  } catch (error) {
    if (error?.code !== 'ENOENT') return { ok: false, error: `Safe-mode state is unreadable; refusing to continue: ${String(error?.message ?? error)}` };
  }
  if (hasOpenTurn()) return busyError();
  const st = await safeModeStatus(cfg);
  if (st.active && st.profile && st.profile !== cfg.profileName) return { ok: false, error: `Safe-mode state belongs to profile ${st.profile}; refusing to use it as ${cfg.profileName}.` };
  const patch = filePath(cfg, { root: 'profile', rel: 'cordis.patch.yml' });
  const homePatch = filePath(cfg, { root: 'home', rel: 'cordis.patch.yml' });
  const pkgPath = filePath(cfg, { root: 'profile', rel: 'package.json' });
  if (on) {
    if (st.active) {
      let rescanned = [];
      try {
        const pkg = JSON.parse(await fs.readFile(pkgPath, 'utf8'));
        rescanned = (await computeSafeBundles(cfg, pkg)).pruned;
      } catch { /* package.json 读不到则重扫结果为空 */ }
      return {
        ok: true, active: true,
        message: t('safe.alreadyOn', { entered: st.enteredAt ?? '?' })
          + (rescanned.length > 0
            ? t('safe.rescan.found', { n: rescanned.length, list: rescanned.map((p) => p.name).join(', ') })
            : t('safe.rescan.none')),
      };
    }
    if (await pathExists(join(cfg.autoDir, 'safe-mode.json'))) {
      const raw = await fs.readFile(join(cfg.autoDir, 'safe-mode.json'), 'utf8').catch(() => null);
      if (raw === null) return { ok: false, error: 'Safe-mode state is unreadable; refusing to overwrite recovery state.' };
      try { JSON.parse(raw); } catch { return { ok: false, error: 'Safe-mode state is corrupt; refusing to overwrite recovery state.' }; }
    }
    const filesRoot = dirname(dirname(cfg.homeDir ?? DSH_HOME));
    let hardManifest;
    try { hardManifest = await safeModeOwnershipManifest(filesRoot); }
    catch (error) { return { ok: false, error: `Trusted factory manifest unavailable; refusing Safe Mode: ${String(error?.message ?? error)}` }; }
    const snap = await createSnapshot(cfg, 'manual', 'safe-mode-before');
    const backup = join(cfg.autoDir, `safe-mode-backup-${snap.id}.yml`);
    const homeBackup = join(cfg.autoDir, `safe-mode-home-backup-${snap.id}.yml`);
    const pkgBackup = join(cfg.autoDir, `safe-mode-pkg-${snap.id}.json`);
    await fs.mkdir(cfg.autoDir, { recursive: true });
    const patchBytes = await fs.readFile(patch).catch((error) => {
      if (error?.code === 'ENOENT') return Buffer.from('[]\n');
      throw error;
    });
    const homePatchExists = await pathExists(homePatch);
    const homeBytes = homePatchExists ? await fs.readFile(homePatch) : null;
    let pkgBytes = null;
    let prunedBundles = [];
    let pkgSafeBytes = null;
    try { pkgBytes = await fs.readFile(pkgPath); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
    if (pkgBytes !== null) {
      const pkgRaw = pkgBytes.toString('utf8');
      try {
        const pkg = JSON.parse(pkgRaw);
        const selected = await safeModeSelectFactoryBundles(cfg, pkg, pkgRaw, hardManifest.factoryBundles);
        prunedBundles = selected.removed.map((name) => ({ name, reason: 'not a verified factory bundle' }));
        pkgSafeBytes = selected.bytes;
      } catch (error) {
        return { ok: false, error: `Safe Mode bundle ownership is incomplete; no live files changed: ${String(error?.message ?? error)}` };
      }
    }
    try {
      await atomicWrite(backup, patchBytes);
      if (homeBytes) await atomicWrite(homeBackup, homeBytes);
      if (pkgBytes) await atomicWrite(pkgBackup, pkgBytes);
      await safeModeEnsureRecovery(cfg, patchBytes, safeModeSha256(patchBytes), atomicWrite);
      if (homeBytes) await safeModeEnsureRecovery(cfg, homeBytes, safeModeSha256(homeBytes), atomicWrite);
      if (pkgBytes) await safeModeEnsureRecovery(cfg, pkgBytes, safeModeSha256(pkgBytes), atomicWrite);
      if (!(await safeModeVerifyBackup(backup, safeModeSha256(patchBytes))) ||
        (homeBytes && !(await safeModeVerifyBackup(homeBackup, safeModeSha256(homeBytes)))) ||
        (pkgBytes && !(await safeModeVerifyBackup(pkgBackup, safeModeSha256(pkgBytes))))) {
        return { ok: false, error: t('safe.err.backupWrite', { backup }) };
      }
    } catch (error) {
      return { ok: false, error: t('safe.err.backupWrite', { backup }) + ` (${String(error?.message ?? error)})` };
    }
    // dsh-mobile safe mode transaction (S2): require final-snapshot ownership identities.
    const minimal = safeModeFilterInserts(patchBytes.toString('utf8'), hardManifest.entries);
    const state = {
      active: true, profile: cfg.profileName, enteredAt: new Date().toISOString(), backup, snapshotId: snap.id,
      backupSha256: safeModeSha256(patchBytes),
      homeBackup: homePatchExists ? homeBackup : undefined,
      homeExisted: homePatchExists,
      homeBackupSha256: homeBytes ? safeModeSha256(homeBytes) : undefined,
      homeFingerprint: await homeFingerprint(cfg),
    };
    if (pkgBytes) {
      state.pkgBackup = pkgBackup;
      state.pkgBackupSha256 = safeModeSha256(pkgBytes);
    }
    if (prunedBundles.length > 0) state.prunedBundles = prunedBundles;
    const stateFile = join(cfg.autoDir, 'safe-mode.json');
    try {
      await atomicWrite(stateFile, Buffer.from(JSON.stringify(state, null, 2), 'utf8'));
      await atomicWrite(patch, Buffer.from(minimal, 'utf8'));
      if (homePatchExists) {
        await atomicWrite(homePatch, Buffer.from(`# dsh-undo-savepoint SAFE MODE (home level, entered ${new Date().toISOString()})\n[]\n`, 'utf8'));
      }
      if (pkgSafeBytes) await atomicWrite(pkgPath, pkgSafeBytes);
    } catch (error) {
      return { ok: false, active: true, error: `Safe Mode changes did not finish; recovery state and backups were retained. Retry safe-mode off after resolving the write error: ${String(error?.message ?? error)}` };
    }
    const prunedTxt = prunedBundles.length > 0
      ? t('safe.neutralized', { n: prunedBundles.length, list: prunedBundles.map((p) => `${p.name}（${p.reason}）`).join('；') })
      : '';
    let patchNote = '';
    try {
      const pv = await patchVerify(cfg);
      if (pv.ok === false && Array.isArray(pv.missing) && pv.missing.length > 0) {
        patchNote = t('safe.patchNote', { n: pv.missing.length, list: pv.missing.join(', ') });
      }
    } catch { /* 检测失败不影响安全模式 */ }
    return { ok: true, active: true, snapshotId: snap.id, prunedBundles, message: t('safe.on', { id: snap.id }) + prunedTxt + patchNote };
  }
  // off
  if (!st.active) {
    return st.stale
      ? { ok: true, active: false, message: t('safe.stale') }
      : { ok: true, active: false, message: t('safe.notActive') };
  }
  if (st.profile && st.profile !== cfg.profileName) return { ok: false, error: `Safe-mode state belongs to profile ${st.profile}; refusing to restore it as ${cfg.profileName}.` };
  const backup = await safeModeBackupPath(cfg, st.backup, st.snapshotId, 'safe-mode-backup-');
  const homeBackup = st.homeBackup
    ? await safeModeBackupPath(cfg, st.homeBackup, st.snapshotId, 'safe-mode-home-backup-') : null;
  const pkgBackup = st.pkgBackup
    ? await safeModeBackupPath(cfg, st.pkgBackup, st.snapshotId, 'safe-mode-pkg-', '.json') : null;
  const patchBytes = await safeModeLoadBackup(cfg, backup, st.backupSha256);
  const homeBytes = homeBackup ? await safeModeLoadBackup(cfg, homeBackup, st.homeBackupSha256) : null;
  const pkgBytes = pkgBackup ? await safeModeLoadBackup(cfg, pkgBackup, st.pkgBackupSha256) : null;
  if (!st.active || !patchBytes) {
    return { ok: false, error: 'Safe-mode backup missing. Restore a snapshot from before the crash first (undo_list / undo_restore).' };
  }
  if ((st.homeBackup && !homeBytes) || (st.pkgBackup && !pkgBytes)) {
    return { ok: false, error: 'Safe-mode home backup missing. Restore a snapshot from before the crash first (undo_list / undo_restore).' };
  }
  try {
    await atomicWrite(patch, patchBytes);
    if (homeBackup) await atomicWrite(homePatch, homeBytes);
    if (pkgBackup) await atomicWrite(pkgPath, pkgBytes);
    await fs.rm(join(cfg.autoDir, 'safe-mode.json'), { force: true });
  } catch (error) {
    return { ok: false, error: `Safe-mode restore incomplete; state and backups were retained for retry: ${String(error?.message ?? error)}` };
  }
  const pkgRestored = !!pkgBackup;
  const restoreTxt = pkgRestored
    ? t('safe.off.restorePkg', { n: st.prunedBundles?.length ?? 0 })
    : t('safe.off.legacy');
  return { ok: true, active: false, message: t('safe.off') + restoreTxt };
}
