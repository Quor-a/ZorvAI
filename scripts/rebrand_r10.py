#!/usr/bin/env python3
# Adapted rebrand for the r10 LANXIU rootfs (upstream "LANXIU Linux 1.7.41").
# r10 has NO qiyuan/Qiyuan/启元 strings (it is clean upstream LANXIU, qy* namespace kept).
# Branding = turn user-visible "LANXIU" -> "澜岫 (LANXIU)".
# MUST use preset=6 (8MB dict) — Android's weak xz can't decompress preset=9's 64MB dict.
import tarfile, io, os

SRC = r"D:/WyDownloads/2026-08-28-00-11-37/lanxiu_iso_work/r10_rootfs.tar.xz"
ASSET = r"D:/Calw OS-project/QuroAI/terminal-core/src/main/assets/lanxiu-aarch64-rootfs-20261017.tar.xz"
DST = ASSET + ".tmp"

LANXIU = "澜岫 (LANXIU)"

def rebrand_os_release(d):
    # r10 is "LANXIU Linux 1.7.41"; rewrite to canonical 澜岫 (LANXIU) form, keep real version.
    return (
        'NAME="澜岫 (LANXIU)"\n'
        'VERSION="1.7.41"\n'
        'ID=lanxiu\n'
        'VERSION_ID="1.7.41"\n'
        'PRETTY_NAME="澜岫 (LANXIU) 1.7.41 (aarch64)"\n'
        'HOME_URL="https://example.invalid/lanxiu"\n'
        'BUG_REPORT_URL="https://example.invalid/lanxiu/bugs"\n'
    ).encode("utf-8")

def rebrand_store(d):
    s = d.replace(b"LANXIU Store", "澜岫 Store".encode("utf-8"))
    s = s.replace("LANXIU 软件中心".encode("utf-8"), "澜岫 软件中心".encode("utf-8"))
    return s

# Files whose user-visible "LANXIU" brand text must become 澜岫 (LANXIU).
# (Only uppercase LANXIU appears as brand; lowercase "lanxiu"/"qy*" are technical and kept.)
GENERIC = {
    "./etc/bash.bashrc",
    "./etc/profile.d/lanxiu.sh",
    "./etc/qyinit.d/qynotifd.unit",
    "./etc/qyinit.d/qyboot.unit",
}
def rebrand_generic(d):
    return d.replace(b"LANXIU", LANXIU.encode("utf-8"))

MODS = {
    "./etc/os-release": rebrand_os_release,
    "./usr/share/applications/qystore.desktop": rebrand_store,
}
for p in GENERIC:
    MODS[p] = rebrand_generic

with tarfile.open(SRC, "r:xz") as src, tarfile.open(DST, "w:xz", preset=6) as dst:
    count = 0
    existing = set()
    for m in src:
        existing.add(m.name)
        if m.isdir() or m.issym() or m.islnk():
            dst.addfile(m); count += 1; continue
        f = src.extractfile(m)
        if m.name in MODS:
            data = MODS[m.name](f.read())
            m.size = len(data)
            dst.addfile(m, io.BytesIO(data))
        else:
            dst.addfile(m, f)
        count += 1
    # usrmerge symlinks: r10 is flat (no top-level lib/bin/sbin); loader lives in usr/lib.
    for link, target in (("lib", "usr/lib"), ("bin", "usr/bin"), ("sbin", "usr/sbin")):
        linkname = "./" + link
        if linkname in existing:
            continue
        if ("./" + target) not in existing:
            continue
        ti = tarfile.TarInfo(linkname)
        ti.type = tarfile.SYMTYPE
        ti.linkname = target
        ti.mode = 0o777
        dst.addfile(ti)
        existing.add(linkname)
        count += 1
        print("added usrmerge symlink", linkname, "->", target)
    print("total members written:", count)

# verify
with tarfile.open(DST, "r:xz") as t:
    for name in ("./etc/os-release", "./usr/share/applications/qystore.desktop"):
        d = t.extractfile(name).read().decode("utf-8", "replace")
        print("\n==== %s ====\n%s" % (name, d))
    # sanity: proot fix artifact must survive
    assert t.getmember("./etc/ld.so.cache").size > 0, "ld.so.cache missing!"
    print("\nld.so.cache present (size=%d) OK" % t.getmember("./etc/ld.so.cache").size)
    # confirm no stray uppercase LANXIU brand remains in the key files
    remain = []
    for m in t.getmembers():
        if m.name in MODS and m.name != "./etc/os-release":
            c = t.extractfile(m).read()
            if b"LANXIU" in c:
                remain.append(m.name)
    print("files still containing 'LANXIU':", remain or "none")
print("\nWROTE", DST)
