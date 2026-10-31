# QuroAI ADB 技术架构（调研版）

> 本文是**基于一手资料**整理的技术架构，不是凭记忆编写。所有结论都标注了来源。
> 目标：把「ADB 调试另一台机器 / 被别的机器调试 / USB 识别 / 无线调试配对」这四个能力
> 从当前不足 10% 补到可用，并明确哪些必须依赖 root/Shizuku、哪些能纯 App 实现。

---

## 0. 一句话结论（先看这个）

| 能力 | 能否纯 App 实现 | 是否需要提权 | 依据 |
|---|---|---|---|
| 调试**另一台** Android（USB 直连） | ✅ 能 | ❌ 不需要（只要对方开 USB 调试 + 本机支持 USB Host/OTG） | Kadb 已实现，见 §4.1 |
| 调试**另一台** Android（同网 TCP 5555） | ✅ 能 | ❌ 不需要（对方需先开 `tcpip 5555`） | dadb 已实现，见 §4.2 |
| 调试**另一台** Android（无线调试配对，Android 11+） | ✅ 能 | ❌ 不需要 | Kadb 完整实现 SPAKE2+TLS，见 §5 |
| **被调试**（本机作为 adb 设备） | ⚠️ 部分 | ✅ 需要 root/Shizuku 才能静默启停 adbd | 见 §6 |
| 本机自连（127.0.0.1） | ✅ 能 | 同「被调试」 | 见 §6.2 |

**关键判断**：本机**作为 ADB 主机去调试别人**，完全不需要 root —— 这是当前最大的空白，
也是 Mika 那套「后台虚拟屏」不需要的能力（Mika 是自连自己）。补齐它靠纯 Kotlin 协议实现即可。

---

## 1. 资料来源（一手）

