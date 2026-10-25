import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { TAR } from './lib/shell.mjs';

const sourceRoot = fileURLToPath(new URL('../', import.meta.url));
function fixture({ profiles = ['web', 'headless'], omitRequired = false } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'injection-manifest-'));
  try {
    mkdirSync(join(root, 'scripts/lib'), { recursive: true });
    for (const file of ['scripts/check-inject-completeness.mjs', 'scripts/lib/shell.mjs']) {
      copyFileSync(join(sourceRoot, file), join(root, file));
    }
    writeFileSync(join(root, 'scripts/plugin-dirs.json'), JSON.stringify({ dirs: [], externals: ['vendor/active'], retiredExternals: ['vendor/retired'] }));
    const pkg = JSON.stringify({ name: 'active-plugin', version: '1.0.0', type: 'module' });
    mkdirSync(join(root, 'vendor/active/lib'), { recursive: true });
    writeFileSync(join(root, 'vendor/active/package.json'), pkg);
    writeFileSync(join(root, 'vendor/active/lib/index.js'), 'export { value } from "./required.js";');
    writeFileSync(join(root, 'vendor/active/lib/required.js'), 'export const value = 1;');
    mkdirSync(join(root, 'vendor/retired/lib'), { recursive: true });
    writeFileSync(join(root, 'vendor/retired/package.json'), JSON.stringify({ name: 'retired-plugin', version: '1.0.0' }));
    writeFileSync(join(root, 'vendor/retired/lib/index.js'), 'retained historical source');
    const tree = join(root, 'tree');
    mkdirSync(join(tree, 'home'), { recursive: true });
    for (const profile of profiles) {
      const dest = join(tree, 'home/.dsh/profiles', profile, 'node_modules/active-plugin');
      mkdirSync(join(dest, 'lib'), { recursive: true });
      writeFileSync(join(dest, 'package.json'), pkg);
      for (const file of ['index.js', ...omitRequired ? [] : ['required.js']]) {
        copyFileSync(join(root, 'vendor/active/lib', file), join(dest, 'lib', file));
      }
    }
    const archive = join(root, 'fixture.tar');
    execFileSync(TAR, ['-cf', archive, '-C', tree, 'home']);
    return spawnSync(process.execPath, [join(root, 'scripts/check-inject-completeness.mjs'), archive], { encoding: 'utf8', cwd: root });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('retired source may remain without being injected, active packages still match in both profiles', () => {
  const result = fixture();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /CHECK-INJECT-COMPLETENESS PASSED/);
});
test('negative control: missing active package in headless still rejects the archive', () => {
  const result = fixture({ profiles: ['web'] });
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stdout, /FAIL.*headless.*active-plugin/);
});
test('negative control: missing a required active member still rejects the archive', () => {
  const result = fixture({ omitRequired: true });
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stdout, /required\.js/);
});
test('repository manifest keeps undo remounted inside the active injection set', () => {
  const manifest = JSON.parse(readFileSync(join(sourceRoot, 'scripts/plugin-dirs.json'), 'utf8'));
  // ZorvAI 2026-10 裁定：撤销保存点是内置 dsh-mobile 核心能力。FactoryProfilePatch.kt 的
  // RETIRED_FACTORY_ENTRIES 已清空、profile-web.cordis.patch.yml 显式 insert dsh-undo，
  // 故本仓 manifest 必须把 undo 留在 externals（随快照注入 profiles/*/node_modules），
  // 与 patch 挂载事实保持单一真相源一致。
  assert.equal(manifest.externals.includes('vendor/dsh-undo-savepoint'), true);
  assert.equal(manifest.retiredExternals.includes('vendor/dsh-undo-savepoint'), false);
  assert.equal(manifest.externals.includes('vendor/dshmarketplace-plugin'), true);
});
