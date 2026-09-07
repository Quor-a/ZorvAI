package main

import "fmt"

// 项目入口。端侧沙箱不内置 Go 工具链；撰写与设计在本地完成，
// 编译运行走 ACI 构建台（aci_call）。
func main() {
	fmt.Println("Hello from Go!")
}
