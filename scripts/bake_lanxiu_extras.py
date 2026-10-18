#!/usr/bin/env python3
# Bake extra components into the LANXIU rootfs tar.xz:
#   dash (from Ubuntu Noble base), zsh + zsh-common, fish + fish-common,
#   libcap2 (flat /usr/lib), node v22.11.0 (-> /usr/local), pip 25.0.1 (-> site-packages),
#   svc service manager (/usr/local/bin/svc).
# Reuses the streaming preset=6 repackage pattern from rebrand_r10.py
# (preserves dir/symlink/hardlink members, Android-weak-xz compatible).
import tarfile, io, os, sys, zipfile, struct
import zstandard

ROOT = r"D:/Calw OS-project/QuroAI"
SRC_ASSET = os.path.join(ROOT, r"terminal-core/src/main/assets/lanxiu-aarch64-rootfs-20261017.tar.xz")
UBUNTU    = os.path.join(ROOT, r"terminal-core/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz")
DEB_DIR   = r"D:/CalwOS-build/tmp/debs"
NODE_TAR  = r"D:/CalwOS-build/tmp/node.tar.xz"
PIP_WHL   = r"D:/CalwOS-build/tmp/pip.whl"
DST       = SRC_ASSET + ".tmp"
SITE      = "./usr/lib/python3.13/site-packages/"
NODE_PREFIX = "node-v22.11.0-linux-arm64/"

def elf_needed(data):
    if data[:4] != b"\x7fELF":
        return []
    ei_class = data[4]
    if ei_class != 2:  # only 64-bit here
        return []
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    loads = []          # (vaddr, file_offset, filesz)
    dyn_off = dyn_size = strtab_vaddr = strtab_size = None
    off = e_phoff
    for _ in range(e_phnum):
        seg = data[off:off+e_phentsize]
        p_type = struct.unpack_from("<I", seg, 0)[0]
        p_offset = struct.unpack_from("<Q", seg, 8)[0]
        p_vaddr = struct.unpack_from("<Q", seg, 0x10)[0]
        p_filesz = struct.unpack_from("<Q", seg, 0x20)[0]
        if p_type == 1:      # PT_LOAD
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:    # PT_DYNAMIC
            dyn_off = p_offset; dyn_size = p_filesz
        off += e_phentsize
    if dyn_off is None:
        return []
    dyn = data[dyn_off:dyn_off+dyn_size]
    needs_vaddr = []
    for j in range(0, len(dyn), 16):
        d_tag = struct.unpack_from("<Q", dyn, j)[0]
        d_val = struct.unpack_from("<Q", dyn, j+8)[0]
        if d_tag == 1:       # DT_NEEDED
            needs_vaddr.append(d_val)
        elif d_tag == 5:     # DT_STRTAB (virtual address!)
            strtab_vaddr = d_val
        elif d_tag == 10:    # DT_STRSZ
            strtab_size = d_val
    if strtab_vaddr is None:
        return []
    # translate strtab virtual address -> file offset via PT_LOAD
    strtab_off = None
    for (v, o, fs) in loads:
        if v <= strtab_vaddr < v + fs:
            strtab_off = strtab_vaddr - v + o
            break
    if strtab_off is None:
        return []
    sd = data[strtab_off:strtab_off+strtab_size]
    needs = []
    for v in needs_vaddr:
        end = sd.find(b"\x00", v)
        needs.append(sd[v:end].decode())
    return needs

injections = []  # list of (TarInfo, data_bytes_or_None_for_symlink)

def add_file(ti_name, mode, data, mtime=0):
    ti = tarfile.TarInfo(ti_name)
    ti.mode = mode; ti.size = len(data); ti.mtime = mtime
    ti.type = tarfile.REGTYPE; ti.uid = 0; ti.gid = 0
    injections.append((ti, data))

