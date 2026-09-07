# Go 项目模板

入口 `main.go`，`go.mod` 已初始化模块名（创建后改成实际项目名）。

## 说明

- 端侧沙箱无 Go 工具链，**编译运行**走 ACI 构建台（`aci_call`）；
- 纯逻辑 / 算法设计先在本地写好，让 AI 云端编译验证。

## 结构

```
my-go-app/
├── main.go
├── go.mod
└── README.md
```
