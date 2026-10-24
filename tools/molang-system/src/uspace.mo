# uspace.mo —— **交叉编译出来的最小用户空间**
#
# mo-target: cfs-aarch64   # 非原生后端，projcheck 跳过原生编译
#
#   bash tools/android_native.sh examples/uspace.mo /tmp/uspace.arm64
#   adb push /tmp/uspace.arm64 /data/local/tmp/uspace
#   adb shell /data/local/tmp/uspace
#
# 它回答的是「自带运行和执行环境」到底能不能成立：
#   一个**推上去就能跑**的静态 ARM64 ELF，起来之后自己
#   建根目录 → 写启动脚本 → 读回来 → 逐行解释 → 装包 → 执行包 → 卸包。
#   全程不依赖 libc / fork / exec / git / 任何已存在的运行时。
#
# 为什么这比「一个能跑的程序」更进一步
# ------------------------------------
# 单程序只能证明「这段代码能在 ARM64 上跑」。用户空间要证明的是
# **它能自我维持**：状态写在自己建的文件系统里、指令从自己写的文件里读回来、
# 包由自己装、自己验、自己执行。所以本程序刻意把「启动脚本」写进文件再读回来
# 执行 —— 若直接执行内存里的字符串，读文件那一段就是死代码，
# 而「能在目标机上持久化状态」恰恰是用户空间与 demo 的分界线。
#
# 判据（每条都必须能区分「真做了」与「碰巧」）
# ------------------------------------------
#   · 写 init.rc 的字节数 == 读回的字节数，且**逐字节相同**
#     —— 只比对长度的话，写进去的是垃圾也照样通过
#   · 解释执行的命令条数 == 脚本里的非空行数
#     —— 少执行一行也数不出来，等于没验
#   · install 的包 run 出来必须是 42（6*7）
#     —— 若 VM 没跑、直接返回常数，这个数也能被编造出来，所以额外要求
#        「篡改 code 后 run 必须失败」，让常数路径被堵死
#   · del 之后 ls 的包数必须减一
#     —— 只删注册表不删文件，或只删文件不删注册表，都能让 ls 不变
#
# 依赖的系统调用（全部经 x86 → asm-generic 号表翻译）
# --------------------------------------------------
#   write(1) · open(2) · read(0) · close(3) · mkdir(83) · unlink(87)

# ---------------------------- 字符串工具 ----------------------------

fn slen(s: ptr) -> i64 {
    let i: i64 = 0;
    while load8(s + i) != 0 { i = i + 1; }
    return i;
}

fn scopy(dst: ptr, src: ptr) -> i64 {
    let i: i64 = 0;
    while load8(src + i) != 0 {
        store8(dst + i, load8(src + i));
        i = i + 1;
    }
    store8(dst + i, 0);
    return i;
}

# 追加。启动脚本要用它逐行拼 —— 硬编码偏移量一旦算错一位，
# 字符串就在中间被截断，后面的命令全部消失而**不报错**（本文件第一版
# 正是这样：写进去 40 字节、读回 40 字节、逐字节相同，却只跑了 2 条命令）。
fn sapp(dst: ptr, src: ptr) -> i64 {
    let i: i64 = slen(dst);
    let j: i64 = 0;
    while load8(src + j) != 0 {
        store8(dst + i + j, load8(src + j));
        j = j + 1;
    }
    store8(dst + i + j, 0);
    return i + j;
}

fn seq(a: ptr, b: ptr) -> i64 {
    let i: i64 = 0;
    while load8(a + i) != 0 {
        if load8(a + i) != load8(b + i) { return 0; }
        i = i + 1;
    }
    if load8(b + i) != 0 { return 0; }
    return 1;
}

fn s2i(s: ptr) -> i64 {
    let i: i64 = 0;
    let v: i64 = 0;
    let c: i64 = load8(s);
    if c == 45 {
        i = 1;
        while load8(s + i) != 0 {
            v = v * 10 + (load8(s + i) - 48);
            i = i + 1;
        }
        return 0 - v;
    }
    while load8(s + i) != 0 {
        v = v * 10 + (load8(s + i) - 48);
        i = i + 1;
    }
    return v;
}

# ---------------------------- 文件 IO ----------------------------

