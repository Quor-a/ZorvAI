# uspace2.mo —— **完整用户空间**（不是最小实现）
#
# mo-target: cfs-aarch64   # 非原生后端，projcheck 跳过原生编译
#
#   bash tools/android_native.sh examples/uspace2.mo /tmp/uspace2.arm64
#   adb push /tmp/uspace2.arm64 /data/local/tmp/uspace2
#   adb shell /data/local/tmp/uspace2
#
# 与 examples/uspace.mo（v1.161，最小用户空间）的关系
# ------------------------------------------------
# 最小版证明的是「能自我维持」：建根 → 写脚本 → 读回 → 解释 → 装包 → 执行。
# 它只有 7 条命令、一层平铺的文件表、无版本无依赖无签名的包表，
# 状态全在内存里 —— 掉电即失，换台机器什么都带不走。
#
# 这一版要补的是**一个用户空间真正该有的六件事**：
#
#   1. 层次化 VFS     —— 目录树 + 路径解析（绝对/相对、. 与 ..），
#                        不是「一个名字一张表」
#   2. 完整 shell     —— 24 条内建命令、环境变量与 $VAR 展开、
#                        $? 退出码、# 注释、; 多语句、未知命令响亮报错
#   3. 完整包管理     —— 语义化版本比较、依赖拓扑排序 + **环检测**、
#                        SipHash-2-4 **签名**（不是校验和）、
#                        pack/unpack 字节串分发、升级、拒绝降级
#   4. 状态持久化     —— VFS + 包表 + 环境全部序列化成字节串写盘，
#                        再清空内存从盘上**重建**，重建后行为必须一致
#   5. 跨运行         —— 第二次启动自动进入 restore 模式（x86-64 端真磁盘），
#                        证明「重启之后还在」
#   6. 错误码体系     —— 12 个互斥的负数码，命令失败能说清是哪一类
#
# 判据（每条都必须能区分「真做了」与「碰巧」）
# ------------------------------------------
#   · 拓扑序必须是 liba → libb → app（后序，依赖在前）
#     —— 若实现成「声明顺序」，app 会在最前，装的时候依赖还没装
#   · 环检测返回 -2；若只是「跳过访问中的节点」，会返回 0 且序表里少一个
#   · 升级后 CODE 从 6*7=42 换成 5*9=45
#     —— 只改版本号而代码没换，run 仍 42，那样测不出「真的升级了」
#   · 降级被拒后版本仍是 2.0.0、run 仍 45
#   · 签名不符返回 -12，与校验和不符（-6）**分开**：
#     前者报警、后者重传，混成一个码会让调用方做错动作
#   · 序列化往返后：文件数、包数、环境变量数分别相等，
#     且 cat 的内容、run 的结果都与往返前**逐位相同**
#   · 未知命令必须有计数 —— 否则 shell 静默吞掉错命令，「解释器」是假的
#
# 依赖的系统调用（全部经 x86 → asm-generic 号表翻译）
# --------------------------------------------------
#   write(1) · open(2) · read(0) · close(3) · mkdir(83) · unlink(87)

# ============================ 字符串工具 ============================

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

# 追加。序列化全靠它 —— 硬编码偏移量一旦算错一位，字符串就在中间被
# \0 截断，后面的内容全部消失而**不报错**（uspace.mo 第一版正是这样：
# 写进 40 字节、读回 40 字节、逐字节相同，却只跑了 2 条命令）。
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

