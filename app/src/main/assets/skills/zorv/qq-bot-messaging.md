---
name: qq-bot-messaging
description: QQ 机器人消息收发框架，基于 qq-botpy SDK。支持三种消息场景：频道 @ 消息、C2C 私聊消息、群 @ 消息。提供完整的收发消息模式、定时推送循环和启动模板。当用户需要开发 QQ 机器人、处理 QQ 频道/群/私聊消息、实现定时推送功能时使用。
---

# QQ Bot Messaging Skill

基于 [qq-botpy](https://github.com/tencent-connect/botpy) 的 QQ 机器人消息收发框架。

## 安装依赖

```bash
pip install qq-botpy
```

## 配置

创建 `config.yaml`：

```yaml
appid: "YOUR_APPID"
secret: "YOUR_SECRET"
```

## 三种消息场景

### 1. 频道 @ 消息（Guild）

- **Intent**：`public_guild_messages=True`
- **事件**：`on_at_message_create(message: Message)`
- **消息对象**：`from botpy.message import Message`
- **回复**：`await message.reply(content="...")`
- **主动发送**：`await self.api.post_message(channel_id=..., content=..., msg_id=message.id)`
- **用户标识**：`message.author.id`，频道：`message.channel_id`
- **解析指令**：消息内容含 `<@!botid>` 前缀，需先剔除（见 `scripts/bot_utils.py` 中的 `parse_cmd()`）

### 2. C2C 私聊消息

- **Intent**：`public_messages=True`
- **事件**：`on_c2c_message_create(message: C2CMessage)`
- **消息对象**：`from botpy.message import C2CMessage`
- **回复**：`await message._api.post_c2c_message(openid=..., msg_type=0, msg_id=message.id, content=...)`
- **用户标识**：`message.author.user_openid`

### 3. 群 @ 消息（Group）

- **Intent**：`public_messages=True`
- **事件**：`on_group_at_message_create(message: GroupMessage)`
- **消息对象**：`from botpy.message import GroupMessage`
- **回复**：`await message._api.post_group_message(group_openid=..., msg_type=0, msg_id=message.id, content=...)`
- **用户标识**：`message.author.member_openid`，群：`message.group_openid`

> C2C 和群消息共用 `public_messages=True`，可同时监听两者。

## 定时推送循环

使用 `asyncio.create_task()` 启动后台协程实现定时推送：

```python
task = asyncio.create_task(self._push_loop(key, message))
tasks[key] = task
# 取消：tasks.pop(key).cancel()
```

循环内用 `await asyncio.sleep(seconds)` 等待，用 `try/except asyncio.CancelledError` 优雅退出。

## 启动模板

```python
# Python 3.10+ 必须显式创建 event loop
loop = asyncio.new_event_loop()
asyncio.set_event_loop(loop)

intents = botpy.Intents(public_guild_messages=True, public_messages=True)
client = MyBot(intents=intents)
client.run(appid=config["appid"], secret=config["secret"])
```

## 完整模板

见 `scripts/bot_template.py`，包含三种消息处理器 + 定时推送的完整可运行示例。

## 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `RuntimeError: There is no current event loop` | Python 3.10+ 不再自动创建 loop | 启动前调用 `asyncio.new_event_loop()` + `asyncio.set_event_loop()` |
| 收不到消息 | Intent 未开启 | C2C/群需要 `public_messages=True`，频道需要 `public_guild_messages=True` |
| 发消息失败 | `msg_id` 过期（被动消息有效期短） | 定时推送需申请主动消息权限，或每次收到消息后更新 `msg_id` |