# 写 n 字节。返回实际写入字节数，失败返回负数。
fn fput(path: ptr, buf: ptr, n: i64) -> i64 {
    let fd: i64 = syscall(2, path, 577, 420, 0, 0);
    if fd < 0 { return fd; }
    let w: i64 = syscall(1, fd, buf, n, 0, 0, 0);
    syscall(3, fd, 0, 0, 0, 0, 0);
    return w;
}

# 读最多 cap 字节。返回读到的字节数，失败返回负数。
fn fget(path: ptr, buf: ptr, cap: i64) -> i64 {
    let fd: i64 = syscall(2, path, 0, 0, 0, 0);
    if fd < 0 { return fd; }
    let r: i64 = syscall(0, fd, buf, cap, 0, 0, 0);
    syscall(3, fd, 0, 0, 0, 0, 0);
    return r;
}

fn fmkdir(path: ptr) -> i64 {
    return syscall(83, path, 493, 0, 0, 0);
}

fn fdel(path: ptr) -> i64 {
    return syscall(87, path, 0, 0, 0, 0);
}

# ---------------------------- 校验和（FNV-1a 32）----------------------------

fn fnv(buf: ptr, n: i64) -> i64 {
    let h: i64 = 2166136261;
    let i: i64 = 0;
    while i < n {
        h = h ^ load8(buf + i);
        h = h * 16777619;
        if h > 4294967295 {
            h = h - 4294967296 * (h / 4294967296);
        }
        i = i + 1;
    }
    return h;
}

# ---------------------------- 栈式 VM ----------------------------
#   1 = push imm32(LE)   2 = add   3 = out   4 = mul   0 = halt
#   其它 = 未知算子，返回 -1（不静默跳过）

var VS: [64] i64;
var VOUT: [8] i64;
var VM_OUT: i64;

fn vm_run(code: ptr, n: i64) -> i64 {
    let i: i64 = 0;
    let sp: i64 = 0;
    let op: i64 = 0;
    let v: i64 = 0;
    let k: i64 = 0;
    let mul: i64 = 1;
    VM_OUT = 0;
    while i < n {
        op = load8(code + i);
        if op == 0 { return 0; }
        if op == 1 {
            if i + 5 > n { return -4; }        # 立即数跨过末尾 = 包被截断
            if sp >= 64 { return -3; }         # 栈满 = 恶意压栈
            v = 0; mul = 1; k = 0;
            while k < 4 {
                v = v + load8(code + i + 1 + k) * mul;
                mul = mul * 256;
                k = k + 1;
            }
            VS[sp] = v;
            sp = sp + 1;
            i = i + 5;
        } else {
            if op == 2 {
                if sp < 2 { return -2; }
                VS[sp - 2] = VS[sp - 2] + VS[sp - 1];
                sp = sp - 1;
                i = i + 1;
            } else {
                if op == 4 {
                    if sp < 2 { return -2; }
                    VS[sp - 2] = VS[sp - 2] * VS[sp - 1];
                    sp = sp - 1;
                    i = i + 1;
                } else {
                    if op == 3 {
                        if sp < 1 { return -2; }
                        if VM_OUT < 8 {
                            VOUT[VM_OUT] = VS[sp - 1];
                            VM_OUT = VM_OUT + 1;
                        }
                        sp = sp - 1;
                        i = i + 1;
                    } else {
                        return -1;
                    }
                }
            }
        }
    }
    return 0;
}

# ---------------------------- 包存储 ----------------------------
#   每槽 128 字节：name+0(32) · ver+32(16) · clen+48 · ck+56 · code+64(64)

var PK: [512] i64;
var PN: i64;
var PKMAX: i64;

fn pk_slot(i: i64) -> i64 {
    return i * 128;
}

fn pk_find(nm: ptr) -> i64 {
    let i: i64 = 0;
    while i < PN {
        if seq(&PK + pk_slot(i), nm) == 1 { return i; }
        i = i + 1;
    }
    return 0 - 1;
}

fn pk_install(nm: ptr, ver: ptr, code: ptr, clen: i64) -> i64 {
    if PN >= PKMAX { return -7; }
    if clen > 64 { return -7; }
    if clen < 1 { return -8; }
    let s: i64 = pk_slot(PN);
    scopy(&PK + s, nm);
    scopy(&PK + s + 32, ver);
    store64(&PK + s + 48, clen);
    store64(&PK + s + 56, fnv(code, clen));
    let i: i64 = 0;
    while i < clen {
        store8(&PK + s + 64 + i, load8(code + i));
        i = i + 1;
    }
    PN = PN + 1;
    return PN;
}