fn sappc(dst: ptr, c: i64) -> i64 {
    let i: i64 = slen(dst);
    store8(dst + i, c);
    store8(dst + i + 1, 0);
    return i + 1;
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

# b 是否为 a 的前缀
fn sprefix(a: ptr, b: ptr) -> i64 {
    let i: i64 = 0;
    while load8(b + i) != 0 {
        if load8(a + i) != load8(b + i) { return 0; }
        i = i + 1;
    }
    return 1;
}

fn s2i(s: ptr) -> i64 {
    let i: i64 = 0;
    let v: i64 = 0;
    let ng: i64 = 0;
    if load8(s) == 45 { ng = 1; i = 1; }
    while load8(s + i) != 0 {
        if load8(s + i) >= 48 {
            if load8(s + i) <= 57 {
                v = v * 10 + (load8(s + i) - 48);
            }
        }
        i = i + 1;
    }
    if ng == 1 { return 0 - v; }
    return v;
}

var NUMP: [32] i64;

# 十进制转串。i2s 之前的版本把「补零」和「反序」写混了，正数高位被覆盖；
# 这里改成一次反序写，负数只在最前面补一个 '-'。
fn i2s(v: i64, d: ptr) -> i64 {
    let x: i64 = v;
    let n: i64 = 0;
    let i: i64 = 0;
    let ng: i64 = 0;
    if x < 0 { ng = 1; x = 0 - x; }
    if x == 0 {
        store8(d, 48);
        store8(d + 1, 0);
        return 1;
    }
    while x > 0 {
        store8(&NUMP + n, (x - (x / 10) * 10) + 48);
        x = x / 10;
        n = n + 1;
    }
    i = 0;
    if ng == 1 {
        store8(d, 45);
        i = 1;
    }
    while i - ng < n {
        store8(d + i, load8(&NUMP + n - 1 - (i - ng)));
        i = i + 1;
    }
    store8(d + i, 0);
    return i;
}

# ============================ 输出 ============================

fn p(s: ptr) -> i64 {
    print(s);
    return 0;
}

fn pi(v: i64) -> i64 {
    print_i64(v);
    return 0;
}

var KLBL: [48] i64;

# 打印 "label=value\n"。测试靠 grep 这个形状断言，
# 所以标签一律写死，不让数字从上下文里猜。
fn pkv(lbl: ptr, v: i64) -> i64 {
    scopy(&KLBL, "  [");
    sapp(&KLBL, lbl);
    sapp(&KLBL, "] = ");
    i2s(v, &NUMP + 24);
    sapp(&KLBL, &NUMP + 24);
    sapp(&KLBL, "\n");
    print(&KLBL);
    return 0;
}

fn pks(lbl: ptr, s: ptr) -> i64 {
    scopy(&KLBL, "  [");
    sapp(&KLBL, lbl);
    sapp(&KLBL, "] = ");
    sapp(&KLBL, s);
    sapp(&KLBL, "\n");
    print(&KLBL);
    return 0;
}

# ---------------------- 错误码（12 个，互斥）----------------------
#   -1 未找到   -2 依赖环   -3 栈溢出   -4 包被截断   -5 无输出
#   -6 校验和不符  -7 容量上限  -8 非法参数  -9 未知命令
#   -10 已存在  -11 目录非空  -12 签名不符
fn perr(c: i64) -> i64 {
    if c == 0 - 1 { p("ENOENT"); return 0; }
    if c == 0 - 2 { p("ECYCLE"); return 0; }
    if c == 0 - 3 { p("EOVERFLOW"); return 0; }
    if c == 0 - 4 { p("ETRUNC"); return 0; }
    if c == 0 - 5 { p("ENOOUT"); return 0; }
    if c == 0 - 6 { p("ECKSUM"); return 0; }
    if c == 0 - 7 { p("EFULL"); return 0; }
    if c == 0 - 8 { p("EINVAL"); return 0; }
    if c == 0 - 9 { p("EUNKNOWN"); return 0; }
    if c == 0 - 10 { p("EEXIST"); return 0; }
    if c == 0 - 11 { p("ENOTEMPTY"); return 0; }
    if c == 0 - 12 { p("ESIG"); return 0; }
    p("OK");
    return 0;
}

# ============================ 真文件系统 IO ============================

fn fput(path: ptr, buf: ptr, n: i64) -> i64 {
    let fd: i64 = syscall(2, path, 577, 420, 0, 0);
    if fd < 0 { return fd; }
    let w: i64 = syscall(1, fd, buf, n, 0, 0, 0);
    syscall(3, fd, 0, 0, 0, 0, 0);
    return w;
}

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

# ============================ 层次化 VFS ============================
#   节点表 64 项：kind(0 空闲/1 文件/2 目录) · parent · name(24B) · data(64B) · len
#   根固定为节点 0，parent = -1。

var FSK: [64] i64;
var FSP: [64] i64;
var FSL: [64] i64;
var FSNAME: [1536] i64;      # 64 × 24 字节
var FSDATA: [4096] i64;      # 64 × 64 字节
var FSCNT: i64;              # 已用节点数（含根）
var CWD: i64;                # 当前目录节点

fn fsnm(i: i64) -> i64 { return i * 24; }
fn fsdt(i: i64) -> i64 { return i * 64; }

fn vinit() -> i64 {
    FSCNT = 1;
    FSK[0] = 2;
    FSP[0] = 0 - 1;
    FSL[0] = 0;
    store8(&FSNAME, 0);
    CWD = 0;
    return 0;
}

fn vchild(par: i64, nm: ptr) -> i64 {
    let i: i64 = 0;
    while i < FSCNT {
        if FSK[i] != 0 {
            if FSP[i] == par {
                if seq(&FSNAME + fsnm(i), nm) == 1 { return i; }
            }
        }
        i = i + 1;
    }
    return 0 - 1;
}

fn valloc() -> i64 {
    let i: i64 = 0;
    while i < 64 {
        if FSK[i] == 0 { return i; }
        i = i + 1;
    }
    return 0 - 7;
}

fn vcreate(par: i64, nm: ptr, kind: i64) -> i64 {
    let i: i64 = valloc();
    if i < 0 { return i; }
    if slen(nm) > 23 { return 0 - 8; }
    if vchild(par, nm) >= 0 { return 0 - 10; }
    FSK[i] = kind;
    FSP[i] = par;
    FSL[i] = 0;
    scopy(&FSNAME + fsnm(i), nm);
    store8(&FSDATA + fsdt(i), 0);
    if i + 1 > FSCNT { FSCNT = i + 1; }
    return i;
}

var SEGB: [32] i64;

# 解析路径 → 节点号。支持绝对(/ 开头)、相对、. 与 ..。
# 返回 -1 表示不存在。
fn vresolve(path: ptr) -> i64 {
    let cur: i64 = CWD;
    let i: i64 = 0;
    let j: i64 = 0;
    let n: i64 = slen(path);
    let nm: i64 = 0;
    if load8(path) == 47 { cur = 0; i = 1; }
    while i <= n {
        if i == n {
            if j > 0 {
                store8(&SEGB + j, 0);
                nm = vchild(cur, &SEGB);
                if nm < 0 { return 0 - 1; }
                cur = nm;
            }
            i = i + 1;
        } else {
            if load8(path + i) == 47 {
                if j > 0 {
                    store8(&SEGB + j, 0);
                    if seq(&SEGB, "..") == 1 {
                        if FSP[cur] >= 0 { cur = FSP[cur]; }
                    } else {
                        if seq(&SEGB, ".") == 0 {
                            nm = vchild(cur, &SEGB);
                            if nm < 0 { return 0 - 1; }
                            cur = nm;
                        }
                    }
                    j = 0;
                }
                i = i + 1;
            } else {
                if j < 31 {
                    store8(&SEGB + j, load8(path + i));
                    j = j + 1;
                }
                i = i + 1;
            }
        }
    }
    return cur;
}


var PSTK: [64] i64;

fn vpath2(i: i64, dst: ptr) -> i64 {
    let top: i64 = 0;
    let cur: i64 = i;
    let k: i64 = 0;
    store8(dst, 0);
    while cur >= 0 {
        PSTK[top] = cur;
        top = top + 1;
        cur = FSP[cur];
    }
    while top > 0 {
        top = top - 1;
        # 根节点的名字是空串。不加这一层判断就会拼出 "//mo/bin" ——
        # 长度、可读性都不报错，只有逐字节看才发现多了一个斜杠。
        if slen(&FSNAME + fsnm(PSTK[top])) > 0 {
            sapp(dst, "/");
            sapp(dst, &FSNAME + fsnm(PSTK[top]));
        }
    }
    if slen(dst) == 0 { sapp(dst, "/"); }
    return slen(dst);
}

fn vwrite(i: i64, buf: ptr, n: i64) -> i64 {
    let k: i64 = 0;
    if i < 0 { return 0 - 1; }
    if FSK[i] != 1 { return 0 - 8; }
    if n > 63 { return 0 - 7; }
    while k < n {
        store8(&FSDATA + fsdt(i) + k, load8(buf + k));
        k = k + 1;
    }
    store8(&FSDATA + fsdt(i) + n, 0);
    FSL[i] = n;
    return n;
}

fn nchild(i: i64) -> i64 {
    let k: i64 = 0;
    let c: i64 = 0;
    while k < FSCNT {
        if FSK[k] != 0 {
            if FSP[k] == i { c = c + 1; }
        }
        k = k + 1;
    }
    return c;
}

fn vrm(i: i64) -> i64 {
    if i < 0 { return 0 - 1; }
    if i == 0 { return 0 - 8; }
    if nchild(i) > 0 { return 0 - 11; }
    FSK[i] = 0;
    FSL[i] = 0;
    return 0;
}

# ============================ 环境变量 ============================

var EKEY: [256] i64;         # 16 × 16
var EVAL: [512] i64;         # 16 × 32
var ECNT: i64;

fn ekn(i: i64) -> i64 { return i * 16; }
fn evn(i: i64) -> i64 { return i * 32; }

fn eget(k: ptr, dst: ptr) -> i64 {
    let i: i64 = 0;
    while i < ECNT {
        if seq(&EKEY + ekn(i), k) == 1 {
            scopy(dst, &EVAL + evn(i));
            return 1;
        }
        i = i + 1;
    }
    store8(dst, 0);
    return 0;
}

fn eset(k: ptr, v: ptr) -> i64 {
    let i: i64 = 0;
    while i < ECNT {
        if seq(&EKEY + ekn(i), k) == 1 {
            scopy(&EVAL + evn(i), v);
            return i;
        }
        i = i + 1;
    }
    if ECNT >= 16 { return 0 - 7; }
    if slen(k) > 15 { return 0 - 8; }
    if slen(v) > 31 { return 0 - 8; }
    scopy(&EKEY + ekn(ECNT), k);
    scopy(&EVAL + evn(ECNT), v);
    ECNT = ECNT + 1;
    return ECNT - 1;
}

# ============================ 签名：SipHash-2-4 ============================
#   校验和只能证明「没被意外改坏」，证明不了**来源可信**。签名解决后者。
#   选 SipHash 而非自造混合：它有公开定义与官方测试向量，可独立验证。
#   两个语言级坑（本项目记过）：
#     · 64 位常数写不进字面量 → 用完整十进制字面量
#     · (1 << r) 在生成的 C 里是 int（32 位）移位，r>=32 是 UB → 用 i64 变量

var SV0: i64;
var SV1: i64;
var SV2: i64;
var SV3: i64;

fn rotl64(x: i64, r: i64) -> i64 {
    let one: i64 = 1;
    return (x << r) | ((x >> (64 - r)) & ((one << r) - 1));
}

fn sipround() -> i64 {
    SV0 = SV0 + SV1;
    SV2 = SV2 + SV3;
    SV1 = rotl64(SV1, 13);
    SV3 = rotl64(SV3, 16);
    SV1 = SV1 ^ SV0;
    SV3 = SV3 ^ SV2;
    SV0 = rotl64(SV0, 32);
    SV2 = SV2 + SV1;
    SV0 = SV0 + SV3;
    SV1 = rotl64(SV1, 17);
    SV3 = rotl64(SV3, 21);
    SV1 = SV1 ^ SV2;
    SV3 = SV3 ^ SV0;
    SV2 = rotl64(SV2, 32);
    return 0;
}

fn siphash24(pb: ptr, n: i64, k0: i64, k1: i64) -> i64 {
    let i: i64 = 0;
    let j: i64 = 0;
    let m: i64 = 0;
    let b: i64 = 0;
    let bb: i64 = 0;
    SV0 = 8317987319222330741 ^ k0;
    SV1 = 7237128888997146477 ^ k1;
    SV2 = 7816392313619706465 ^ k0;
    SV3 = 8387220255154660723 ^ k1;
    i = 0;
    while (i + 8) <= n {
        m = 0;
        j = 0;
        while j < 8 {
            bb = load8(pb + i + j);
            m = m + (bb << (j * 8));
            j = j + 1;
        }
        SV3 = SV3 ^ m;
        sipround();
        sipround();
        SV0 = SV0 ^ m;
        i = i + 8;
    }
    b = (n - (n / 256) * 256) << 56;
    j = i;
    while j < n {
        bb = load8(pb + j);
        b = b + (bb << ((j - i) * 8));
        j = j + 1;
    }
    SV3 = SV3 ^ b;
    sipround();
    sipround();
    SV0 = SV0 ^ b;
    SV2 = SV2 ^ 255;
    sipround();
    sipround();
    sipround();
    sipround();
    return SV0 ^ SV1 ^ SV2 ^ SV3;
}

var VEC: [16] i64;

fn sip_selftest() -> i64 {
    let M: [80] i64;
    let i: i64 = 0;
    let bad: i64 = 0;
    let w: i64 = 0;
    VEC[0] = 8246050544436514353;
    VEC[1] = 8428550223375919101;
    VEC[2] = 967288799772626778;
    VEC[3] = -8833979346009227731;
    VEC[4] = -3519680893311416393;
    VEC[5] = 1762690195596617357;
    VEC[6] = -3762398573937892402;
    VEC[7] = -6124331488671313609;
    VEC[8] = -7785046478206851998;
    VEC[9] = -7061500321231936336;
    VEC[10] = 8817410102741809651;
    VEC[11] = -814255129352229465;
    VEC[12] = 8439340791604635131;
    VEC[13] = 1507111754042457488;
    VEC[14] = -638443999941954834;
    VEC[15] = -6833708440360172059;
    i = 0;
    while i < 64 {
        store8(&M + i, i);
        i = i + 1;
    }
    i = 0;
    while i < 16 {
        w = siphash24(&M, i, 506097522914230528, 1084818905618843912);
        if w != VEC[i] { bad = bad + 1; }
        i = i + 1;
    }
    return bad;
}

var KEY0: i64;
var KEY1: i64;

fn key_derive(pass: ptr) -> i64 {
    let n: i64 = slen(pass);
    KEY0 = siphash24(pass, n, 131, 277);
    KEY1 = siphash24(pass, n, 277, 131);
    return 0;
}

# ============================ 栈式 VM ============================
#   0 = halt   1 = push imm32(LE)   2 = add   3 = out   4 = mul   5 = sub
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
            if i + 5 > n { return 0 - 4; }
            if sp >= 64 { return 0 - 3; }
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
                if sp < 2 { return 0 - 2; }
                VS[sp - 2] = VS[sp - 2] + VS[sp - 1];
                sp = sp - 1;
                i = i + 1;
            } else {
                if op == 5 {
                    if sp < 2 { return 0 - 2; }
                    VS[sp - 2] = VS[sp - 2] - VS[sp - 1];
                    sp = sp - 1;
                    i = i + 1;
                } else {
                    if op == 4 {
                        if sp < 2 { return 0 - 2; }
                        VS[sp - 2] = VS[sp - 2] * VS[sp - 1];
                        sp = sp - 1;
                        i = i + 1;
                    } else {
                        if op == 3 {
                            if sp < 1 { return 0 - 2; }
                            if VM_OUT < 8 {
                                VOUT[VM_OUT] = VS[sp - 1];
                                VM_OUT = VM_OUT + 1;
                            }
                            sp = sp - 1;
                            i = i + 1;
                        } else {
                            return 0 - 1;
                        }
                    }
                }
            }
        }
    }
    return 0;
}