| 资料 | 用途 |
|---|---|
| [ADB WiFi 架构（AOSP 官方文档）](https://android.googlesource.com/platform/packages/modules/adb/+/08c52fa1c9b3d75ca1ecbe8660f75c5f047f4ce6/docs/dev/adb_wifi.md) | 配对/TLS/mDNS 的权威说明 |
| [adb/protocol.txt（AOSP）](https://android.googlesource.com/platform/system/core/+/dd7bc3319deb2b77c5d07a51b7d6cd7e11b5beb0/adb/protocol.txt) | 24 字节报文格式、CNXN/AUTH/OPEN/READY/WRITE/CLOSE |
| [dadb（mobile-dev-inc，Apache-2.0，Kotlin）](https://github.com/mobile-dev-inc/dadb) | 纯 Kotlin 讲 ADB 协议的参考实现（4359 行） |
| [Kadb（flyfishxu，KMP）](https://github.com/flyfishxu/Kadb) | **Android USB Host 直连 ADB** + **Android 11+ 无线配对**（2111 行） |
| [USB host overview（Android 官方）](https://developer.android.com/develop/connectivity/usb/host) | UsbManager/openDevice/claimInterface/bulkTransfer |
| [ADB wire protocol（rsadb 独立描述）](https://github.com/tbraun96/rsadb/blob/main/docs/protocol.md) | 报文字段、Android 公钥 524 字节布局的独立佐证 |

---

## 2. 分层架构

```
┌─────────────────────────────────────────────────────────┐
│ 工具层  adb_dev（AI 调用）                                │
│  list / connect / shell / push / pull / install /        │
│  forward / pair / tcpip / devices                        │
├─────────────────────────────────────────────────────────┤
│ 会话层  QuroAdbClient                                    │
│  多路复用（一条连接上并发多条 stream）、stream 生命周期    │
├─────────────────────────────────────────────────────────┤
│ 协议层  AdbConnection                                    │
│  CNXN 握手 → AUTH 认证（RSA）→ OPEN/OKAY/WRTE/CLSE        │
│  STLS（TLS 升级，仅无线调试连接用）                        │
├─────────────────────────────────────────────────────────┤
│ 传输层  AdbTransport（接口）                              │
│   ├─ TcpAdbTransport   Socket（网络 / 本机回环）           │
│   └─ UsbAdbTransport   UsbDeviceConnection.bulkTransfer  │
├─────────────────────────────────────────────────────────┤
│ 身份层  AdbKeyPair                                       │
│  RSA 2048 私钥 + Android 专用公钥编码（524B 二进制）       │
└─────────────────────────────────────────────────────────┘
```

**为什么传输层要抽象**：USB 和 TCP 的差别只在「字节怎么进出」，协议层完全一致。
把差异隔离在 transport，才能一套协议代码同时支持 USB / 网络 / 回环。

---

## 3. 协议层：报文与握手

### 3.1 报文格式（官方 protocol.txt）

每条消息 = **24 字节小端头 + 可选 payload**：

| 偏移 | 大小 | 字段 | 说明 |
|---|---|---|---|
| 0 | 4 | command | 下面那些常量 |
| 4 | 4 | arg0 | 命令相关 |
| 8 | 4 | arg1 | 命令相关 |
| 12 | 4 | data_length | payload 字节数 |
| 16 | 4 | data_check | payload 累加和（新版本忽略） |
| 20 | 4 | magic | `command ^ 0xFFFFFFFF` |

命令常量：

| 名称 | 值 | arg0 | arg1 | payload |
|---|---|---|---|---|
| CNXN | `0x4e584e43` | version | maxdata | `"host::<banner>\0"` |
| AUTH | `0x48545541` | 1=token / 2=signature / 3=pubkey | 0 | token / 签名 / 公钥 |
| OPEN | `0x4e45504f` | local-id | 0 | 服务名 + `\0` |
| OKAY | `0x59414b4f` | local-id | remote-id | 无 |
| WRTE | `0x45545257` | local-id | remote-id | 数据 |
| CLSE | `0x45534c43` | local-id | remote-id | 无 |
| STLS | `0x534c5453` | TLS 版本(1) | 0 | 无 |

> 注意：协议里 `READY`/`WRITE`/`CLOSE`（旧文档用词）与 `OKAY`/`WRTE`/`CLSE`（实际实现用词）
> 是同一批消息。以 `adb.h` 的常量为准。

### 3.2 握手顺序

```
host                                  device(adbd)
 │  CNXN(0x01000000, 1MiB, "host::")  │
 │ ─────────────────────────────────► │
 │                                    │
 │  ◄──────── AUTH(1, token) ─────────│   ← 设备要求认证
 │  AUTH(2, RSA-sign(token)) ────────►│
 │                                    │
 │  ◄──────── AUTH(1, token2) ────────│   ← 还不认识我们
 │  AUTH(3, androidPubkey) ──────────►│   ← 上报公钥，等用户在设备上点「允许」
 │                                    │
 │  ◄──────── CNXN(devFeatures) ──────│   ← 认证通过
 │                                    │
 │  OPEN(local=1, 0, "shell:ls\0") ──►│   ← 开一条 stream
 │  ◄──────── OKAY(remote, local) ────│
 │  WRTE(0, remote, data) ───────────►│
 │  ◄──────── OKAY / WRTE(data) ──────│
 │  CLSE(1, remote) ─────────────────►│
```

**实现要点（dadb 已验证）**：
1. 签名不是标准 `SHA256withRSA`，而是 **`RSA/ECB/NoPadding` + 手写 PKCS#1 v1.5 填充**
   （前缀 `00 01 FF...FF 00 30 21 30 09 06 05 2B 0E 03 02 1A 05 00 04 14` 再拼 token）。
   写成 `SHA256withRSA` 会被设备拒绝。
2. 认证失败的表现是**再收到一个 AUTH**，要按「用户未授权」处理，不要死循环重试。
3. `CNXN` 的 version 用 `0x01000000` 即可；`maxdata` 建议 1MiB（老设备会给 256KiB，取双方最小值）。

### 3.3 常用服务名（OPEN 的 payload）

| 服务 | 用途 | 返回 |
|---|---|---|
| `shell:<cmd>\0` | 执行命令（shell v1） | 原始 stdout/stderr |
| `shell,v2,raw:<cmd>\0` | shell v2 | 带 stream id 的包（stdout/stderr/exit） |
| `sync:\0` | 文件推拉协议（子协议见 §3.5） | 二进制 |
| `tcp:<port>\0` | 连设备端口 | 双向字节流 |
| `reboot:<mode>\0` | 重启 | — |
| `root:\0` | 提权 adbd（仅 userdebug/eng） | — |

### 3.5 sync 子协议（push/pull 用）

每条 = `4 字节 id + 4 字节小端长度`，id 为 ASCII：

| id | 方向 | 含义 |
|---|---|---|
| `SEND` | host→dev | 推文件：`SEND` + len + `"path,mode"` |
| `DATA` | host→dev | 数据块 |
| `DONE` | host→dev | 结束（pull 时是 len=0 的参数） |
| `OKAY` | dev→host | 成功 |
| `FAIL` | dev→host | 失败 + 原因 |
| `STAT` | host→dev | 取 stat |
| `RECV` | host→dev | 拉文件 |
| `QUIT` | host→dev | 关闭会话 |

---

## 4. 传输层

### 4.1 USB（调试另一台机器，**这是当前最大空白**）

**识别规则（三个开源实现一致）**：

```
interfaceClass    == 0xFF   // vendor specific
interfaceSubclass == 0x42   // 'B' — ADB
interfaceProtocol == 0x01
且该 interface 同时具备 bulk IN 与 bulk OUT 端点
```

**流程**：

```kotlin
val usbManager = context.getSystemService(UsbManager::class.java)
val devices = usbManager.deviceList.values.filter { isAdbDevice(it) }   // 按上面规则筛

// 1. 申请权限（与对方设备的「允许 USB 调试」是两回事，这一步是本机给 App 授权访问 USB 设备）
usbManager.requestPermission(device, pendingIntent)

// 2. 打开并独占接口
val conn = usbManager.openDevice(device)!!
val intf = /* 上面找到的 adb interface */
conn.claimInterface(intf, true)

// 3. 之后就是纯字节流：bulkTransfer(out, buf, len, timeout) / bulkTransfer(in, buf, len, timeout)
//    把这两个端点包装成 AdbTransport，协议层完全不用改
```

**必须注意的坑**：
- 需要 `<uses-feature android:name="android.hardware.usb.host" />`，且**不是所有手机都支持 USB Host**
  （很多机型 OTG 只能供电/挂 U 盘，不能做 host 直连另一台手机的 ADB）。
- 一根 OTG 线接两台手机时，**只有一台是 host**，另一台必须是 device（开 USB 调试）。
  谁是 host 由线材和端口角色协商决定，不是软件能选的。
- USB 总线地址（deviceName）**不能作为设备身份持久化**，拔插后会变；要用 serialNumber。
- 打开后要 `releaseInterface` + `close`，否则下次连不上。
- 每次 bulkTransfer 都要给超时，且读操作必须放在 IO 线程（阻塞）。

### 4.2 TCP（网络 / 回环）

```kotlin
val socket = Socket()
socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
// 之后的 in/out 流包装成 AdbTransport
```

- **端口**：legacy `tcpip 5555` 是 5555；无线调试（Android 11+）是**随机高位端口**，必须靠 mDNS 或用户手输。
- 注意：模拟器是 **5555**（奇数），不是 5554（偶数，那是控制台端口）。这个坑踩的人最多。
- 同网段调试还需要对方先执行 `adb tcpip 5555`（这一步必须能连上对方一次 USB 或对方有 root）。

---

## 5. 无线调试配对（Android 11+）—— 最难的一块

### 5.1 三个 mDNS 服务类型（官方文档）

| 服务类型 | 谁发布 | 含义 |
|---|---|---|
| `_adb._tcp` | adbd | legacy `tcpip` 5555 |
| `_adb-tls-pairing._tcp` | NsdServiceInfo（配对服务） | 配对入口，**只开很短时间** |
| `_adb-tls-connect._tcp` | adbd | TLS 连接入口，配对成功后长期可用 |

实例名前缀：`adb-<ro.serialno>-<随机后缀>`；配对服务的 QR 模式前缀是 `studio-`。

### 5.2 配对协议（Kadb 已验证的完整流程）

```
1. 连上 _adb-tls-pairing 服务的 IP:port（明文 TCP）
2. 立即升级 TLS：双方用**临时自签证书**握手（TLS 1.3）
3. 从 TLS 连接导出 64 字节 keying material，label = "adb-label\0"
   —— 这一步是防中间人的关键：SPAKE2 的口令 = 6 位配对码 + 这 64 字节
4. SPAKE2（PAKE）交换：
   - 身份串固定：client = "adb pair client\0"，server = "adb pair server\0"
   - 口令 = 配对码，角色 = Alice（host 侧）
5. HKDF-SHA256 从 SPAKE2 共享秘密导出 16 字节 AES key
6. 之后的消息用 AES-128-GCM 加密，IV 递增
7. 在加密通道内交换 PeerInfo（各自的长效 RSA 公钥）
8. 设备把 host 公钥写入 /data/misc/adb/adb_keys → 配对完成
9. 之后连接走 _adb-tls-connect 服务：TCP → STLS → TLS → 标准 ADB 握手
```

### 5.3 实现成本评估（诚实结论）

| 部分 | 成本 | 说明 |
|---|---|---|
| mDNS 发现 | 低 | `NsdManager` 直接支持，Android 原生 API |
| TCP + TLS 1.3 | 中 | JSSE 即可，需自签证书 + 信任所有（因为要交换公钥） |
| TLS keying material 导出 | 中 | `SSLSocket` 无公开 API，需反射 Conscrypt 的 `exportKeyingMaterial`（hidden API，需 bypass） |
| **SPAKE2** | **高** | 需第三方库：`com.github.Flyfish233:spake2-java`（JitPack）或自己移植 bssl |
| AES-GCM + HKDF | 低 | JCE 自带 |

**结论**：配对是**唯一必须引入第三方密码学库**的部分。建议分两期：
- 一期：USB + TCP + 回环（不含配对）→ 覆盖「调试另一台机器」的 90% 场景
- 二期：引入 spake2 库做配对 → 覆盖「免数据线」场景

---

## 6. 被调试（本机作为 adb 设备）

### 6.1 让别的机器调试本机

本质是让 **adbd 在 TCP 上监听**。两条路：

**A. Legacy tcpip（兼容性最好）**
```sh
setprop service.adb.tcp.port 5555
stop adbd && start adbd          # 或 setprop ctl.restart adbd
# 关闭：setprop service.adb.tcp.port -1 && stop adbd && start adbd
```
需要 **root**（改系统属性 + 重启 adbd 服务）。
Shizuku 只有 shell 权限，`setprop service.adb.tcp.port` 会被 SELinux 拒绝，
所以**Shizuku 单独不够**，实际要 root（或 userdebug 版本）。

**B. 无线调试（Android 11+，用户手动开）**
用户自己在「开发者选项 → 无线调试」打开，系统会：
- 起 Pairing Server（`_adb-tls-pairing._tcp`）
- 起 TLSServer（`_adb-tls-connect._tcp`，随机端口）
- 显示 6 位配对码 + IP:端口

App 能做的是：**引导用户去开**（`Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`），
以及**自己作为 host 去配对/连接**（§5），而不是替用户开。

> 这也是为什么 Mika 要「借无线调试拉起内置助手」——它需要 shell 身份，而当时没有 Shizuku 这条路。
> QuroAI 已有 Shizuku，**不需要绕无线调试拿 shell 权限**。

### 6.2 本机回环（自己连自己）

```kotlin
// 前提：本机已开 tcpip 5555 或无线调试
TcpAdbTransport("127.0.0.1", 5555)
```
用途：拿到一个「标准 adb 接口」来执行命令，与直接 Shizuku exec 的差别是
多了一层协议开销，但语义与电脑上 `adb shell` 完全一致（某些只认 adb 的流程需要它）。

---

## 7. 开源实现对比（选型依据）

| 项目 | 语言 | USB host | 无线配对 | 被依赖体积 | 许可 | 结论 |
|---|---|---|---|---|---|---|
| **dadb** | Kotlin/JVM | ❌（需 adb server） | ❌ | 小 | Apache-2.0 | 协议层参考最佳，USB 不行 |
| **Kadb** | KMP | ✅ | ✅ | 中（BC + spake2 + okio） | MIT | **能力最全**，可直接依赖或移植 |
| **adblib** | Java（AOSP） | ❌ | ✅ | 大（platform tools） | MIT | 官方血统，但构建麻烦 |
| **Shizuku** | Java + JNI | ❌ | ✅（Manager UI） | — | Apache-2.0 | 配对能力在 Manager 里，不是公开 API |

**选型建议**：
- 不想引入 KMP 依赖 → **照 Kadb 的结构自己写**（它 2111 行，分层清晰，可读性强）
- 想快速可用 → 直接依赖 `com.flyfishxu:kadb`（若无 Maven 发布则加 JitPack，或 vendor 源码）

---

## 8. 落地清单（分期）

### 一期（不引入第三方密码学库，纯 App 可完成）
- [x] 协议层：`AdbMessage` 编解码（24B 头 + 校验 + magic 校验）
- [x] 身份层：`AdbKeyPair`（RSA2048 生成/持久化 + Android 524B 公钥 + NoPadding 签名）
- [x] 传输层：`TcpAdbTransport`、`UsbAdbTransport`
- [x] 会话层：`AdbConnection`（CNXN/AUTH/OPEN/OKAY/WRTE/CLSE 多路复用）
- [x] 能力层：`shell` / `shell v2`（拿 exit code）/ `push` / `pull` / `install`
- [x] 管理：设备扫描（USB + mDNS）、连接池、断线清理
- [x] 工具：`adb_dev`（AI 可调用）

### 二期（需要 spake2 库）
- [ ] 无线配对（SPAKE2 + TLS keying material + AES-GCM）
- [ ] mDNS 自动发现 + 免密重连

### 三期（需 root）
- [ ] 本机 tcpip 启停（已部分实现于 `QuroAdbDebug`）
- [ ] 多用户/分身场景

---

## 9. 风险与硬约束（别踩）

1. **USB Host 不是所有机型都有** —— 必须运行时检测并给出人话提示，不能假设可用。
2. **`RSA/ECB/NoPadding` 手工填充** —— 写成标准 `SHA256withRSA` 一定失败。
3. **认证失败 = 再收到 AUTH** —— 要识别成「用户没在设备上点允许」，不能无限重试。
4. **无线配对端口是随机的且会关** —— 用户必须现场抄屏幕上的 IP:端口 + 6 位码，过期要重开。
5. **`setprop service.adb.tcp.port` 需要 root** —— Shizuku 不够，别在 Shizuku 模式下假装能开。
6. **拔插后 USB 身份会变** —— 用 serialNumber，不要缓存 deviceName。
7. **一条连接上多 stream 要严格按 OKAY 节流** —— 上一个 WRTE 没收到 OKAY 不能再发。
