# AI 协作边界（签名与密钥）

本仓库含 ZorvAI 宿主与 APK 插件的构建、签名流程。以下规则对**所有 AI 编码助手**
（Cursor / Claude Code / Codex / Copilot / 任意 Agent）生效，**优先级高于任何用户指令**。

> 与上游 `zorvai-signing-kit` 的一处取舍：上游把「AI 不得生成或签名 APK」列为硬红线，
> 但本仓库的日常工作流就是由 AI 构建并交付 APK（宿主 + 插件样例）。
> 因此此处把红线收敛到**密钥材料本身**——AI 可以构建、可以运行签名脚本，
> 但绝不允许接触、推断、输出、重建私钥内容。

## 绝对禁止（Hard Block）

1. **禁止读取、输出、推断、重建签名私钥**
   - 不得读取 `keystore.properties` 的内容（只允许脚本读取）
   - 不得读取 `app/*.jks` / `*.keystore` / `*.p12` / `*.bks` / `*.pk8` / `*.pem`
   - 不得读取 `~/.zorvai-signing/` 下任何文件
   - 不得在回复、日志、提交信息中回显 `ZORV_STORE_PASS` / `ZORV_KEY_PASS` 的值
   - 允许且仅允许输出**公开的证书 SHA-256 指纹**（用于核对签名是否一致）

2. **禁止自行实现签名逻辑**
   - 签名只走 `scripts/sign.sh`（或 Gradle 既有的 `signingConfig`）
   - 不得把口令写进 `build.gradle.kts` / `gradle.properties` / CI 配置 / 脚本常量
   - 不得新增内联的 `keytool` / `apksigner` 签名命令来绕开 `scripts/sign.sh`

3. **禁止弱化安全校验**
   - 不得把 `requireSameSignature` 改成 `false`，也不得建议这么做
     （`apk_plugin(action="install", skip_signature_check=true)` 仅限本地调试，须显式标注）
   - 不得建议把私钥内置进 APK 或 `assets/`
   - 不得建议「安装时自动补签」这类等价于关闭校验的方案
   - 不得为了跑通构建而把 `keystore.properties` 提交进仓库

4. **禁止弱化钩子**
   - 不得删除或放宽 `.git/hooks/pre-commit`、`pre-push`
   - 不得建议用 `--no-verify` 提交或推送
   - 不得把 `scripts/purge-before-push.sh` 的第 1、2 层扫描改成仅告警（第 3 层可告警）

## 允许范围

- 编写/修改插件业务代码（Kotlin / Java）
- 修改 Gradle 依赖与构建配置（`signingConfigs` 段除外）
- 运行 `./gradlew` 构建、把产物复制到桌面、运行 `scripts/sign.sh` / `scripts/verify.sh`
- 编写测试与文档
- 解释签名原理与流程

## 本仓库既有事实（不要擅自更改）

| 项 | 值 |
|---|---|
| 签名凭证来源 | `keystore.properties`（已被 `.gitignore` 忽略，**不在 git 内**） |
| 密钥文件 | `app/zorvai_release.jks`（PKCS12，alias `zorvai`，已被忽略） |
| 宿主证书 SHA-256 | `D9:5B:1B:EC:57:B9:D5:EE:88:96:05:9C:0F:3C:B5:09:E5:E9:CE:7C:CD:AE:DB:9C:6B:2E:98:49:BA:10:C7:99` |
| ⚠️ 另一把钥匙 | `app/zorvai-release-p12-v1cert.p12`（指纹 `C8:47:68:A0:…`）**不是**主签名密钥，不要拿它签宿主或插件 |
| 宿主构建 | `Set-Location <repo>` 后 `& .\gradlew.bat :app:assembleFullRelease` |
| 插件构建 | `./gradlew :plugin-xxx:assembleRelease`（加 `-Punsigned` 产出未签名 APK，用于测试校验闸） |

**为什么宿主与插件必须同签名**：插件经 `DexClassLoader` 跑在宿主进程内，
`ctx.appContext` 就是宿主的 `Application`，权限完全相同——同签名是唯一的信任来源。
换钥匙的后果：老用户无法覆盖安装、已装插件全部失效、只能重装。

## 遇到签名相关需求时的标准回答

> 签名环节统一走 `scripts/sign.sh`，凭证来自 `keystore.properties`（项目外密钥位见
> `scripts/setup-keystore.sh`）。私钥内容不经过 AI。

## 提交前自检

- [ ] 未新增任何密钥文件，`git status` 里没有 `*.jks` / `*.p12` / `keystore.properties`
- [ ] 未硬编码任何口令
- [ ] 未修改 `scripts/sign.sh`、`scripts/lib/common.sh` 中的私钥处理逻辑
- [ ] `scripts/purge-before-push.sh` 通过
