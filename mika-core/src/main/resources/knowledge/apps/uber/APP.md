---
name: uber
display: Uber
packages: ["com.ubercab"]
aliases: ["Uber", "优步", "uber"]
sites: []
open: Uber
description: 车型、价格与行程
---
## 是什么
Uber 是打车 App,包名 `com.ubercab`。

## 界面
底部入口有 Home、Services、Activity、Account。Home 里的「Where to?」填行程;Later 等入口用来预约指定时间的行程。

## 功能
- **估价**:填上车点和目的地后得到车型列表,有车型名、原币种的估价、预计到达时间、容量、动态加价。价格通常需要登录才显示。
- **行程**:Activity 里是历史和当前行程;已叫车的行程能看到司机、车牌和到达时间。

## 已知现象
- 默认上车点是设备 GPS,但可以改成别的城市的地址,界面上有「Search another city」入口。
- 地址输入框有清除按钮 X;输入后从建议列表里选择匹配项。
- 机场地址会追问 Which airline 等,有 Skip 可以跳过。
- 输入框的内容可能在输入过程中变化,输入后需要重新读一遍。