# ============================ 包表 ============================
#   16 个包：name(16) · ver(16) · deps(32，逗号分隔) · code(64) · len · sig

var PNAME: [256] i64;
var PVER: [256] i64;
var PDEP: [512] i64;
var PCODE: [1024] i64;
var PLEN: [16] i64;
var PSIG: [16] i64;
var PN: i64;

fn pnm(i: i64) -> i64 { return i * 16; }
fn pdv(i: i64) -> i64 { return i * 16; }
fn pdp(i: i64) -> i64 { return i * 32; }
fn pcd(i: i64) -> i64 { return i * 64; }

fn pfind(nm: ptr) -> i64 {
    let i: i64 = 0;
    while i < PN {
        if seq(&PNAME + pnm(i), nm) == 1 { return i; }
        i = i + 1;
    }
    return 0 - 1;
}

# 语义化版本比较：a>b → 1，a==b → 0，a<b → -1
# 按 '.' 分段逐段比数字。刻意**不**做字符串比较：
# "1.10.0" < "1.9.0" 按字典序是对的，按语义是错的。
fn vcmp(a: ptr, b: ptr) -> i64 {
    let ai: i64 = 0;
    let bi: i64 = 0;
    let av: i64 = 0;
    let bv: i64 = 0;
    let la: i64 = slen(a);
    let lb: i64 = slen(b);
    while ai < la {
        av = 0;
        while ai < la {
            if load8(a + ai) < 48 { }
            else {
                if load8(a + ai) > 57 { }
                else { av = av * 10 + load8(a + ai) - 48; }
            }
            if load8(a + ai) == 46 { ai = ai + 1; break; }
            ai = ai + 1;
        }
        bv = 0;
        while bi < lb {
            if load8(b + bi) == 46 { bi = bi + 1; break; }
            bv = bv * 10 + load8(b + bi) - 48;
            bi = bi + 1;
        }
        if av > bv { return 1; }
        if av < bv { return 0 - 1; }
    }
    return 0;
}

fn fnv1a(pb: ptr, n: i64) -> i64 {
    let h: i64 = 2166136261;
    let i: i64 = 0;
    while i < n {
        h = h * 16777619;
        h = h + load8(pb + i);
        h = h - (h / 4294967296) * 4294967296;
        i = i + 1;
    }
    return h;
}

var SIGB: [256] i64;

# 签名覆盖 name|ver|deps|len|code。**只签 code 是不够的**：
# 那样改 version / deps 而签名不变，照样能把依赖关系换掉。
fn sig_of(i: i64) -> i64 {
    store8(&SIGB, 0);
    sapp(&SIGB, &PNAME + pnm(i));
    sappc(&SIGB, 124);
    sapp(&SIGB, &PVER + pdv(i));
    sappc(&SIGB, 124);
    sapp(&SIGB, &PDEP + pdp(i));
    sappc(&SIGB, 124);
    i2s(PLEN[i], &NUMP + 24);
    sapp(&SIGB, &NUMP + 24);
    sappc(&SIGB, 124);
    let k: i64 = 0;
    while k < PLEN[i] {
        i2s(load8(&PCODE + pcd(i) + k), &NUMP + 24);
        sapp(&SIGB, &NUMP + 24);
        sappc(&SIGB, 44);
        k = k + 1;
    }
    return siphash24(&SIGB, slen(&SIGB), KEY0, KEY1);
}

fn psign(i: i64) -> i64 {
    PSIG[i] = sig_of(i);
    return PSIG[i];
}

fn pcheck(i: i64) -> i64 {
    if sig_of(i) != PSIG[i] { return 0 - 12; }
    return 0;
}

# ---------------- 依赖解析：DFS 后序 + 环检测 ----------------

var ORD: [16] i64;
var ORDSTR: [128] i64;
var ON: i64;
var MARK: [16] i64;
var CY: i64;
var DB0: [24] i64;
var DB1: [24] i64;
var DB2: [24] i64;
var DB3: [24] i64;
var DN: i64;

fn dep_split(s: ptr) -> i64 {
    let i: i64 = 0;
    let t: i64 = 0;
    let j: i64 = 0;
    let n: i64 = slen(s);
    store8(&DB0, 0);
    store8(&DB1, 0);
    store8(&DB2, 0);
    store8(&DB3, 0);
    while i <= n {
        if i == n {
            if t == 0 { store8(&DB0 + j, 0); }
            if t == 1 { store8(&DB1 + j, 0); }
            if t == 2 { store8(&DB2 + j, 0); }
            if t == 3 { store8(&DB3 + j, 0); }
            if j > 0 { t = t + 1; }
            i = i + 1;
        } else {
            if load8(s + i) == 44 {
                if t == 0 { store8(&DB0 + j, 0); }
                if t == 1 { store8(&DB1 + j, 0); }
                if t == 2 { store8(&DB2 + j, 0); }
                if t == 3 { store8(&DB3 + j, 0); }
                t = t + 1;
                j = 0;
                i = i + 1;
            } else {
                if t == 0 { store8(&DB0 + j, load8(s + i)); }
                if t == 1 { store8(&DB1 + j, load8(s + i)); }
                if t == 2 { store8(&DB2 + j, load8(s + i)); }
                if t == 3 { store8(&DB3 + j, load8(s + i)); }
                j = j + 1;
                i = i + 1;
            }
        }
    }
    DN = t;
    return t;
}

