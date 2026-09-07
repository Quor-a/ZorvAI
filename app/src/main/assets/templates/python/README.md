# Python 项目模板

入口 `main.py`，用 `run_code {lang: "python", code: ...}` 运行（App 内置原生 CPython 3.14，含完整标准库）。

## 结构

```
my-python-app/
├── main.py           # 入口：python main.py 的逻辑
├── requirements.txt  # 依赖说明（端侧沙箱不装三方库，见内注）
└── README.md
```

## 建议

- 数据处理 / 算法计算 / 爬虫解析用 python 直接跑；
- 结果要可视化时，让 AI 输出 ```html 工件或 AIP 排版（```aip 围栏）；
- 文件读写走 AI 的 write_file / 工作区，或 JS 沙盒 Tools.Files。