def add_symlink(ti_name, linkname, mode=0o777):
    ti = tarfile.TarInfo(ti_name)
    ti.mode = mode; ti.type = tarfile.SYMTYPE; ti.linkname = linkname
    ti.size = 0; ti.uid = 0; ti.gid = 0
    injections.append((ti, None))

def add_from_ti(src_ti, data, remap=None):
    name = remap(src_ti.name) if remap else src_ti.name
    if src_ti.issym() or src_ti.islnk():
        add_symlink(name, src_ti.linkname, src_ti.mode)
    else:
        # data provided
        ti = tarfile.TarInfo(name)
        ti.mode = src_ti.mode; ti.size = len(data); ti.mtime = src_ti.mtime
        ti.type = tarfile.REGTYPE; ti.uid = 0; ti.gid = 0
        injections.append((ti, data))

# ---- dash from Ubuntu Noble base rootfs ----
print("== dash from Ubuntu base ==")
tu = tarfile.open(UBUNTU, "r:xz")
dm = tu.getmember("ubuntu-noble-aarch64/usr/bin/dash")
add_from_ti(dm, tu.extractfile(dm).read(), remap=lambda n: "./usr/bin/dash")
print("  dash bytes:", len(injections[-1][1]))

# ---- .deb extraction ----
def iter_deb(path):
    data = open(path, "rb").read()
    off = 8
    members = {}
    while off < len(data):
        hdr = data[off:off+60]
        if len(hdr) < 60:
            break
        name = hdr[0:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip() or 0)
        off += 60
        content = data[off:off+size]; off += size
        if size % 2:
            off += 1
        members[name] = content
    raw = members["data.tar.zst"]
    dctx = zstandard.ZstdDecompressor()
    reader = dctx.stream_reader(io.BytesIO(raw))
    decompressed = reader.read()  # read all (frame has no content-size hint)
    tf = tarfile.open(fileobj=io.BytesIO(decompressed))
    for m in tf.getmembers():
        yield m, tf

print("== debs ==")
deb_specs = [
    ("zsh.deb",        lambda n: n.startswith("./bin/zsh") or n.startswith("./usr/lib/aarch64-linux-gnu/zsh"),
                       lambda n: n.replace("./bin/", "./usr/bin/", 1)),
    ("zsh-common.deb", lambda n: n.startswith("./usr/share/zsh"), None),
    ("fish.deb",       lambda n: n.startswith("./usr/bin/fish") or n.startswith("./usr/lib/aarch64-linux-gnu/fish"), None),
    ("fish-common.deb", lambda n: n.startswith("./usr/share/fish"), None),
    ("libcap2.deb",    None, lambda n: n.replace("./usr/lib/aarch64-linux-gnu/", "./usr/lib/")),
]
for deb, filt, remap in deb_specs:
    cnt = 0
    for m, tf in iter_deb(os.path.join(DEB_DIR, deb)):
        if m.isdir():
            continue
        if filt and not filt(m.name):
            continue
        is_link = m.issym() or m.islnk()
        data = None if is_link else tf.extractfile(m).read()
        add_from_ti(m, data, remap=remap)
        cnt += 1
    print(f"  {deb}: {cnt} members")

# ---- node ----
print("== node -> /usr/local ==")
tn = tarfile.open(NODE_TAR, "r:xz")
nmembers = tn.getmembers()
nmap = {m.name: m for m in nmembers}
n_cnt = 0
for m in nmembers:
    if not m.name.startswith(NODE_PREFIX):
        continue
    rel = m.name[len(NODE_PREFIX):]
    if rel == "" or rel.endswith("/"):
        continue
    newname = "./usr/local/" + rel
    if m.issym():
        add_symlink(newname, m.linkname, m.mode)
        n_cnt += 1
        continue
    if m.islnk():  # hardlink: copy data from the linked regular file
        tgt = m.linkname
        if not tgt.startswith(NODE_PREFIX):
            # resolve relative to this member's dir
            import posixpath
            base = posixpath.dirname(m.name)
            tgt = posixpath.normpath(posixpath.join(base, tgt))
        tm = nmap.get(tgt)
        if tm is None:
            print("  WARN hardlink target missing:", m.name, "->", tgt)
            continue
        data = tn.extractfile(tm).read()
    else:
        ef = tn.extractfile(m)
        if ef is None:
            print("  WARN extractfile None (skip):", m.name, "type", m.type)
            n_cnt += 1
            continue
        data = ef.read()
    ti = tarfile.TarInfo(newname)
    ti.mode = m.mode; ti.size = len(data); ti.mtime = m.mtime
    ti.type = tarfile.REGTYPE; ti.uid = 0; ti.gid = 0
    injections.append((ti, data))
    n_cnt += 1