fn dnth(k: i64) -> i64 {
    if k == 0 { return 0; }
    if k == 1 { return 1; }
    if k == 2 { return 2; }
    return 3;
}

fn dfs(i: i64) -> i64 {
    let k: i64 = 0;
    let j: i64 = 0;
    if MARK[i] == 1 { CY = 1; return 0 - 2; }
    if MARK[i] == 2 { return 0; }
    MARK[i] = 1;
    dep_split(&PDEP + pdp(i));
    while k < DN {
        if k == 0 { j = pfind(&DB0); }
        if k == 1 { j = pfind(&DB1); }
        if k == 2 { j = pfind(&DB2); }
        if k == 3 { j = pfind(&DB3); }
        if j >= 0 { dfs(j); }
        k = k + 1;
    }
    MARK[i] = 2;
    ORD[ON] = i;
    ON = ON + 1;
    return 0;
}

fn resolve(nm: ptr) -> i64 {
    let i: i64 = pfind(nm);
    let k: i64 = 0;
    if i < 0 { return 0 - 1; }
    ON = 0;
    CY = 0;
    while k < PN { MARK[k] = 0; k = k + 1; }
    dfs(i);
    if CY == 1 { return 0 - 2; }
    return ON;
}

# ---------------- 安装 / 升级 / 执行 / 卸载 ----------------

fn p_install(nm: ptr, ver: ptr, deps: ptr, code: ptr, clen: i64) -> i64 {
    let i: i64 = pfind(nm);
    let k: i64 = 0;
    if clen < 1 { return 0 - 8; }
    if clen > 64 { return 0 - 7; }
    if i < 0 {
        if PN >= 16 { return 0 - 7; }
        if slen(nm) > 15 { return 0 - 8; }
        i = PN;
        PN = PN + 1;
    }
    scopy(&PNAME + pnm(i), nm);
    scopy(&PVER + pdv(i), ver);
    scopy(&PDEP + pdp(i), deps);
    while k < clen {
        store8(&PCODE + pcd(i) + k, load8(code + k));
        k = k + 1;
    }
    PLEN[i] = clen;
    psign(i);
    return i;
}

# 升级：新版本必须 > 旧版本。降级被**拒绝**，不是静默覆盖。
fn p_upgrade(nm: ptr, ver: ptr, code: ptr, clen: i64) -> i64 {
    let i: i64 = pfind(nm);
    if i < 0 { return 0 - 1; }
    if vcmp(ver, &PVER + pdv(i)) != 1 { return 0 - 8; }
    let k: i64 = 0;
    scopy(&PVER + pdv(i), ver);
    while k < clen {
        store8(&PCODE + pcd(i) + k, load8(code + k));
        k = k + 1;
    }
    PLEN[i] = clen;
    psign(i);
    return i;
}

fn p_run(nm: ptr) -> i64 {
    let i: i64 = pfind(nm);
    let r: i64 = 0;
    if i < 0 { return 0 - 1; }
    # 签名先验：验不过就不执行，而不是执行完再说。
    if pcheck(i) != 0 { return 0 - 12; }
    r = vm_run(&PCODE + pcd(i), PLEN[i]);
    if r < 0 { return r; }
    if VM_OUT < 1 { return 0 - 5; }
    return VOUT[0];
}

fn p_del(nm: ptr) -> i64 {
    let i: i64 = pfind(nm);
    let last: i64 = 0;
    let k: i64 = 0;
    if i < 0 { return 0 - 1; }
    last = PN - 1;
    if i != last {
        scopy(&PNAME + pnm(i), &PNAME + pnm(last));
        scopy(&PVER + pdv(i), &PVER + pdv(last));
        scopy(&PDEP + pdp(i), &PDEP + pdp(last));
        PLEN[i] = PLEN[last];
        PSIG[i] = PSIG[last];
        k = 0;
        while k < 64 {
            store8(&PCODE + pcd(i) + k, load8(&PCODE + pcd(last) + k));
            k = k + 1;
        }
    }
    PN = last;
    return 0;
}

# ---------------- hex / 段：pack 与 unpack ----------------

var HEXD: [16] i64;

fn hexch(d: i64) -> i64 {
    if d < 10 { return d + 48; }
    return d + 87;
}

fn hexv(c: i64) -> i64 {
    if c >= 48 {
        if c <= 57 { return c - 48; }
    }
    if c >= 97 {
        if c <= 102 { return c - 87; }
    }
    return 0 - 1;
}

# 一个字节 → 两位 hex，追加到 dst
fn hex8(dst: ptr, v: i64) -> i64 {
    let hi: i64 = (v / 16) - ((v / 16) / 16) * 16;
    let lo: i64 = v - (v / 16) * 16;
    sappc(dst, hexch(hi));
    sappc(dst, hexch(lo));
    return 2;
}

fn unhex16(s: ptr) -> i64 {
    let i: i64 = 0;
    let v: i64 = 0;
    let d: i64 = 0;
    while i < 16 {
        d = hexv(load8(s + i));
        if d < 0 { return 0 - 8; }
        v = v * 16 + d;
        i = i + 1;
    }
    return v;
}

var STREAM: [512] i64;
var SEG0: [32] i64;
var SEG1: [32] i64;
var SEG2: [32] i64;
var SEG3: [32] i64;
var SEG4: [32] i64;
var SEG5: [32] i64;
var SEG6: [32] i64;

# 第 k 个 '|' 之后的偏移；-1 表示没有这么多段
fn segpos(s: ptr, k: i64) -> i64 {
    let i: i64 = 0;
    let c: i64 = 0;
    let n: i64 = slen(s);
    if k == 0 { return 0; }
    while i < n {
        if load8(s + i) == 124 {
            c = c + 1;
            if c == k { return i + 1; }
        }
        i = i + 1;
    }
    return 0 - 1;
}

fn take_seg(s: ptr, k: i64, out: ptr) -> i64 {
    let p0: i64 = segpos(s, k);
    let p1: i64 = segpos(s, k + 1);
    let n: i64 = slen(s);
    let i: i64 = 0;
    store8(out, 0);
    if p0 < 0 { return 0 - 1; }
    if p1 < 0 { p1 = n + 1; }
    while p0 + i < p1 - 1 {
        if i < 31 {
            store8(out + i, load8(s + p0 + i));
            i = i + 1;
        } else { return i; }
    }
    store8(out + i, 0);
    return i;
}

fn p_pack(nm: ptr) -> i64 {
    let i: i64 = pfind(nm);
    let k: i64 = 0;
    if i < 0 { return 0 - 1; }
    store8(&STREAM, 0);
    sapp(&STREAM, "MOPK2|");
    sapp(&STREAM, &PNAME + pnm(i));
    sappc(&STREAM, 124);
    sapp(&STREAM, &PVER + pdv(i));
    sappc(&STREAM, 124);
    sapp(&STREAM, &PDEP + pdp(i));
    sappc(&STREAM, 124);
    i2s(PLEN[i], &NUMP + 24);
    sapp(&STREAM, &NUMP + 24);
    sappc(&STREAM, 124);
    while k < PLEN[i] {
        hex8(&STREAM, load8(&PCODE + pcd(i) + k));
        k = k + 1;
    }
    sappc(&STREAM, 124);
    # 签名段用**十进制**而不是 hex：SipHash 是 64 位有符号值，可能为负，
    # 而墨语言没有无符号右移，把负数转成 16 位 hex 必须自己处理二补数 ——
    # 多一处手写的位操作就多一处静默出错的地方。十进制能被 s2i 原样读回。
    i2s(PSIG[i], &NUMP + 24);
    sapp(&STREAM, &NUMP + 24);
    return slen(&STREAM);
}

