#!/usr/bin/env python3
"""项目入口：`run_code {lang:"python"}` 直接运行（App 内置原生 CPython 3.14）。"""


def main() -> None:
    print("Hello from Python!")
    # 标准库全量可用：json / re / math / datetime / sqlite3 / ...
    import json

    data = {"project": "my-python-app", "stdlib": "full"}
    print(json.dumps(data, ensure_ascii=False))


if __name__ == "__main__":
    main()
