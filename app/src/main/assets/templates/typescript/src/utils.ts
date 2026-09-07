export interface Item {
  name: string;
  price: number;
}

export function formatItems(items: Item[]): string {
  return items.map((i) => `${i.name}：￥${i.price}`).join("\n");
}