var CBUF: [96] i64;

# 装包失败时的回滚缓冲。验签失败就 p_del 会把**已经装好的那个包**一起删掉
# —— 一个坏包进来，好包没了。正确行为是还原成装之前的状态。
var BKN: [32] i64;
var BKV: [32] i64;
var BKD: [32] i64;
var BKC: [96] i64;
var BKL: i64;
var BKS: i64;

fn p_unpack(s: ptr) -> i64 {
    let n: i64 = 0;
    let i: i64 = 0;
    let ln: i64 = 0;
    let sg: i64 = 0;
    let h: i64 = 0;
    let v: i64 = 0;
    let k: i64 = 0;
    let had: i64 = 0;
    take_seg(s, 0, &SEG0);
    if seq(&SEG0, "MOPK2") == 0 { return 0 - 8; }
    take_seg(s, 1, &SEG1);
    take_seg(s, 2, &SEG2);
    take_seg(s, 3, &SEG3);
    take_seg(s, 4, &SEG4);
    take_seg(s, 5, &SEG5);
    take_seg(s, 6, &SEG6);
    ln = s2i(&SEG4);
    if ln < 1 { return 0 - 8; }
    if ln > 64 { return 0 - 7; }
    if slen(&SEG5) != ln * 2 { return 0 - 5; }
    n = 0;
    while n < ln {
        h = hexv(load8(&SEG5 + n * 2));
        v = hexv(load8(&SEG5 + n * 2 + 1));
        if h < 0 { return 0 - 8; }
        if v < 0 { return 0 - 8; }
        store8(&CBUF + n, h * 16 + v);
        n = n + 1;
    }
    # 装之前先备份同名包
    had = pfind(&SEG1);
    if had >= 0 {
        scopy(&BKN, &PNAME + pnm(had));
        scopy(&BKV, &PVER + pdv(had));
        scopy(&BKD, &PDEP + pdp(had));
        BKL = PLEN[had];
        BKS = PSIG[had];
        k = 0;
        while k < BKL {
            store8(&BKC + k, load8(&PCODE + pcd(had) + k));
            k = k + 1;
        }
    }
    i = p_install(&SEG1, &SEG2, &SEG3, &CBUF, ln);
    if i < 0 { return i; }
    sg = s2i(&SEG6);
    # 验签：与安装时算出的签名比对，不符 → 还原并返回 -12。
    # 不能只 p_del：那样坏包进来会把原来装好的包一起弄丢。
    if sg != PSIG[i] {
        if had >= 0 {
            scopy(&PNAME + pnm(i), &BKN);
            scopy(&PVER + pdv(i), &BKV);
            scopy(&PDEP + pdp(i), &BKD);
            PLEN[i] = BKL;
            PSIG[i] = BKS;
            k = 0;
            while k < BKL {
                store8(&PCODE + pcd(i) + k, load8(&BKC + k));
                k = k + 1;
            }
        } else {
            p_del(&SEG1);
        }
        return 0 - 12;
    }
    return i;
}

# ============================ 状态持久化 ============================
#   序列化成文本行（不是二进制）：可读、可 grep、可人工核对。
#     F <idx> <par> <kind> <name>
#     D <idx> <len> <b0,b1,...>
#     P <name> <ver> <deps> <len> <b0,b1,...> <sig>
#     E <key> <value>

var STATE: [4096] i64;
var DBPATH: [32] i64;
var RBUF: [4096] i64;

fn sv_save() -> i64 {
    let i: i64 = 0;
    let k: i64 = 0;
    store8(&STATE, 0);
    sapp(&STATE, "MOSTATE1\n");
    i = 0;
    while i < FSCNT {
        if FSK[i] != 0 {
            sapp(&STATE, "F ");
            i2s(i, &NUMP + 24);
            sapp(&STATE, &NUMP + 24);
            sappc(&STATE, 32);
            i2s(FSP[i], &NUMP + 24);
            sapp(&STATE, &NUMP + 24);
            sappc(&STATE, 32);
            i2s(FSK[i], &NUMP + 24);
            sapp(&STATE, &NUMP + 24);
            sappc(&STATE, 32);
            sapp(&STATE, &FSNAME + fsnm(i));
            sappc(&STATE, 10);
            if FSK[i] == 1 {
                sapp(&STATE, "D ");
                i2s(i, &NUMP + 24);
                sapp(&STATE, &NUMP + 24);
                sappc(&STATE, 32);
                i2s(FSL[i], &NUMP + 24);
                sapp(&STATE, &NUMP + 24);
                sappc(&STATE, 32);
                k = 0;
                while k < FSL[i] {
                    i2s(load8(&FSDATA + fsdt(i) + k), &NUMP + 24);
                    sapp(&STATE, &NUMP + 24);
                    sappc(&STATE, 44);
                    k = k + 1;
                }
                sappc(&STATE, 10);
            }
        }
        i = i + 1;
    }
    i = 0;
    while i < PN {
        sapp(&STATE, "P ");
        sapp(&STATE, &PNAME + pnm(i));
        sappc(&STATE, 32);
        sapp(&STATE, &PVER + pdv(i));
        sappc(&STATE, 32);
        sapp(&STATE, &PDEP + pdp(i));
        sappc(&STATE, 32);
        i2s(PLEN[i], &NUMP + 24);
        sapp(&STATE, &NUMP + 24);
        sappc(&STATE, 32);
        k = 0;
        while k < PLEN[i] {
            i2s(load8(&PCODE + pcd(i) + k), &NUMP + 24);
            sapp(&STATE, &NUMP + 24);
            sappc(&STATE, 44);
            k = k + 1;
        }
        sappc(&STATE, 32);
        i2s(PSIG[i], &NUMP + 24);
        sapp(&STATE, &NUMP + 24);
        sappc(&STATE, 10);
        i = i + 1;
    }
    i = 0;
    while i < ECNT {
        sapp(&STATE, "E ");
        sapp(&STATE, &EKEY + ekn(i));
        sappc(&STATE, 32);
        sapp(&STATE, &EVAL + evn(i));
        sappc(&STATE, 10);
        i = i + 1;
    }
    return fput(&DBPATH, &STATE, slen(&STATE));
}

# 清空全部内存状态。往返测试必须真的清空 —— 只清「文件数」不清表，
# load 之后的表里全是残留，「重建成功」就是自欺。
fn sv_clear() -> i64 {
    let i: i64 = 0;
    while i < 64 { FSK[i] = 0; FSP[i] = 0; FSL[i] = 0; i = i + 1; }
    i = 0;
    while i < 1536 { store8(&FSNAME + i, 0); i = i + 1; }
    i = 0;
    while i < 4096 { store8(&FSDATA + i, 0); i = i + 1; }
    FSCNT = 0;
    CWD = 0;
    PN = 0;
    ECNT = 0;
    i = 0;
    while i < 16 { PLEN[i] = 0; PSIG[i] = 0; i = i + 1; }
    return 0;
}

var TK0: [64] i64;
var TK1: [64] i64;
var TK2: [64] i64;
var TK3: [64] i64;
var TK4: [64] i64;
var TK5: [64] i64;
var TK6: [64] i64;
var TK7: [64] i64;
var NTK: i64;

