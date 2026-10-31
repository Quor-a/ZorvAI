# Gmail 搜索语法

可以输入顶部搜索框，也可以编码后放在地址的 `#search/` 之后。多个条件用空格隔开，要同时满足。

| 想查什么 | 写法 |
|---|---|
| 位置 | `in:inbox`、`in:sent`、`in:drafts`、`in:trash`；`in:anywhere` 连垃圾邮件和已删除也查 |
| 状态 | `is:unread`、`is:starred`、`is:important` |
| 标签与分类 | `label:work`、`category:promotions`、`category:social` |
| 发件人、收件人、抄送 | `from:alice@example.com`、`to:`、`cc:` |
| 主题 | `subject:invoice` |
| 附件 | `has:attachment`；`filename:pdf` 按附件名或类型 |
| 大小 | `larger:5M`、`smaller:1M` |
| 相对时间 | `newer_than:7d`、`older_than:30d`；单位 d 天、m 月、y 年 |
| 绝对日期 | `after:2026/02/01`、`before:2026/03/01` |
| 任一满足 | `from:alice OR from:bob` |
| 排除 | `-from:noreply@` |
| 分组 | `(from:alice OR from:bob) subject:meeting` |
| 整句 | `"exact phrase"` |

例：最近一天的未读邮件，排除促销分类：`is:unread newer_than:1d -category:promotions`
