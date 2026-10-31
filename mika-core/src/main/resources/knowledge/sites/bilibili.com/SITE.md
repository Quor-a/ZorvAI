---
name: bilibili.com
display: 哔哩哔哩
domains: ["bilibili.com"]
aliases: ["B站", "哔哩哔哩", "bilibili", "b站"]
apps: ["bilibili"]
open: bilibili.com
mode: desktop
description: 视频网站,搜实测、拆机、评测视频并看评论区反馈。
---
## 是什么

国内的视频社区,内容覆盖广。3C 数码的拆机、横评、长期使用类视频较多,白电、家居、母婴相对少。视频作者里既有独立测评者,也有厂商或带商业合作的账号。另有同名手机 App。

## 页面与网址

- 搜索:`https://search.bilibili.com/all?keyword=<关键词>`,关键词需 URL 编码。要限定发布时间,加 `pubtime_begin_s`、`pubtime_end_s`,值是 Unix 秒级时间戳。
- 视频页:`/video/BV…`。番剧和专栏的地址不是 BV 视频。

## 功能

- **视频搜索**:结果有视频链接、标题、发布日期。
- **视频页**:简介正文和发布时间;正文不含评论。
- **评论区**:评论渲染在单独的 shadow DOM 里,整页读文字或滚动外层页面常拿不到。能读到的通常是按热度排前面的部分,每条有作者、正文、点赞数、楼中楼回复数、日期、是否置顶;页面上另有评论总数。

## 已知现象

- 评论没读到,可能是评论区关闭、没有评论或加载失败。
- 评论接口有签名,页面外直接请求不稳定。