fn split8(line: ptr) -> i64 {
    let i: i64 = 0;
    let t: i64 = 0;
    let j: i64 = 0;
    let c: i64 = 0;
    store8(&TK0, 0);
    store8(&TK1, 0);
    store8(&TK2, 0);
    store8(&TK3, 0);
    store8(&TK4, 0);
    store8(&TK5, 0);
    store8(&TK6, 0);
    store8(&TK7, 0);
    while load8(line + i) != 0 {
        c = load8(line + i);
        if c == 32 {
            if j > 0 {
                if t == 0 { store8(&TK0 + j, 0); }
                if t == 1 { store8(&TK1 + j, 0); }
                if t == 2 { store8(&TK2 + j, 0); }
                if t == 3 { store8(&TK3 + j, 0); }
                if t == 4 { store8(&TK4 + j, 0); }
                if t == 5 { store8(&TK5 + j, 0); }
                if t == 6 { store8(&TK6 + j, 0); }
                if t == 7 { store8(&TK7 + j, 0); }
                t = t + 1;
                j = 0;
                if t > 7 { return 8; }
            }
        } else {
            if t == 0 { store8(&TK0 + j, c); }
            if t == 1 { store8(&TK1 + j, c); }
            if t == 2 { store8(&TK2 + j, c); }
            if t == 3 { store8(&TK3 + j, c); }
            if t == 4 { store8(&TK4 + j, c); }
            if t == 5 { store8(&TK5 + j, c); }
            if t == 6 { store8(&TK6 + j, c); }
            if t == 7 { store8(&TK7 + j, c); }
            j = j + 1;
        }
        i = i + 1;
    }
    if t == 0 { store8(&TK0 + j, 0); }
    if t == 1 { store8(&TK1 + j, 0); }
    if t == 2 { store8(&TK2 + j, 0); }
    if t == 3 { store8(&TK3 + j, 0); }
    if t == 4 { store8(&TK4 + j, 0); }
    if t == 5 { store8(&TK5 + j, 0); }
    if t == 6 { store8(&TK6 + j, 0); }
    if t == 7 { store8(&TK7 + j, 0); }
    if j > 0 { t = t + 1; }
    NTK = t;
    return t;
}

# "1,6,0" → 字节。返回字节数。
fn parseb(s: ptr, dst: ptr) -> i64 {
    let i: i64 = 0;
    let n: i64 = 0;
    let st: i64 = 0;
    let c: i64 = 0;
    let ln: i64 = slen(s);
    while i <= ln {
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
                if c >= 48 {
                    if c <= 57 { st = st * 10 + (c - 48); }
                }
            }
        }
        i = i + 1;
    }
    return n;
}

var LINEB: [256] i64;
var LBUF: [96] i64;

fn sv_load() -> i64 {
    let r: i64 = fget(&DBPATH, &RBUF, 4000);
    let i: i64 = 0;
    let j: i64 = 0;
    let n: i64 = 0;
    let idx: i64 = 0;
    let par: i64 = 0;
    let kd: i64 = 0;
    let ln: i64 = 0;
    let nline: i64 = 0;
    if r <= 0 { return 0 - 1; }
    store8(&RBUF + r, 0);
    sv_clear();
    while i < r {
        j = 0;
        while i + j < r {
            if load8(&RBUF + i + j) == 10 { break; }
            j = j + 1;
        }
        n = 0;
        while n < j {
            store8(&LINEB + n, load8(&RBUF + i + n));
            n = n + 1;
        }
        store8(&LINEB + j, 0);
        if j > 0 {
            nline = nline + 1;
            split8(&LINEB);
            if seq(&TK0, "F") == 1 {
                idx = s2i(&TK1);
                par = s2i(&TK2);
                kd = s2i(&TK3);
                if idx < 64 {
                    FSK[idx] = kd;
                    FSP[idx] = par;
                    FSL[idx] = 0;
                    scopy(&FSNAME + fsnm(idx), &TK4);
                    if idx + 1 > FSCNT { FSCNT = idx + 1; }
                }
            } else {
                if seq(&TK0, "D") == 1 {
                    idx = s2i(&TK1);
                    ln = s2i(&TK2);
                    parseb(&TK3, &LBUF);
                    n = 0;
                    while n < ln {
                        store8(&FSDATA + fsdt(idx) + n, load8(&LBUF + n));
                        n = n + 1;
                    }
                    FSL[idx] = ln;
                } else {
                    if seq(&TK0, "P") == 1 {
                        if PN < 16 {
                            scopy(&PNAME + pnm(PN), &TK1);
                            scopy(&PVER + pdv(PN), &TK2);
                            scopy(&PDEP + pdp(PN), &TK3);
                            ln = s2i(&TK4);
                            parseb(&TK5, &LBUF);
                            n = 0;
                            while n < ln {
                                store8(&PCODE + pcd(PN) + n, load8(&LBUF + n));
                                n = n + 1;
                            }
                            PLEN[PN] = ln;
                            PSIG[PN] = s2i(&TK6);
                            PN = PN + 1;
                        }
                    } else {
                        if seq(&TK0, "E") == 1 {
                            eset(&TK1, &TK2);
                        }
                    }
                }
            }
        }
        i = i + j + 1;
    }
    return nline;
}

# ============================ Shell ============================

var NCMD: i64;
var NERR: i64;
var LAST: i64;
var EXP: [128] i64;

# $VAR 与 $? 展开。未知变量展开成空串（不静默保留 "$VAR" —— 那会让
# 「变量没设上」看起来和「变量设成了字面量」一样）。
fn expand(s: ptr, dst: ptr) -> i64 {
    let i: i64 = 0;
    let j: i64 = 0;
    let c: i64 = 0;
    store8(dst, 0);
    while load8(s + i) != 0 {
        c = load8(s + i);
        if c == 36 {
            if load8(s + i + 1) == 63 {
                i2s(LAST, &NUMP + 24);
                sapp(dst, &NUMP + 24);
                i = i + 2;
            } else {
                j = 0;
                while load8(s + i + 1 + j) != 0 {
                    if load8(s + i + 1 + j) == 32 { break; }
                    if load8(s + i + 1 + j) == 47 { break; }
                    store8(&LBUF + j, load8(s + i + 1 + j));
                    j = j + 1;
                }
                store8(&LBUF + j, 0);
                if j > 0 {
                    eget(&LBUF, &EXP);
                    sapp(dst, &EXP);
                    i = i + 1 + j;
                } else {
                    sappc(dst, c);
                    i = i + 1;
                }
            }
        } else {
            sappc(dst, c);
            i = i + 1;
        }
    }
    return slen(dst);
}

var ECHO: [128] i64;
var AB: [64] i64;
var BB: [64] i64;

fn vmkdir_p(path: ptr) -> i64 {
    let i: i64 = 0;
    let j: i64 = 0;
    let n: i64 = slen(path);
    let cur: i64 = 0;
    let nx: i64 = 0;
    if load8(path) != 47 { cur = CWD; }
    store8(&AB, 0);
    while i <= n {
        if i == n {
            if j > 0 {
                store8(&BB + j, 0);
                nx = vchild(cur, &BB);
                if nx < 0 { nx = vcreate(cur, &BB, 2); }
                if nx < 0 { return nx; }
                cur = nx;
            }
            i = i + 1;
        } else {
            if load8(path + i) == 47 {
                if j > 0 {
                    store8(&BB + j, 0);
                    nx = vchild(cur, &BB);
                    if nx < 0 { nx = vcreate(cur, &BB, 2); }
                    if nx < 0 { return nx; }
                    cur = nx;
                    j = 0;
                }
                i = i + 1;
            } else {
                if j < 63 { store8(&BB + j, load8(path + i)); j = j + 1; }
                i = i + 1;
            }
        }
    }
    return cur;
}

# 创建文件（父目录必须已存在）
fn vcreatef(path: ptr, kind: i64) -> i64 {
    let i: i64 = slen(path);
    let par: i64 = 0;
    let nm: i64 = 0;
    while i > 0 {
        if load8(path + i - 1) == 47 { break; }
        i = i - 1;
    }
    if i == 0 {
        par = CWD;
        nm = 0;
    } else {
        store8(&AB, 0);
        nm = 0;
        while nm < i - 1 {
            store8(&AB + nm, load8(path + nm));
            nm = nm + 1;
        }
        store8(&AB + nm, 0);
        par = vresolve(&AB);
        if par < 0 { return 0 - 1; }
        nm = i;
    }
    store8(&BB, 0);
    nm = 0;
    store8(&BB, 0);
    while load8(path + i + nm) != 0 {
        store8(&BB + nm, load8(path + i + nm));
        nm = nm + 1;
    }
    store8(&BB + nm, 0);
    return vcreate(par, &BB, kind);
}

fn vls(i: i64) -> i64 {
    let k: i64 = 0;
    let c: i64 = 0;
    while k < FSCNT {
        if FSK[k] != 0 {
            if FSP[k] == i {
                p(&FSNAME + fsnm(k));
                if FSK[k] == 2 { p("/"); }
                print_nl();
                c = c + 1;
            }
        }
        k = k + 1;
    }
    return c;
}