# 返回 0 表示执行成功；负数表示校验失败或执行出错。
fn pk_run(nm: ptr) -> i64 {
    let i: i64 = pk_find(nm);
    if i < 0 { return 0 - 6; }
    let s: i64 = pk_slot(i);
    let clen: i64 = load64(&PK + s + 48);
    # 校验和必须**先验**：验不过就不执行，而不是执行完再说。
    if fnv(&PK + s + 64, clen) != load64(&PK + s + 56) { return 0 - 6; }
    let r: i64 = vm_run(&PK + s + 64, clen);
    if r < 0 { return r; }
    if VM_OUT < 1 { return 0 - 5; }
    return VOUT[0];
}

fn pk_del(nm: ptr) -> i64 {
    let i: i64 = pk_find(nm);
    if i < 0 { return 0 - 6; }
    # 用最后一个包覆盖被删槽位。只清名字不清内容的话，
    # 内容仍留在表里，之后的新包可能读到它的残留 —— 不报错，但会算错。
    let last: i64 = PN - 1;
    if i != last {
        let a: i64 = pk_slot(i);
        let b: i64 = pk_slot(last);
        let j: i64 = 0;
        while j < 128 {
            store8(&PK + a + j, load8(&PK + b + j));
            j = j + 1;
        }
    }
    PN = last;
    return 0;
}

# ---------------------------- 命令解释器 ----------------------------

var TK0: [64] i64;
var TK1: [64] i64;
var TK2: [64] i64;
var LINE: [160] i64;
var CBUF: [96] i64;
var RBUF: [256] i64;
var NCMD: i64;

fn sp_split(line: ptr) -> i64 {
    let i: i64 = 0;
    let t: i64 = 0;
    let j: i64 = 0;
    let c: i64 = 0;
    store8(&TK0, 0);
    store8(&TK1, 0);
    store8(&TK2, 0);
    while load8(line + i) != 0 {
        c = load8(line + i);
        if c == 32 {
            if j > 0 {
                if t == 0 { store8(&TK0 + j, 0); }
                if t == 1 { store8(&TK1 + j, 0); }
                if t == 2 { store8(&TK2 + j, 0); }
                t = t + 1;
                j = 0;
                if t > 2 { return t; }
            }
        } else {
            if t == 0 { store8(&TK0 + j, c); }
            if t == 1 { store8(&TK1 + j, c); }
            if t == 2 { store8(&TK2 + j, c); }
            j = j + 1;
        }
        i = i + 1;
    }
    if t == 0 { store8(&TK0 + j, 0); }
    if t == 1 { store8(&TK1 + j, 0); }
    if t == 2 { store8(&TK2 + j, 0); }
    if j > 0 { t = t + 1; }
    return t;
}

# 把 "1,6,0,0,0,1,7,0,0,0,4,3,0" 这样的十进制字节串解析成字节。
fn parseb(s: ptr, dst: ptr) -> i64 {
    let i: i64 = 0;
    let n: i64 = 0;
    let st: i64 = 0;
    let c: i64 = 0;
    let len: i64 = slen(s);
    while i <= len {
        c = load8(s + i);
        if c == 44 {
            store8(dst + n, st);
            n = n + 1;
            st = 0;
        } else {
            if c == 0 {
                store8(dst + n, st);
                n = n + 1;
            } else {
                st = st * 10 + (c - 48);
            }
        }
        i = i + 1;
    }
    return n;
}

fn do_line(line: ptr) -> i64 {
    let nt: i64 = sp_split(line);
    let r: i64 = 0;
    let n: i64 = 0;
    if nt < 1 { return 0; }
    NCMD = NCMD + 1;
    if seq(&TK0, "echo") == 1 {
        print(&TK1);
        print_nl();
        return 1;
    }
    if seq(&TK0, "write") == 1 {
        return fput(&TK1, &TK2, slen(&TK2));
    }
    if seq(&TK0, "cat") == 1 {
        r = fget(&TK1, &RBUF, 200);
        if r > 0 {
            store8(&RBUF + r, 0);
            print(&RBUF);
        }
        print_nl();
        return r;
    }
    if seq(&TK0, "install") == 1 {
        n = parseb(&TK2, &CBUF);
        r = pk_install(&TK1, "1.0.0", &CBUF, n);
        return r;
    }
    if seq(&TK0, "run") == 1 {
        return pk_run(&TK1);
    }
    if seq(&TK0, "ls") == 1 {
        return PN;
    }
    if seq(&TK0, "del") == 1 {
        return pk_del(&TK1);
    }
    return 0 - 9;
}

