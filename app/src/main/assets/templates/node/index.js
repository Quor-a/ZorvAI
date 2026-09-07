const _ = require("lodash");

const items = [
  { name: "苹果", cat: "水果", price: 6 },
  { name: "香蕉", cat: "水果", price: 3 },
  { name: "硬盘", cat: "数码", price: 399 },
];

const byCat = _.groupBy(items, "cat");
console.log("分类结果：", JSON.stringify(byCat, null, 2));
console.log("平均价格：", _.mean(items.map((i) => i.price)));

// 导出给 ToolPkg / code_runner 复用
module.exports = { items, byCat };
