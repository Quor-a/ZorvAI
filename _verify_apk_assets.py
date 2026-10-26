import hashlib, zipfile, os, sys

APK = "app/build/outputs/apk/full/release/app-full-release.apk"
ASSETS_DIR = "terminal-core/src/main/assets"

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()

def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()

# source assets (file path -> sha)
src = {}
for root, _, files in os.walk(ASSETS_DIR):
    for fn in files:
        full = os.path.join(root, fn)
        rel = os.path.relpath(full, ASSETS_DIR).replace("\\", "/")
        src[rel] = sha256_file(full)

# apk embedded assets
missing = []
inconsistent = []
apk_shas = {}
with zipfile.ZipFile(APK) as z:
    for info in z.infolist():
        name = info.filename
        if name.startswith("assets/") and not name.endswith("/"):
            rel = name[len("assets/"):]
            data = z.read(name)
            apk_shas[rel] = sha256_bytes(data)

# compare
for rel, s in src.items():
    if rel not in apk_shas:
        missing.append(rel)
    elif apk_shas[rel] != s:
        inconsistent.append((rel, s, apk_shas[rel]))

# apk entries not in source (informational, may be other assets)
extra = [r for r in apk_shas if r not in src]

print("source asset files:", len(src))
print("apk embedded assets:", len(apk_shas))
print("MISSING (in source, not in apk):", len(missing))
for m in missing:
    print("   ", m)
print("INCONSISTENT (sha mismatch):", len(inconsistent))
for r, s, a in inconsistent:
    print("   ", r, "src=", s[:16], "apk=", a[:16])
print("EXTRA (in apk, not in source assets):", len(extra))
for e in sorted(extra)[:20]:
    print("   ", e)