# 逐行执行脚本。返回执行过的非空行数。
fn runscript(s: ptr) -> i64 {
    let i: i64 = 0;
    let n: i64 = slen(s);
    let j: i64 = 0;
    let k: i64 = 0;
    let stop: i64 = 0;
    while i < n {
        j = 0;
        stop = 0;
        while stop == 0 {
            if i + j >= n {
                stop = 1;
            } else {
                if load8(s + i + j) == 10 { stop = 1; }
                else { j = j + 1; }
            }
        }
        if j > 0 {
            k = 0;
            while k < j {
                store8(&LINE + k, load8(s + i + k));
                k = k + 1;
            }
            store8(&LINE + j, 0);
            do_line(&LINE);
        }
        i = i + j + 1;
    }
    return NCMD;
}

# ---------------------------- 主流程 ----------------------------

var ROOTD: [32] i64;
var RCPATH: [48] i64;
var SCRIPTO: [512] i64;
var SCRIPTR: [512] i64;

fn main() -> i64 {
    PKMAX = 32;
    PN = 0;
    NCMD = 0;

    print("=== mo uspace : 最小用户空间 ===\n");

    # 1) 建根 —— 用户空间自己的根，不依赖宿主任何目录
    scopy(&ROOTD, "/mo");
    let r1: i64 = fmkdir(&ROOTD);
    print("[1] mkdir /mo     ret=");
    print_i64(r1);
    print_nl();

    # 2) 写启动脚本（这就是「用户空间自带配置」）
    scopy(&RCPATH, "/mo/init.rc");
    scopy(&SCRIPTO, "echo boot\n");
    sapp(&SCRIPTO, "write /mo/hi.txt MO-USERSPACE\n");
    sapp(&SCRIPTO, "install demo 1,6,0,0,0,1,7,0,0,0,4,3,0\n");
    sapp(&SCRIPTO, "run demo\n");
    sapp(&SCRIPTO, "cat /mo/hi.txt\n");
    sapp(&SCRIPTO, "echo done\n");
    let sl: i64 = slen(&SCRIPTO);
    let w: i64 = fput(&RCPATH, &SCRIPTO, sl);
    print("[2] write rc      bytes=");
    print_i64(w);
    print_nl();

    # 3) 读回来（证明状态真的落到了文件系统，而不是只在内存里）
    let rr: i64 = fget(&RCPATH, &SCRIPTR, 400);
    print("[3] read rc       bytes=");
    print_i64(rr);
    print_nl();

    # 逐字节比对：只比长度的话，写进去的是垃圾也照样通过
    let same: i64 = 1;
    if rr != sl { same = 0; }
    let q: i64 = 0;
    while q < sl {
        if load8(&SCRIPTO + q) != load8(&SCRIPTR + q) { same = 0; }
        q = q + 1;
    }
    print("[3] rc identical  =");
    print_i64(same);
    print_nl();

    # 4) 逐行解释执行
    store8(&SCRIPTR + rr, 0);
    let nc: i64 = runscript(&SCRIPTR);
    print("[4] cmds run      =");
    print_i64(nc);
    print_nl();

    # 5) 包确实装上了、跑出 42
    let rv: i64 = pk_run("demo");
    print("[5] run demo      out=");
    print_i64(rv);
    print_nl();

    # 6) 篡改 code 后必须跑不出来（堵死「返回常数」的假实现）
    let si: i64 = pk_find("demo");
    if si >= 0 {
        store8(&PK + pk_slot(si) + 64 + 1, 9);
    }
    let rv2: i64 = pk_run("demo");
    print("[6] tamper run    ret=");
    print_i64(rv2);
    print_nl();

    # 7) 卸包后包数减一
    let n1: i64 = PN;
    pk_del("demo");
    let n2: i64 = PN;
    print("[7] pkgs before   =");
    print_i64(n1);
    print_nl();
    print("[7] pkgs after    =");
    print_i64(n2);
    print_nl();
    print("[7] find removed  =");
    print_i64(pk_find("demo"));
    print_nl();

    fdel(&RCPATH);
    return 0;
}
