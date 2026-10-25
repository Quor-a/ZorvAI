import yaml, sys

path = sys.argv[1] if len(sys.argv) > 1 else 'scripts/profile-web.cordis.patch.yml'
with open(path, encoding='utf-8') as f:
    doc = yaml.safe_load(f)

sp = [e for e in doc if isinstance(e, dict) and e.get('id') == 'system-prompt']
print('entries:', len(doc), '| system-prompt:', len(sp))
assert len(sp) == 1, 'system-prompt entry must be unique'
cfg = sp[0]['config']
print('config keys:', sorted(cfg.keys()))
print('includeHarnessIdentity absent -> schema default true (expected)')
print('prefix head:', cfg['personaPrefix'][:50].replace('\n', ' / '))
print('suffix head:', cfg['personaSuffix'][:50].replace('\n', ' / '))
# no {{ }} interpolation hazards
for k in ('personaPrefix', 'personaSuffix'):
    assert '{{' not in cfg[k] and '}}' not in cfg[k], k + ' contains prompt-variable braces'
# ids unique at top level (insert groups carry no id)
ids = [e.get('id') for e in doc if isinstance(e, dict) and e.get('id')]
dups = {i for i in ids if ids.count(i) > 1}
assert not dups, 'duplicate top-level ids: %r' % dups
print('YAML OK — no interpolation braces, no duplicate ids')
