import tarfile, io, os

SRC = "terminal-core/src/main/assets/lanxiu-aarch64-rootfs-20261017.tar.xz"
DST = "terminal-core/src/main/assets/lanxiu-aarch64-rootfs-20261017.preset6.tar.xz.tmp"

def rebrand_os_release(d):
    s = d.decode("utf-8")
    s = s.replace('NAME="Qiyuan Linux"', 'NAME="澜岫 (LANXIU)"')
    s = s.replace('PRETTY_NAME="Qiyuan Linux 0.2.0"', 'PRETTY_NAME="澜岫 (LANXIU) 0.2.0"')
    s = s.replace("ID=qiyuan", "ID=lanxiu")
    s = s.replace("https://example.invalid/qiyuan", "https://example.invalid/lanxiu")
    return s.encode("utf-8")

def rebrand_hostname(d):
    return d.replace(b"qiyuan", b"lanxiu")

def rebrand_desktop(d):
    s = d.replace(b"Qiyuan Store", "澜岫 Store".encode("utf-8"))
    s = s.replace("启元软件中心".encode("utf-8"), "澜岫软件中心".encode("utf-8"))
    return s

def rebrand_gtkcss(d):
    return d.replace(b"/usr/share/themes/qiyuan/", b"/usr/share/themes/lanxiu/")

def rebrand_qy(d):
    # 通用：把残留的用户可见「启元」改为「澜岫」（utf-8 同为 6 字节，二进制内替换也安全）
    return d.replace("启元".encode("utf-8"), "澜岫".encode("utf-8"))

MODS = {
    "./etc/os-release": rebrand_os_release,
    "./etc/hostname": rebrand_hostname,
    "./usr/share/applications/qystore.desktop": rebrand_desktop,
    "./usr/share/themes/lanxiu/gtk-3.0/gtk.css": rebrand_gtkcss,
    "./etc/ld.so.conf.d/qiyuan-aarch64.conf": rebrand_qy,
    "./etc/xdg/weston/weston.ini": rebrand_qy,
    "./usr/include/qydemo.h": rebrand_qy,
    "./usr/bin/qyinit": rebrand_qy,
}

with tarfile.open(SRC, "r:xz") as src, tarfile.open(DST, "w:xz", preset=6) as dst:
    count = 0
    for m in src:
        if m.isdir() or m.issym() or m.islnk():
            dst.addfile(m)
            count += 1
            continue
        f = src.extractfile(m)
        if m.name in MODS:
            data = MODS[m.name](f.read())
            m.size = len(data)
            dst.addfile(m, io.BytesIO(data))
        else:
            dst.addfile(m, f)
        count += 1
    print("total members written:", count)

# verify modified content
with tarfile.open(DST, "r:xz") as t:
    for name in MODS:
        d = t.extractfile(name).read()
        print("\n==== %s ====" % name)
        print(d.decode("utf-8", "replace"))
