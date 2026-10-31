---
name: documents
display: 处理 PDF 文档
description: 读 PDF 的文字、按页取、看文档信息,拆分合并后把文件交给用户
aliases: ["读 PDF", "PDF 转文字", "合并 PDF"]
terms: ["PDF", "文档", "提取文字", "按页", "合并", "拆分", "扫描件"]
---
## 入口

先看文档在哪:
- 网上的公开链接:用 `web_navigate` 打开,内置浏览器能直接显示的,读页面就够了。
- 用户发来的附件:在 `/workspace/inbox/`。
- 网页上下载的文件:在 `/workspace/downloads/`,可以用 `web_downloads` 查下载记录。

## 步骤

1. 文字型 PDF 用 `run_bundled_script` 跑 `scripts/extract_pdf.py`,`source` 填 `skills/documents`,文件路径相对 `/workspace` 写:
   - 全文:`args: ["inbox/a.pdf"]`
   - 指定页(从 1 数,可写 `3`、`1-5`、`1,4,7-9`):`args: ["inbox/a.pdf", "--pages", "1-5"]`
   - 标题、作者、页数等信息(JSON):`args: ["inbox/a.pdf", "--metadata"]`
   - 长文档先存成文本:`args: ["inbox/a.pdf", "--out", "a.txt"]`,再用 `read_file` 分段读,或用 `search_files` 找关键词。
   - 有密码的:加 `--password` 和密码。
2. 输出里每页以 `--- page N ---` 开头,引用时按它写页码。
3. 拆分、合并、抽页用 `run_command` 写一小段 Python,调用 pypdf;新文件放在 `/workspace` 下。
4. 做好的文件用 `share_file` 交给用户。

## 容易出错的地方

- 脚本退出码为 3、提示没有可取的文字,说明是扫描出来的图片型 PDF;本机没有文字识别,只能看页面图片或告诉用户这一点。部分页没有文字时,脚本会单独报出这些页。
- 表格、多栏排版取出来的顺序常常是乱的,引用表格里的数字前要对照原页。
- 页码范围超出文档页数时脚本直接报错,先用 `--metadata` 看页数。

## 怎样算办成(核实证据)

给出的文字能在脚本输出里对应到原页,并写了页码;交出去的文件 `share_file` 成功,页数和内容与预期一致(可以对新文件再跑一次 `--metadata` 核对页数)。

## 什么时候停下交给用户

文档有密码而用户没给,或者需要用户决定保留哪些页、按什么顺序合并时,先问用户。
