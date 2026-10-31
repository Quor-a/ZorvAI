---
name: mail.google.com
display: Gmail
description: Gmail 网页版的邮件搜索、阅读、写信和标签
domains: ["mail.google.com"]
aliases: ["Gmail", "Google Mail", "谷歌邮箱"]
apps: ["gmail"]
open: mail.google.com
mode: desktop
---
## 是什么
Google 的网页邮箱，需要登录 Google 账号。

## 页面与网址
入口 `https://mail.google.com/`。界面：顶部是搜索框，左侧有写信（Compose）和收件箱、已发送、草稿、垃圾邮件、已删除及自定义标签，中间是邮件列表。界面语言随账号设置，按钮名不一定是英文。在当前账号地址后接 `#search/<编码后的查询>` 可直接搜索，地址里的账号序号（如 `u/0`）对应当前登录的账号。搜索语法见 `references/gmail-search-syntax.md`。

## 功能
- **列表与搜索**：每行有发件人、主题、日期、是否未读，没有正文。列表是虚拟滚动的，往下滚时上面的行会被移除，一次滚过的行数不是邮箱里邮件的总数。
- **邮件正文**：点开邮件，看到发件人、主题、正文、时间和附件。打开邮件可能改变未读状态。
- **写信、回复、转发**：写新邮件用 Compose；回复和转发要先打开对应邮件。回复时收件人和主题自动填入。发送后邮件出现在已发送里，写信窗口关闭不说明已发出。

## 已知现象
登录 Google 账号和「确认是你本人」是两道不同的验证。
