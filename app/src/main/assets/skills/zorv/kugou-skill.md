---
name: kugou-skill
description: |
---

# kugou-skill

## AI 使用工作流（优先阅读）

使用本工具时的标准流程：

```
1. 检查安装 → npm install -g @kg-ai/kugou-skill
2. 检查登录 → kugou-cli auth status
3. 未登录 → 引导用户执行登录流程（详见 references/auth.md）
4. 执行用户请求的音乐命令（详见 references/music.md）
5. 解析 JSON 输出，按展示规范展示给用户（详见 references/output-format.md）
```

### 关键注意事项

- **登录流程极简**（详见 [references/auth.md](references/auth.md)）：
  1. `auth login` - 获取二维码图片，输出包含 `qrcode` 和 `qrcode_img_path`（**必须将图片发送给用户**）
  2. `auth status` - 循环调用此命令检查登录状态：已登录返回 `logged_in: true`；未登录但有 pending qrcode 时内部自动检查扫码状态，扫码成功则自动完成登录
- **直接导入 secret 登录**：当用户**已经持有**一个有效的 base64 secret 字符串（从别处获取的），直接调用 `kugou-cli auth set-secret "<secret>"` 即可完成登录，**跳过扫码流程**。这与扫码登录保存到同一份 `auth.json`，效果完全一致。secret 字符串含 `+` `/` `=` 是正常的，shell 里务必用引号包起来。
- **音乐命令依赖登录**：除了 `auth`、`install`、`version`、`--help` 以外，所有 `music` 子命令都需要先登录。如果收到 `"not logged in"` 错误，引导用户执行登录流程。
- **自动更新机制**：每次启动任意命令时会自动检测 npm 远端版本，若有新版会**自动执行** `npm install -g @kg-ai/kugou-skill@latest`，无需手动 `kugou-cli update`：
  - 关闭自动检查：加 `--no-update-check` 标志，或设置环境变量 `KUGOU_CLI_NO_UPDATE_CHECK=1`
  - 手动检查/触发：`kugou-cli update` 跳过本地缓存直接查远端并自动安装；`kugou-cli update --check` 仅检查不安装
  - 非 npm 安装（手动编译/容器）会打印提示和升级命令但不会自动执行
- **输出均为 JSON**：所有命令输出原始 JSON 到 stdout，错误输出到 stderr。解析 `errcode` 字段判断成功与否（`0` 为成功）。
- **歌曲展示规范**（详见 [references/output-format.md](references/output-format.md)）：**禁止**只返回歌曲名、歌手名，**必须**以 Markdown 链接格式展示播放链接。
- **创建歌单的调用原则**（详见 [references/music.md#8-创建歌单](references/music.md#8-创建歌单)）：
  1. **被动调用**：必须用户**明确**要求创建歌单时才调用 `music create-playlist`，禁止在用户仅说"推荐/搜歌"时主动创建
  2. **主动询问**：当通过搜索、推荐（猜你喜欢/相似/文本）等方式给出一批歌曲后，**必须**询问用户是否需要将当前这批歌曲创建为歌单，等用户确认后再调用 `music create-playlist --songs "<mix_song_id 列表>"`

---

## 基础信息

- **npm 包**: @kg-ai/kugou-skill
- **二进制命令**: kugou-cli
- **安装方式**: `npm install -g @kg-ai/kugou-skill`

---

## 详细文档索引

| 文档 | 说明 |
|------|------|
| [references/auth.md](references/auth.md) | 认证命令：扫码登录、直接设置 secret、查看状态、登出 |
| [references/music.md](references/music.md) | 音乐命令：搜索、推荐、收藏、统计、榜单、创建歌单 |
| [references/install.md](references/install.md) | 安装命令：SKILL.md 安装到各平台 |
| [references/update.md](references/update.md) | 更新命令：检查/执行自动更新 |
| [references/output-format.md](references/output-format.md) | 输出格式与展示规范 |
| [references/error-handling.md](references/error-handling.md) | 错误处理与常见错误 |

---

## 完整使用流程

```bash
# 1. 登录（极简流程，详见 references/auth.md）
kugou-cli auth login                      # 获取二维码
# 循环执行以下命令直到 logged_in=true
kugou-cli auth status

# 1'. 或者直接导入已持有的 secret（跳过扫码）
kugou-cli auth set-secret "<base64-secret>"

# 2. 搜索歌曲
kugou-cli music search "周杰伦"

# 3. 获取猜你喜欢
kugou-cli music recommend guess

# 4. 查看我的收藏
kugou-cli music favorites

# 5. 查看最近播放
kugou-cli music recent

# 6. 查看听歌统计
kugou-cli music stats

# 7. 查看抖音热歌榜
kugou-cli music charts 52144

# 8. 创建歌单
kugou-cli music create-playlist "我的空歌单"
kugou-cli music create-playlist "我的批量歌单" --songs "32068120,233125060"
```
