import { formatItems, Item } from "./utils";

const items: Item[] = [
  { name: "苹果", price: 6 },
  { name: "香蕉", price: 3 },
];

console.log(formatItems(items));
console.log("总价：", items.reduce((s, i) => s + i.price, 0));

module.exports = { items };
