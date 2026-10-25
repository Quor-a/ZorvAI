import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

const source = readFileSync(new URL('./build-apk-013.ps1', import.meta.url), 'utf8');
const expression = /^\s*foreach \(\$pair in (.*)\) \{$/m.exec(source)?.[1];
const available = spawnSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.Major'], { encoding: 'utf8' }).status === 0;
function enumeratePairs(code) {
  const result = spawnSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command',
    "$market='fixture-source'; $seen=@(); foreach($pair in " + code + ") { $seen += [PSCustomObject]@{leaf=$pair[0];src=$pair[1]} }; ConvertTo-Json -InputObject @($seen) -Compress"], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout.trim());
}
test('actual build loop passes one intact source pair to vendor degradation', { skip: !available }, () => {
  assert.ok(expression, 'actual staging loop must be located');
  assert.deepEqual(enumeratePairs(expression), [{ leaf: 'market-degraded', src: 'fixture-source' }]);
});
test('negative control: the old singleton nested array flattens into invalid character pairs', { skip: !available }, () => {
  const pairs = enumeratePairs("@(@('market-degraded', $market))");
  assert.equal(pairs.length, 2);
  assert.notDeepEqual(pairs, [{ leaf: 'market-degraded', src: 'fixture-source' }]);
});