print(f"  node members: {n_cnt}")

# ---- pip wheel -> site-packages ----
print("== pip -> site-packages ==")
z = zipfile.ZipFile(PIP_WHL)
p_cnt = 0
for n in z.namelist():
    if n.endswith("/"):
        continue
    data = z.read(n)
    info = z.getinfo(n)
    mode = (info.external_attr >> 16) & 0o7777
    if mode == 0:
        mode = 0o644
    add_file(SITE + n, mode, data)
    p_cnt += 1
print(f"  pip files: {p_cnt}")

# ---- svc service manager ----
SVC = r'''#!/bin/sh
# svc: 极简常驻服务管理器 for LANXIU (proot 环境, 无 systemd)
# 设计要点: 用 setsid 脱离 exec 父进程; 用 `echo $$ > pidfile; exec $SVC_CMD` 拿到真实 pid
#          (修复 setsid $! 是 setsid 自身 pid 而非服务真 pid 的坑)
# 用法: svc start <name> "<cmd> [args]" | svc stop <name> | svc restart <name> "<cmd>"
#       svc status <name> | svc list
SVCDIR="${SVC_DIR:-$HOME/.svc}"
mkdir -p "$SVCDIR" 2>/dev/null
cmd="$1"; shift 2>/dev/null || true
case "$cmd" in
  start)
    name="$1"; shift 2>/dev/null || true
    full="$*"
    [ -z "$name" ] && { echo "usage: svc start <name> \"<cmd> [args]\""; exit 1; }
    if [ -f "$SVCDIR/$name.pid" ]; then
      opid=$(cat "$SVCDIR/$name.pid" 2>/dev/null)
      if [ -n "$opid" ] && kill -0 "$opid" 2>/dev/null; then
        echo "already running name=$name pid=$opid"; exit 0
      fi
    fi
    export SVC_PIDFILE="$SVCDIR/$name.pid"
    export SVC_CMD="$full"
    setsid sh -c 'echo $$ > "$SVC_PIDFILE"; exec $SVC_CMD' > "$SVCDIR/$name.log" 2>&1 &
    sleep 0.4
    pid=$(cat "$SVCDIR/$name.pid" 2>/dev/null)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "started name=$name pid=$pid"
    else
      echo "FAILED to start (see $SVCDIR/$name.log):"; cat "$SVCDIR/$name.log" 2>/dev/null; exit 1
    fi
    ;;
  stop)
    name="$1"
    pid=$(cat "$SVCDIR/$name.pid" 2>/dev/null)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null; sleep 0.2; kill -9 "$pid" 2>/dev/null
      echo "stopped $name (pid=$pid)"
    else
      pkill -f "SVC_CMD=.*$name" 2>/dev/null
      echo "stopped $name (via pkill)"
    fi
    rm -f "$SVCDIR/$name.pid"
    ;;
  restart)
    "$0" stop "$1"; sleep 0.3
    ;;
  status)
    name="$1"; pid=$(cat "$SVCDIR/$name.pid" 2>/dev/null)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then echo "running name=$name pid=$pid"; else echo "not running name=$name"; fi
    ;;
  list)
    found=0
    for f in "$SVCDIR"/*.pid; do [ -e "$f" ] || continue; found=1
      n=$(basename "$f" .pid); p=$(cat "$f")
      if kill -0 "$p" 2>/dev/null; then echo "RUN  $n pid=$p"; else echo "DOWN $n (stale pid=$p)"; fi
    done
    [ "$found" = 0 ] && echo "(no services)"
    ;;
  *) echo "svc <start|stop|restart|status|list> ..." ;;
esac
'''
add_file("./usr/local/bin/svc", 0o755, SVC.encode("utf-8"))

