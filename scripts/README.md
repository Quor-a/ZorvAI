# scripts/ · ZorvAI 签名工具

宿主与 APK 插件**必须同签名**（插件经 `DexClassLoader` 跑在宿主进程内、权限等同宿主），
所以签名是发布链路里最容易出错、也最不能出错的一环。这四个脚本把它收敛成可复现的命令。

| 脚本 | 作用 |
|---|---|
| `sign.sh` | 统一签名入口：构建 → 对齐 → 签名 → 一致性校验（幂等） |
| `verify.sh` | 指纹校验：安装前确认插件与宿主是同一证书签的 |
| `purge-before-push.sh` | 推送前扫描：密钥文件、硬编码口令、历史痕迹 |
| `setup-keystore.sh` | 初始化**项目外**密钥位（fork / CI 用；本仓库勿跑） |
| `lib/common.sh` | 公共库：凭证解析、指纹提取、SDK 工具定位 |

---

## 快速使用

```bash
# 1. 校验：桌面上的宿主 APK 与插件 APK 是不是同一把钥匙签的
scripts/verify.sh --keystore ~/Desktop/宿主.apk ~/Desktop/插件.apk

# 2. 补签：对未签名的产物签名（典型场景：-Punsigned 构建出的测试插件）
scripts/sign.sh plugin-signcheck/build/outputs/apk/release/plugin-signcheck-release-unsigned.apk

# 3. 构建 + 签名一个模块（产物已同签名则自动跳过，不会重复劳动）
scripts/sign.sh plugin-signcheck

# 4. 推送前自查
scripts/purge-before-push.sh
```

凭证自动从 `keystore.properties` 读取（该文件已被 `.gitignore` 忽略，不在 git 内）。
脚本**从不打印口令**，只打印可公开的证书 SHA-256 指纹。

---

## 密钥与指纹

| 项 | 值 |
|---|---|
| 密钥文件 | `app/zorvai_release.jks`（PKCS12，alias `zorvai`） |
| 宿主证书 SHA-256 | `D9:5B:1B:EC:57:B9:D5:EE:88:96:05:9C:0F:3C:B5:09:E5:E9:CE:7C:CD:AE:DB:9C:6B:2E:98:49:BA:10:C7:99` |
| ⚠️ 不是主密钥 | `app/zorvai-release-p12-v1cert.p12`（指纹 `C8:47:68:A0:…`）——不要用它签宿主或插件 |

指纹口径与 `PluginInstaller.sameSignature()` 一致：**证书的 SHA-256，大写冒号分隔**。

---

## 与上游 `zorvai-signing-kit` 的差异

套件原版假设自己被放在仓库的**一个子目录**里、且 Gradle 侧完全不签名。直接内联到本仓库
会出问题，因此落地时做了以下适配——每一条都是踩过才知道的：

| # | 上游行为 | 本仓库处理 | 为什么 |
|---|---|---|---|
| 1 | `REPO_ROOT="$(cd .. && pwd)"` | 改用 `git rev-parse --show-toplevel` | 上游假设自己在 `<repo>/zorvai-signing-kit/` 下；直接内联到 `scripts/` 后 `cd ..` 会指到仓库**外面** |
| 2 | 凭证只从 `~/.zorvai-signing/env.sh` 读 | 优先 `keystore.properties`，回退 `env.sh` / 环境变量 | 本仓库既有密钥在 `app/zorvai_release.jks`；换钥匙会让老用户无法覆盖安装、已装插件全部失效 |
| 3 | 要求 Gradle 侧一律不签名、全部由脚本补签 | **保留 Gradle 直签**，脚本负责补签未签名产物 + 一致性校验 | 现有 Gradle 直签是 v1.0.86~90 已发布包的签名来源，改链路风险大于收益 |
| 4 | `$bt/apksigner` / `$bt/zipalign` | 按 `无扩展名 → .exe → .bat` 探测；`ANDROID_HOME` 做 `D:\…` → `/d/…` 归一 | Windows 上 build-tools 只提供 `apksigner.bat` / `zipalign.exe` |
| 5 | 路径直接传给 `keytool` | MSYS 下 `/c/…` → `C:/…` 转换 | `keytool` 是 Windows 原生程序，传 MSYS 路径报 `NoSuchFileException: \c\Users\…` |
| 6 | 历史密钥痕迹也**阻断**推送 | 默认仅告警，`--strict` 才阻断 | 本仓库历史里有已删除的 AOSP 测试密钥（`app/test-*.pem/pk8`），阻断会逼人养成 `--no-verify` 的坏习惯；真正的红线是「被跟踪的密钥」与「硬编码口令」两层 |
| 7 | AI 不得构建/签名 APK | 红线收敛到**密钥材料本身** | 本仓库的日常工作流就是由 AI 交付 APK；详见 `AGENTS.md` |
| 8 | `.aiignore` 排除签名脚本 | 不排除 `scripts/*.sh` | 脚本不含私钥，需要可读以便排查；信任链靠 `AGENTS.md` + 钩子保障 |

---

## Git 钩子

已安装到 `.git/hooks/`（源文件在 `git-hooks/`，改动后需重新 `cp` 过去）：

| 钩子 | 拦什么 |
|---|---|
| `pre-commit` | 密钥文件、硬编码口令、构建产物入库 |
| `pre-push` | 调用 `purge-before-push.sh` 做深度扫描 |

重装钩子：

```bash
cp git-hooks/pre-commit git-hooks/pre-push .git/hooks/
chmod +x .git/hooks/pre-commit .git/hooks/pre-push
```

---

## fork / CI 场景

fork 或 CI 上不存在本仓库的 `keystore.properties`，走**项目外密钥位**：

```bash
# 生成 ~/.zorvai-signing/{zorvai-release.jks, env.sh}（chmod 600/700）
scripts/setup-keystore.sh --force

# CI：把密钥 base64 存进 Secret，运行时还原
```

⚠️ 警告：新生成的钥匙与线上发布版本**不同签名**，签出的 APK 老用户无法覆盖安装。
仅适用于全新分发链。

---

## 附录：为什么不能「安装时自动补签」

| 方案 | 私钥位置 | 校验是否还有效 | 泄露后果 |
|---|---|---|---|
| 内置私钥自动补签 | APK `assets/` 内 | ❌ 恒真 | 发布密钥作废，可被签出假冒更新 |
| **本方案** | 项目外 / git 外 | ✅ 真实生效 | 仅本地开发密钥 |

自动补签后 `apkSig == hostSig` 恒成立，安全强度与 `skip_signature_check=true` 完全相同，
却多付一个私钥泄露代价。把签名放在**构建期**、密钥放在**项目外**，校验才真正有意义。
