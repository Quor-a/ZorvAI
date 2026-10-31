---
name: xiaohongshu.com
display: 小红书
domains: ["xiaohongshu.com", "xhslink.com"]
aliases: ["小红书", "Xiaohongshu", "XHS", "xiaohongshu", "xhs"]
apps: ["xiaohongshu"]
open: xiaohongshu.com
mode: desktop
description: 图文和视频笔记社区,可搜笔记并读取正文、互动数和评论
---
## 是什么
以用户笔记为主的内容社区,笔记是图文或视频,其中有广告和求推荐帖。

## 页面与网址
- 搜索:`https://www.xiaohongshu.com/search_result?keyword=<编码关键词>&source=web_explore_feed`,直接打开即可。
- 笔记详情:`/explore/<笔记ID>` 并带 `xsec_token` 参数,没有 token 打不开。
- `xhslink.com` 是分享短链。

## 功能
- **搜索**:结果是卡片,含标题、作者、点赞数、封面、图文或视频类型和链接。第一屏约 20 条,往下滚两三次可到五六十条。通常需要有效的登录会话。
- **笔记详情**:正文、发布时间、IP 属地、点赞、收藏、评论数,以及评论(作者、原话、点赞数)。在搜索结果里点开卡片会弹出详情层,卡片链接自带 `xsec_token`。
- **手机 App**:对应的说明见 `xiaohongshu`。

## 已知现象
- 部分卡片没有标题,显示的是正文开头一段。
- 评论一开始只加载第一页;评论数显示为 0 才表示没有评论。
- 「1.2万」这类缩写计数按原样显示。
- 发布时间和更新时间是两个字段,有的笔记只显示后者。
- 搜索结果里夹有广告卡,不是笔记。
- 服务繁忙时会提示网络繁忙,过几秒重试通常恢复。