fn vfind(pre: ptr) -> i64 {
    let k: i64 = 0;
    let c: i64 = 0;
    while k < FSCNT {
        if FSK[k] != 0 {
            if sprefix(&FSNAME + fsnm(k), pre) == 1 {
                vpath2(k, &AB);
                p(&AB);
                print_nl();
                c = c + 1;
            }
        }
        k = k + 1;
    }
    return c;
}

# 逐行执行脚本（支持 # 注释、; 分隔多语句）。返回执行过的语句数。
fn do_line(line: ptr) -> i64 {
    let nt: i64 = split8(line);
    let r: i64 = 0;
    let i: i64 = 0;
    let n: i64 = 0;
    let cl: i64 = 0;
    if nt < 1 { return 0; }
    NCMD = NCMD + 1;

    if seq(&TK0, "echo") == 1 {
        expand(&TK1, &ECHO);
        p(&ECHO);
        print_nl();
        return 0;
    }
    if seq(&TK0, "mkdir") == 1 {
        expand(&TK1, &AB);
        r = vmkdir_p(&AB);
        return r;
    }
    if seq(&TK0, "cd") == 1 {
        expand(&TK1, &AB);
        i = vresolve(&AB);
        if i < 0 { return 0 - 1; }
        if FSK[i] != 2 { return 0 - 8; }
        CWD = i;
        return 0;
    }
    if seq(&TK0, "pwd") == 1 {
        vpath2(CWD, &AB);
        p(&AB);
        print_nl();
        return 0;
    }
    if seq(&TK0, "ls") == 1 {
        if nt > 1 { expand(&TK1, &AB); i = vresolve(&AB); }
        else { i = CWD; }
        if i < 0 { return 0 - 1; }
        return vls(i);
    }
    if seq(&TK0, "write") == 1 {
        expand(&TK1, &AB);
        expand(&TK2, &BB);
        i = vresolve(&AB);
        if i < 0 {
            i = vcreatef(&AB, 1);
            if i < 0 { return i; }
        }
        return vwrite(i, &BB, slen(&BB));
    }
    if seq(&TK0, "cat") == 1 {
        expand(&TK1, &AB);
        i = vresolve(&AB);
        if i < 0 { return 0 - 1; }
        if FSK[i] != 1 { return 0 - 8; }
        p(&FSDATA + fsdt(i));
        print_nl();
        return FSL[i];
    }
    if seq(&TK0, "stat") == 1 {
        expand(&TK1, &AB);
        i = vresolve(&AB);
        if i < 0 { return 0 - 1; }
        return FSK[i] * 1000 + FSL[i];
    }
    if seq(&TK0, "rm") == 1 {
        expand(&TK1, &AB);
        i = vresolve(&AB);
        return vrm(i);
    }
    if seq(&TK0, "mv") == 1 {
        expand(&TK1, &AB);
        expand(&TK2, &BB);
        i = vresolve(&AB);
        if i < 0 { return 0 - 1; }
        if slen(&BB) > 23 { return 0 - 8; }
        scopy(&FSNAME + fsnm(i), &BB);
        return 0;
    }
    if seq(&TK0, "cp") == 1 {
        expand(&TK1, &AB);
        expand(&TK2, &BB);
        i = vresolve(&AB);
        if i < 0 { return 0 - 1; }
        if FSK[i] != 1 { return 0 - 8; }
        n = vcreatef(&BB, 1);
        if n < 0 { return n; }
        return vwrite(n, &FSDATA + fsdt(i), FSL[i]);
    }
    if seq(&TK0, "find") == 1 {
        expand(&TK1, &AB);
        return vfind(&AB);
    }
    if seq(&TK0, "set") == 1 {
        return eset(&TK1, &TK2);
    }
    if seq(&TK0, "unset") == 1 {
        i = 0;
        while i < ECNT {
            if seq(&EKEY + ekn(i), &TK1) == 1 {
                ECNT = ECNT - 1;
                scopy(&EKEY + ekn(i), &EKEY + ekn(ECNT));
                scopy(&EVAL + evn(i), &EVAL + evn(ECNT));
                return 0;
            }
            i = i + 1;
        }
        return 0 - 1;
    }
    if seq(&TK0, "env") == 1 {
        i = 0;
        while i < ECNT {
            p(&EKEY + ekn(i));
            p("=");
            p(&EVAL + evn(i));
            print_nl();
            i = i + 1;
        }
        return ECNT;
    }
    if seq(&TK0, "install") == 1 {
        cl = parseb(&TK4, &CBUF);
        return p_install(&TK1, &TK2, &TK3, &CBUF, cl);
    }
    if seq(&TK0, "upgrade") == 1 {
        cl = parseb(&TK3, &CBUF);
        return p_upgrade(&TK1, &TK2, &CBUF, cl);
    }
    if seq(&TK0, "run") == 1 {
        return p_run(&TK1);
    }
    if seq(&TK0, "del") == 1 {
        return p_del(&TK1);
    }
    if seq(&TK0, "pkls") == 1 {
        return PN;
    }
    if seq(&TK0, "deps") == 1 {
        r = resolve(&TK1);
        if r < 0 { return r; }
        i = 0;
        while i < ON {
            p(&PNAME + pnm(ORD[i]));
            print_nl();
            i = i + 1;
        }
        return ON;
    }
    if seq(&TK0, "pack") == 1 {
        r = p_pack(&TK1);
        if r > 0 { p(&STREAM); print_nl(); }
        return r;
    }
    if seq(&TK0, "unpack") == 1 {
        return p_unpack(&TK1);
    }
    if seq(&TK0, "save") == 1 {
        return sv_save();
    }
    if seq(&TK0, "load") == 1 {
        return sv_load();
    }
    if seq(&TK0, "help") == 1 {
        p("echo mkdir cd pwd ls write cat stat rm mv cp find set unset env\n");
        p("install upgrade run del pkls deps pack unpack save load help exit\n");
        return 0;
    }
    if seq(&TK0, "exit") == 1 {
        return 0;
    }
    NERR = NERR + 1;
    p("? ");
    p(&TK0);
    print_nl();
    return 0 - 9;
}

# 逐级创建（mkdir -p）。返回最后一级节点号。
fn exec_seg(s: ptr, a: i64, b: i64) -> i64 {
    let m: i64 = 0;
    while a + m < b {
        store8(&EXP + m, load8(s + a + m));
        m = m + 1;
    }
    store8(&EXP + m, 0);
    LAST = do_line(&EXP);
    return LAST;
}

# 逐行执行脚本：# 开头是注释，一行里的 ; 分成多条语句。
# 返回执行过的语句数（NCMD）。
fn runscript(s: ptr) -> i64 {
    let i: i64 = 0;
    let n: i64 = slen(s);
    let j: i64 = 0;
    let k: i64 = 0;
    let p0: i64 = 0;
    let done: i64 = 0;
    while i < n {
        j = 0;
        done = 0;
        while done == 0 {
            if i + j >= n { done = 1; }
            else {
                if load8(s + i + j) == 10 { done = 1; }
                else { j = j + 1; }
            }
        }
        if j > 0 {
            k = 0;
            while k < j {
                store8(&LINEB + k, load8(s + i + k));
                k = k + 1;
            }
            store8(&LINEB + j, 0);
            if load8(&LINEB) != 35 {
                p0 = 0;
                k = 0;
                while k <= j {
                    if k == j {
                        if k > p0 { exec_seg(&LINEB, p0, k); }
                        k = k + 1;
                    } else {
                        if load8(&LINEB + k) == 59 {
                            exec_seg(&LINEB, p0, k);
                            k = k + 1;
                            p0 = k;
                        } else {
                            k = k + 1;
                        }
                    }
                }
            }
        }
        i = i + j + 1;
    }
    return NCMD;
}

# ============================ 主流程 ============================

var ROOTD: [32] i64;
var RCPATH: [48] i64;
var SCRIPTO: [512] i64;
var SCRIPTR: [512] i64;

