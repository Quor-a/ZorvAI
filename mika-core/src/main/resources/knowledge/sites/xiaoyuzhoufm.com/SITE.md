---
name: xiaoyuzhoufm.com
display: 小宇宙
domains: ["xiaoyuzhoufm.com"]
aliases: ["小宇宙", "Xiaoyuzhou"]
apps: []
open: xiaoyuzhoufm.com
mode: desktop
description: 中文播客平台,单集页可取音频地址、标题和时长
---
## 是什么
中文播客平台。每一集有公开的单集页,页面里带音频地址和元信息。

## 页面与网址
单集页 `https://www.xiaoyuzhoufm.com/episode/<id>`,`<id>` 是 20 到 32 位小写十六进制(`[0-9a-f]{20,32}`)。

## 接口
单集页整页 HTML 约 300KB,只需抽三个字段,在沙箱里用 `wget` 取页面后 `grep -oE` 过滤:

```sh
wget -qO- 'https://www.xiaoyuzhoufm.com/episode/<id>' | grep -oE '<meta property="og:audio" content="[^"]+"|"duration":[0-9]+|<meta property="og:title" content="[^"]+"'
```

- `og:audio`:音频文件的真实地址,通常在 `https://media.xyzcdn.net/` 下。
- `duration`:单集时长,单位秒。
- `og:title`:单集标题。

## 已知现象
页面改版后可能缺 `og:audio`,此时页面里取不到音频地址。