# ---- verify NEEDED libs for the new ELF binaries ----
print("== NEEDED lib check ==")
# collect basenames of all .so present in existing LANXIU + injected
present_libs = set()
with tarfile.open(SRC_ASSET, "r:xz") as src:
    for m in src.getmembers():
        b = os.path.basename(m.name)
        if ".so" in b:
            present_libs.add(b)
            if ".so." in b:
                present_libs.add(b.split(".so.")[0] + ".so")
for ti, data in injections:
    if ti.name.endswith(".so") or ".so." in ti.name:
        b = os.path.basename(ti.name)
        present_libs.add(b)
        if ".so." in b:
            present_libs.add(b.split(".so.")[0] + ".so")

def need_ok(need):
    base = need.split(".so.")[0] + ".so"
    return need in present_libs or base in present_libs or need in present_libs

for ti, data in injections:
    if data and len(data) > 4 and data[:4] == b"\x7fELF":
        needed = elf_needed(data)
        missing = [n for n in needed if not need_ok(n)]
        if missing:
            print(f"  MISSING libs for {ti.name}: {missing}")
        else:
            print(f"  OK {ti.name} NEEDED={needed}")
# specifically check the main shells/node
for ti, data in injections:
    if ti.name in ("./usr/bin/dash", "./usr/bin/zsh", "./usr/bin/fish", "./usr/local/bin/node"):
        needed = elf_needed(data) if data else []
        missing = [n for n in needed if not need_ok(n)]
        print(f"  {'MISSING' if missing else 'OK'} {ti.name} -> {missing or needed}")

# ---- repackage ----
print("== repackage (preset=6) ==")
existing = set()
skip = 0
with tarfile.open(SRC_ASSET, "r:xz") as src, tarfile.open(DST, "w:xz", preset=6) as dst:
    for m in src:
        existing.add(m.name)
        if m.isdir() or m.issym() or m.islnk():
            dst.addfile(m)
        else:
            dst.addfile(m, src.extractfile(m))
    added = 0
    for ti, data in injections:
        if ti.name in existing:
            print("  SKIP (already in tar):", ti.name)
            skip += 1
            continue
        if data is None:
            dst.addfile(ti)
        else:
            dst.addfile(ti, io.BytesIO(data))
        added += 1
    print(f"  added={added} skipped={skip}")

# ---- final verification ----
print("== final verify ==")
expect = ["./usr/bin/dash", "./usr/bin/zsh", "./usr/bin/fish",
          "./usr/local/bin/node", "./usr/local/bin/npm", "./usr/local/bin/npx",
          "./usr/lib/libcap.so.2.66", "./usr/lib/libcap.so.2",
          "./usr/local/bin/svc",
          SITE + "pip/__init__.py", SITE + "pip-25.0.1.dist-info/METADATA"]
with tarfile.open(DST, "r:xz") as t:
    names = set(t.getnames())
    for e in expect:
        if e in names:
            m = t.getmember(e)
            kind = "sym" if (m.issym() or m.islnk()) else f"{m.size}B"
            print(f"  PRESENT {e} ({kind})")
        else:
            print(f"  ABSENT  {e}")
    print("  total members:", len(names))

import hashlib
h = hashlib.sha256()
with open(DST, "rb") as f:
    for b in iter(lambda: f.read(1<<20), b""):
        h.update(b)
print("DST sha256:", h.hexdigest())
print("WROTE", DST)