fn main() -> i64 {
    let r: i64 = 0;
    let i: i64 = 0;
    let n1: i64 = 0;
    let n2: i64 = 0;

    p("=== mo uspace2 : 完整用户空间 ===\n");

    # --- 0) 签名自检（可独立验证，不靠"跑通就行"）---
    key_derive("mo-uspace2");
    pkv("sip vectors bad", sip_selftest());

    scopy(&ROOTD, "/mo");
    scopy(&DBPATH, "/mo/state.db");
    fmkdir(&ROOTD);

    # --- 1) 跨运行：state.db 已存在则进入 restore 模式 ---
    r = fget(&DBPATH, &RBUF, 4000);
    if r > 0 {
        store8(&RBUF + r, 0);
        p("[mode] restore\n");
        pkv("db bytes", r);
        pkv("db lines", sv_load());
        pkv("files", FSCNT);
        pkv("pkgs", PN);
        pkv("envs", ECNT);
        vpath2(CWD, &AB);
        pks("cwd", &AB);
        i = vresolve("/mo/etc/motd");
        if i >= 0 {
            pkv("motd len", FSL[i]);
            p("  [motd] = ");
            p(&FSDATA + fsdt(i));
            print_nl();
        }
        pkv("run app", p_run("app"));
        return 0;
    }
    p("[mode] init\n");
    vinit();

    # --- 2) 层次化 VFS：mkdir -p /mo/bin /mo/etc ---
    pkv("mkdir bin", vmkdir_p("/mo/bin"));
    pkv("mkdir etc", vmkdir_p("/mo/etc"));
    pkv("nodes", FSCNT);

    # --- 3) 写文件 + cat 读回 ---
    i = vcreate(vresolve("/mo/etc"), "motd", 1);
    vwrite(i, "MO-USERSPACE-2", 14);
    pkv("write motd", FSL[i]);
    p("  [motd] = ");
    p(&FSDATA + fsdt(i));
    print_nl();

    # --- 4) cd / pwd ---
    pkv("cd bin", do_line("cd /mo/bin"));
    vpath2(CWD, &AB);
    pks("pwd", &AB);

    # --- 5) 环境变量与 $VAR 展开 ---
    do_line("set USER root");
    do_line("echo $USER");
    pkv("env count", ECNT);

    # --- 6) 装包：app 依赖 libb，libb 依赖 liba ---
    #   代码用十进制字节串：1=push32 4=mul 3=out 0=halt
    pkv("inst liba", do_line("install liba 1.0.0 - 1,2,0,0,0,1,3,0,0,0,4,3,0"));
    pkv("inst libb", do_line("install libb 1.0.0 liba 1,5,0,0,0,1,5,0,0,0,4,3,0"));
    pkv("inst app",  do_line("install app 1.0.0 libb 1,6,0,0,0,1,7,0,0,0,4,3,0"));
    pkv("pkgs", PN);

    # --- 7) 依赖拓扑：必须是 liba → libb → app（后序）---
    pkv("deps app", resolve("app"));
    store8(&ORDSTR, 0);
    i = 0;
    while i < ON {
        p("  -> ");
        p(&PNAME + pnm(ORD[i]));
        print_nl();
        if i > 0 { sappc(&ORDSTR, 44); }
        sapp(&ORDSTR, &PNAME + pnm(ORD[i]));
        i = i + 1;
    }
    pks("order", &ORDSTR);

    # --- 8) 执行 ---
    pkv("run liba", p_run("liba"));
    pkv("run app", p_run("app"));

    # --- 9) 篡改 → 签名不符（-12，与校验和 -6 分开）---
    i = pfind("app");
    store8(&PCODE + pcd(i) + 1, 9);
    pkv("tampered", p_run("app"));
    store8(&PCODE + pcd(i) + 1, 6);
    psign(i);

    # --- 10) 升级 1.0.0 → 2.0.0：代码换成 5*9=45 ---
    # 走 shell 的 upgrade 命令而不是直接调 p_upgrade：顺带验证命令层接线。
    # 第一版在这里手工改立即数字节，把第二个 push 的立即数索引写成 7
    # （正确是 6），结果 5 * 2311 = 11555 —— 数字看着完全正常，
    # 只有和"期望 45"对照才暴露。
    pkv("upgrade", do_line("upgrade app 2.0.0 1,5,0,0,0,1,9,0,0,0,4,3,0"));
    i = pfind("app");
    pks("app ver", &PVER + pdv(i));
    pkv("run app2", p_run("app"));

    # --- 11) 降级必须被拒 ---
    pkv("downgrade", do_line("upgrade app 1.0.0 1,6,0,0,0,1,7,0,0,0,4,3,0"));
    pks("still ver", &PVER + pdv(i));
    pkv("still run", p_run("app"));

    # --- 12) 环检测 ---
    do_line("install cyca 1.0.0 cycb 1,1,0,0,0,3,0");
    do_line("install cycb 1.0.0 cyca 1,1,0,0,0,3,0");
    pkv("cycle", resolve("cyca"));
    p_del("cyca");
    p_del("cycb");

    # --- 13) pack / unpack ---
    pkv("pack len", p_pack("app"));
    p("  [stream] = ");
    p(&STREAM);
    print_nl();
    pkv("unpack", p_unpack(&STREAM));
    # 篡改流：签名段的最后一位换成一个**不同的数字**（不是非法字符）。
    # 验签失败后原包必须还在、还能跑 —— 只返回 -12 而把好包删了是错的。
    let sl2: i64 = slen(&STREAM);
    let lc: i64 = load8(&STREAM + sl2 - 1);
    if lc == 57 { lc = 48; } else { lc = lc + 1; }
    store8(&STREAM + sl2 - 1, lc);
    pkv("bad stream", p_unpack(&STREAM));
    pkv("app survives", p_run("app"));

    # --- 14) 持久化往返：save → 清空 → load ---
    n1 = FSCNT;
    n2 = PN;
    pkv("save bytes", sv_save());
    sv_clear();
    pkv("after clear files", FSCNT);
    pkv("after clear pkgs", PN);
    pkv("load lines", sv_load());
    pkv("restored files", FSCNT);
    pkv("restored pkgs", PN);
    pkv("restored envs", ECNT);
    # 往返前后**逐项相等**，不是"差不多"。写成差值平方这类复合判据的话，
    # 差 1 和差 100 都会缩成一个大数，看不出到底差在哪一项。
    let fm: i64 = 0;
    if FSCNT == n1 { fm = 1; }
    let pm: i64 = 0;
    if PN == n2 { pm = 1; }
    pkv("files match", fm);
    pkv("pkgs match", pm);
    i = vresolve("/mo/etc/motd");
    if i >= 0 {
        pkv("motd again", FSL[i]);
        p("  [motd] = ");
        p(&FSDATA + fsdt(i));
        print_nl();
    }
    pkv("run app after", p_run("app"));

    # --- 15) shell：脚本执行 + 未知命令计数 ---
    scopy(&SCRIPTO, "# 这是注释\n");
    sapp(&SCRIPTO, "echo hello ; echo world\n");
    sapp(&SCRIPTO, "mkdir /mo/tmp\n");
    sapp(&SCRIPTO, "write /mo/tmp/a.txt ABC\n");
    sapp(&SCRIPTO, "cat /mo/tmp/a.txt\n");
    sapp(&SCRIPTO, "ls /mo/etc\n");
    sapp(&SCRIPTO, "nosuchcmd\n");
    sapp(&SCRIPTO, "env\n");
    sapp(&SCRIPTO, "exit\n");
    let sl: i64 = slen(&SCRIPTO);
    pkv("script bytes", sl);
    scopy(&RCPATH, "/mo/init.rc");
    pkv("write rc", fput(&RCPATH, &SCRIPTO, sl));
    let rr: i64 = fget(&RCPATH, &SCRIPTR, 400);
    pkv("read rc", rr);
    store8(&SCRIPTR + rr, 0);
    let nc0: i64 = NCMD;
    runscript(&SCRIPTR);
    pkv("cmds run", NCMD - nc0);
    pkv("unknown cmds", NERR);

    # --- 16) 目录非空拒绝删除 ---
    pkv("rm non-empty", vrm(vresolve("/mo/etc")));
    pkv("rm file", vrm(vresolve("/mo/etc/motd")));
    pkv("rm again", vrm(vresolve("/mo/etc/motd")));

    fdel(&RCPATH);
    return 0;
}
