---
name: maps.google.com
display: Google Maps
description: 查地点详情,以及驾车、公交、步行等路线
domains: ["maps.google.com"]
aliases: ["Google Maps", "谷歌地图"]
apps: []
open: maps.google.com
mode: desktop
---
## 是什么
谷歌的网页地图,可查地点资料和路线,通常不用登录。实际网址在 `google.com/maps` 下,与 Google Flights 等共用 google.com 域名。

## 页面与网址
- 搜索:`https://www.google.com/maps/search/<编码后的查询>`。
- 路线:`https://www.google.com/maps/dir/<编码后的起点>/<编码后的终点>`。

## 功能
- **地点**:名称、地址、评分和评价数、营业状态、完整营业时间、电话。营业时间和电话要在详情里展开才看得到。
- **路线**:打开路线面板后输入起点和终点,切换出行方式(驾车、公交、步行等)。每种方式下会列出多个方案,每个方案有用时、距离、经过的道路或换乘线路和附注。

## 已知现象
- 网址里带着地点名,不等于页面选中了那个地点;同名分店很多,要核对页面上的名称、地址和城市。
- 在起点、终点输入框里直接按回车,可能选中当前位置、最近搜索过的地点或同名的另一处,从候选列表里点选更稳。
- 切换出行方式或点开另一张方案卡后,页面内容整体刷新,之前读到的数据属于上一个状态。
