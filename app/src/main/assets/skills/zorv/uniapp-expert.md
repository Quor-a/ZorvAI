---
name: uniapp-expert
description: uni-app 跨平台开发专家技能，涵盖 Vue2/Vue3 开发、Vue2→Vue3 迁移实战、微信小程序自动化测试。融合真实项目踩坑经验，提供从开发到测试的完整解决方案。
---

# uniapp-expert

> uni-app 全栈开发专家 · Vue2→Vue3 迁移 · 微信小程序自动化测试

## 核心能力

- **uni-app 开发**：Vue2/Vue3 双版本开发指导
- **Vue2→Vue3 迁移**：完整的迁移检查清单与实战方案
- **微信小程序自动化测试**：从环境配置到脚本编写的全流程实战

---

## Vue3 开发指南

### Composition API vs Options API

| 特性 | Options API | Composition API | 推荐 |
|------|------------|----------------|------|
| 代码组织 | 按选项类型分散 | 按功能逻辑集中 | Composition |
| 逻辑复用 | mixins（隐式） | Composables（显式） | Composition |
| 类型推导 | 较弱 | 强（配合 TS） | Composition |
| 学习曲线 | 简单 | 稍陡 | 新项目用 Composition |
| 兼容性 | - | Vue3 完全支持 | - |

> 💡 **建议**：新项目使用 Composition API（`<script setup>`），享受更好的类型推导和逻辑复用。

### `<script setup>` 语法糖

`<script setup>` 是 Vue3 新增的语法糖，让组件代码更简洁：

```vue
<script setup>
import { ref, computed, onMounted } from 'vue'

// 响应式数据
const count = ref(0)
const doubled = computed(() => count.value * 2)

// 方法
function increment() {
  count.value++
}

// 生命周期
onMounted(() => {
  console.log('组件挂载完成')
})

// 暴露给模板
defineExpose({ count, increment })
</script>
```

**优势**：
- 更少的样板代码
- 自动推断类型
- 更好的 Tree-shaking

### 常用 Composables 推荐

| Composable | 功能 | 使用场景 |
|------------|------|---------|
| `useRequest` | 请求封装 | API 调用、loading 状态 |
| `useLoading` | 加载状态 | 异步操作反馈 |
| `useToast` | 轻提示 | 操作成功/失败提示 |
| `useModal` | 弹窗封装 | 确认框、对话框 |

```javascript
// 示例：useLoading
import { ref } from 'vue'

export function useLoading(initial = false) {
  const loading = ref(initial)
  const start = () => { loading.value = true }
  const stop = () => { loading.value = false }

  const withLoading = async (fn) => {
    start()
    try {
      return await fn()
    } finally {
      stop()
    }
  }

  return { loading, start, stop, withLoading }
}

// 使用
const { loading, withLoading } = useLoading()
await withLoading(() => fetchData())
```

### 开发规范建议

| 规范 | 说明 |
|------|------|
| **响应式数据用 `ref`/`reactive`** | 不要直接赋值（需要 `.value` 或解构） |
| **大对象用 `reactive`** | 避免 `ref` 解构丢失响应性 |
| **Props 定义类型** | 使用 `defineProps` with TypeScript 或 `propTypes` |
| **事件用 `emit`** | 清晰定义事件名，建议常量 |
| **组合式逻辑抽离** | 超过 50 行考虑抽成 Composable |
| **避免 `watch` 滥用** | 优先用 `computed` |

### 响应式数据对比

```javascript
// ❌ 错误：丢失响应性
const obj = reactive({ count: 0 })
const { count } = obj  // count 不再是响应式

// ✅ 正确：保持响应性
const obj = reactive({ count: 0 })
const count = toRef(obj, 'count')  // 或
const { count } = toRefs(obj)      // 解构后仍响应式
```

---

## Vue2 → Vue3 迁移检查清单

处理 uni-app Vue2 → Vue3 迁移任务时，必须执行以下检查：

### 1. 生命周期钩子

| Vue2 | Vue3 | 说明 |
|------|------|------|
| `destroyed` | `unmounted` | Vue 组件生命周期 |
| `beforeDestroy` | `beforeUnmount` | Vue 组件生命周期 |
| `onUnload` | **保留不变** | uni-app 页面生命周期，全平台支持 |

> ⚠️ **重要区分**：uni-app 页面生命周期（onLoad/onShow/onUnload 等）**全部保留**，只有 Vue 组件的生命周期钩子有变化。

### 2. 全局 API

| Vue2 | Vue3 |
|------|------|
| `new Vue()` | `createSSRApp()` |
| `Vue.prototype` | `app.config.globalProperties` |
| `Vue.use()` | `app.use()` |

### 3. 模板语法

| Vue2 | Vue3 | 说明 |
|------|------|------|
| `v-model: value` | `v-model: modelValue` | 默认 model 名 |
| `v-model: input` | `v-model: update:modelValue` | 默认事件名 |
| `slot="xxx"` | `v-slot:xxx` 或 `#xxx` | 具名插槽 |
| `.sync` 修饰符 | `v-model:xxx` | 双向绑定 |
| `v-if` + `v-for` 同一元素 | 分离 | Vue3 v-if 优先级更高 |
| `{{ value \| filter }}` | 计算属性或方法 | 过滤器已移除 |

### 4. 组件选项

| Vue2 | Vue3 |
|------|------|
| 无 | 推荐声明 `emits` 选项 |
| `inheritAttrs: false` | 保留 |
| `functional: true` | 已移除 |

> 💡 **选项式 API 兼容性**：Vue2 的选项式 API（Options API）在 Vue3 中**仍然兼容**。如果 Vue 组件逻辑简单、文件较大，直接转换容易出错，建议：
> 1. 询问用户是否需要转换
> 2. 简单页面可保留选项式语法
> 3. 复杂页面（如涉及多个 mixins、复杂响应式逻辑）才建议转为 Composition API

### 4.1 Mixins 处理建议

Vue3 仍支持 Mixins，但有更好的替代方案：

#### Mixins 在 Vue3 中的变化

| Vue2 | Vue3 | 说明 |
|------|------|------|
| `mixins: [xxx]` | `mixins: [xxx]` | 仍然支持 |
| 无 | `extends` 已保留但少用 | - |
| - | **推荐使用 Composables** | `useXxx()` 函数 |

#### Mixins vs Composables 对比

```javascript
// ❌ Mixins（隐式依赖，不清晰）
// mixin.js
export default {
  data() { return { count: 0 } },
  methods: { increment() { this.count++ } }
}
// 组件中使用 - 不知道 count 来自哪里
```

```javascript
// ✅ Composables（显式依赖，更清晰）
// useCounter.js
import { ref } from 'vue'
export function useCounter() {
  const count = ref(0)
  const increment = () => count.value++
  return { count, increment }
}
// 组件中使用 - 明确知道依赖
import { useCounter } from '@/composables/useCounter'
const { count, increment } = useCounter()
```

#### 迁移建议

| 场景 | 建议 |
|------|------|
| 简单 mixin（少量 data/methods） | 保留，继续使用 |
| 复杂 mixin（多个生命周期钩子） | 考虑改为 Composables |
| 新增逻辑 | **强烈建议使用 Composables** |

> 📝 **生命周期钩子合并规则**（了解即可）：
> - 多个 mixin 的同名生命周期钩子会**全部调用**
> - 执行顺序：mixin1 → mixin2 → 组件自身
> - 如需在组件中访问 mixin 数据，使用 `this.xxx`

### 5. 样式相关

| Vue2 | Vue3 |
|------|------|
| `/deep/` | `::v-deep` 或 `:deep()` |

> ⚠️ **超长字符串处理**：CSS 中的 base64 编码图片、大型 data URI 等超长行，在转写过程中**容易出现字符截断或格式错误**。转换后必须验证文件完整性，特别是包含 `data:image/` 或 `background-image` 的样式。

### 6. 状态管理

| Vue2 | Vue3 |
|------|------|
| Vuex | Pinia |
| `new Vuex.Store()` | `createPinia()` |
| `$store` | `useStore()` |

> 📝 Pinia 迁移三步闭环：
> 1. 创建 store (`store/index.js`)
> 2. 在 `main.js` 中 `app.use(pinia)`
> 3. 组件中 `import { useStore } from '@/store'`

> 📝 App.vue 升级要点：
> 1. 使用 `<script setup>` 语法（简化写法）
> 2. 全局变量改用 Pinia 或独立的 globalState.js
> 3. 应用级生命周期使用 `onLaunch`（非 `onMounted`）

### 7. API 语法

| Vue2 | Vue3 |
|------|------|
| `require/module.exports` | `import/export` |
| `Vue.observable` | `reactive/ref` |
| `this.$listeners` | `this.$attrs` |

> ⚠️ **复杂 JS 文件转写警告**：涉及复杂闭包、动态 require、循环依赖等逻辑时，CommonJS → ES Module 转写**容易出现错误**。建议：先人工 review，再决定是否自动转换。

### 8. uni.request 调用方式

uni.request 支持两种调用方式，Vue2 和 Vue3 都完全支持：

#### 回调函数方式

```javascript
uni.request({
  url: 'https://api.example.com',
  success: (res) => {
    if (res.statusCode === 200) {
      console.log(res.data)
    }
  },
  fail: (err) => {
    console.error(err)
  }
})
```

#### Promise 方式（推荐）

```javascript
// async/await
const res = await uni.request({
  url: 'https://api.example.com'
})
if (res.statusCode === 200) {
  console.log(res.data)
}

// 或 Promise 链式
uni.request({
  url: 'https://api.example.com'
}).then(res => {
  console.log(res.data)
}).catch(err => {
  console.error(err)
})
```

> ⚠️ 注意：`let [error, res] = await uni.request()` 这种写法**不是 uni-app 的标准用法**，那是 axios 的风格。

### 9. 第三方库

- 检查 `uni_modules` 兼容性
- 检查 `vue.config.js` 配置
- Vue2 语法的第三方组件**标记为无需修复**，不要随意修改

---

## 微信小程序自动化测试

### 环境准备检查清单

执行自动化测试前，必须确认以下环境配置：

| 检查项 | 说明 | 验证方法 |
|--------|------|---------|
| **微信开发者工具开启自动化** | 工具设置 → 安全设置 → 开启"允许自动化" | 工具界面操作 |
| **不校验合法域名** | 开发阶段必须关闭，否则请求会失败 | 工具界面操作 |
| **自动化端口已开启** | 通过 `cli auto` 命令开启 | `netstat -ano \| findstr 9421` |
| **编译输出目录存在** | 必须用 `dev/mp-weixin` 目录 | 检查 `unpackage/dist/dev/mp-weixin` |

> ⚠️ **常见失败原因**：
> - 自动化端口未开 → 报错 `WebSocket connection failed`
> - 未开启自动化权限 → 报错 `automation disabled`
> - 用错编译目录 → 自动化打开的是旧版本

### 关键概念区分

| 概念 | 说明 |
|------|------|
| **23459 端口** | 微信开发者工具 HTTP 服务端口（IDE 管理界面用），**不能**用于自动化 |
| **9420/9421 端口** | 自动化专用 WebSocket 端口，需要通过 `cli auto` 命令开启 |
| **uni-app 源码目录** | 微信开发者工具**无法直接打开** |
| **编译输出目录** | `unpackage/dist/` 下有两个子目录 |

### 编译输出目录

```
unpackage/dist/
├── dev/mp-weixin     # 开发/运行模式（HBuilderX 运行），自动化测试建议用这个
└── build/mp-weixin   # 发行/打包模式（HBuilderX 发行）
```

### 完整操作流程

#### 1. 编译 uni-app

```bash
# HBuilderX：运行 → 运行到小程序模拟器 → 微信开发者工具
# 或 CLI：
npm run dev:mp-weixin
```

#### 2. 开启自动化端口

```powershell
# Windows PowerShell
& "C:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat" auto --project "编译输出目录" --auto-port 9421
```

#### 3. 验证端口

```powershell
netstat -ano | findstr "9421"
# 看到 LISTENING 即可
```

#### 4. 执行测试脚本

```powershell
$env:PYTHONIOENCODING="utf-8"; python auto_test.py
```

### WXML 选择器语法

自动化测试的核心是定位元素，微信小程序使用类似 CSS 的选择器：

| 选择器 | 示例 | 说明 | 推荐度 |
|--------|------|------|--------|
| class | `.category-item` | 推荐使用，最稳定 | ⭐⭐⭐⭐⭐ |
| id | `#my-input` | 注意小程序 id 会加前缀 `id-` | ⭐⭐⭐ |
| tag | `view` | 不推荐，泛用性太强 | ⭐ |
| 层级 | `view .btn` | 后代选择器 | ⭐⭐⭐⭐ |
| 属性 | `view[disabled]` | 属性选择器 | ⭐⭐⭐ |

```python
# 推荐写法
runner.click(".confirm-btn")        # class 选择器
runner.input("input[name='phone']", "13800138000")

# 不推荐写法
runner.click("view")                 # 太泛
runner.click("#the-id")              # id 可能有前缀
```

> 💡 **调试技巧**：如果 selector 找不到元素，先用 `.screenshot()` 截一张图，确认页面渲染正确，再用开发者工具检查 WXML 结构。

### WeappTestRunner API（实测有效）

```python
import sys
sys.path.insert(0, r"path_to_weapp-automation_skill\scripts")
from weapp_automation import AutomationConfig, WeappTestRunner

config = AutomationConfig(
    project_path=r"path_to_your_miniapp\unpackage\dist\dev\mp-weixin",
    ws_endpoint="ws://localhost:9421"
)

runner = WeappTestRunner(config)
results = (runner
    .navigate("pages/home/home")     # 导航到页面
    .wait(2)                          # 等待2秒
    .click(".category-item")          # 点击元素（不是 tap！）
    .input("input", "test text")      # 输入文本（参数名是 text 不是 value！）
    .screenshot("result.png")         # 截图
    .get_results())                   # 执行并返回结果
```

| 方法 | 说明 | 注意事项 |
|------|------|---------|
| `.navigate(path)` | 导航到页面 | 路径不带 `/` 前缀 |
| `.click(selector)` | 点击元素 | ⚠️ 不是 `tap` |
| `.input(selector, text)` | 输入文本 | ⚠️ 参数名是 `text` 不是 `value` |
| `.wait(seconds)` | 等待 | |
| `.screenshot(filename)` | 截图 | |
| `.get_results()` | 执行并返回结果 | 返回结构 `{"result": {"success": ..., "message": ...}}` |

### 常见报错处理

| 报错 | 原因 | 解决方案 |
|------|------|---------|
| `'WeappTestRunner' object has no attribute 'tap'` | 方法名错误 | 改为 `click` |
| `WebSocket connection failed` | 自动化端口未开 | 检查 `cli auto` 命令是否执行成功 |
| `input failed: unknown error` | selector 找不到元素 | 检查页面实际 WXML 结构 |
| `check if target project window is opened with automation enabled` | 项目未以自动化模式打开 | 重新执行 `cli auto` 流程 |

### 测试脚本模板

```python
import sys
sys.path.insert(0, r"path_to_weapp-automation_skill\scripts")
from weapp_automation import AutomationConfig, WeappTestRunner

# 页面配置列表
PAGES = [
    ("01", "pages/home/home", "首页"),
    # 添加更多页面...
]

def no_interaction(runner):
    """无需交互的页面"""
    return runner

def interact_page(runner):
    """交互测试"""
    return runner.input("input", "1 2 3 4").wait(1)

# 执行测试
config = AutomationConfig(
    project_path=r"your_project_path\unpackage\dist\dev\mp-weixin",
    ws_endpoint="ws://localhost:9421"
)

for num, path, desc, interact_fn in PAGES:
    runner = WeappTestRunner(config)
    chain = runner.navigate(path).wait(2)
    chain = interact_fn(chain)
    result = chain.screenshot(f"{num}_{path.replace('/', '_')}.png").get_results()
    status = "✅" if result.get("result", {}).get("success") else "❌"
    print(f"{status} {desc} 测试完成")

# 测试结果汇总
print("\n========== 测试汇总 ==========")
print(f"总页面数: {len(PAGES)}")
print(f"成功: {sum(1 for r in results if r['status']=='success')}")
print(f"失败: {sum(1 for r in results if r['status']=='failed')}")
```

### 测试结果汇总（进阶）

```python
import json
from datetime import datetime

results = []
start_time = datetime.now()

for num, path, desc, interact_fn in PAGES:
    try:
        runner = WeappTestRunner(config)
        chain = runner.navigate(path).wait(2)
        chain = interact_fn(chain)
        screenshot = f"sresults/{num}_{path.replace('/', '_')}.png"
        result = chain.screenshot(screenshot).get_results()

        success = result.get("result", {}).get("success", False)
        results.append({
            "num": num,
            "path": path,
            "desc": desc,
            "status": "success" if success else "failed",
            "message": result.get("result", {}).get("message", ""),
            "screenshot": screenshot
        })
        print(f"{'✅' if success else '❌'} {desc}")
    except Exception as e:
        results.append({
            "num": num,
            "path": path,
            "desc": desc,
            "status": "error",
            "message": str(e),
            "screenshot": None
        })
        print(f"❌ {desc} - 异常: {e}")

# 输出报告
end_time = datetime.now()
duration = (end_time - start_time).total_seconds()
report = {
    "summary": {
        "total": len(PAGES),
        "success": sum(1 for r in results if r["status"] == "success"),
        "failed": sum(1 for r in results if r["status"] in ("failed", "error")),
        "duration": f"{duration:.1f}s"
    },
    "results": results
}

with open("test_report.json", "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)

print(f"\n📊 测试完成，耗时 {duration:.1f}s")
print(f"📄 报告已保存: test_report.json")
```

---

## 项目结构参考

```
uni-app-project/
├── pages/                      # 页面目录
├── components/                  # 公共组件
├── static/                     # 静态资源
├── utils/                      # 工具函数（CommonJS → ES Module）
├── store/                      # Pinia store（Vue3）
│   ├── index.js               # Pinia 实例
│   └── modules/               # Store 模块
├── common/js/                 # 公共 JS
├── uni_modules/               # 第三方模块（Vue2 语法保持不变）
├── App.vue                    # 应用实例
├── main.js                    # 应用入口
├── pages.json                # 页面路由配置
├── manifest.json             # 应用配置
└── vue.config.js            # Vue 配置
```

---

## 经验教训

### 1. 文档处理流程

收到迁移类文档后，应该：
1. 提取【易错点】和【注意事项】
2. 转化为【检查清单】
3. 执行任务时同步检查，而不是"读完再检查"

### 2. 引用关系检查

- 创建文件后必须验证是否被引用
- 引用的文件是否存在
- 同名 `.min.js` 和 `.js` 文件要区分清楚

### 3. 问题排查顺序

出现问题时，优先：
1. 查看相关文档/日志
2. 检查文件引用关系
3. 最后才凭经验猜测

---

## References

本技能依赖或参考以下资源：

### 官方文档

| 资源 | 链接 | 说明 |
|------|------|------|
| uni-app 官方文档 | https://uniapp.dcloud.io/ | 核心参考文档 |
| Vue3 官方文档 | https://vuejs.org/ | Composition API 语法参考 |
| 微信开发者工具 | https://developers.weixin.qq.com/miniprogram/dev/devtools/cli.html | CLI 自动化接口 |

### 内嵌技能

> ⭐ **技能已内嵌**：以下技能已复制到 `skills/` 子目录，发布时无需额外安装。

| 技能 | 路径 | 说明 |
|------|------|------|
| Vue | `skills/vue/` | Vue 通用开发知识，可补充 Vue 基础 |
| Vue Expert | `skills/vue-expert/` | Vue3 进阶用法，TypeScript 集成 |
| weapp-automated-testing | `skills/weapp-automated-testing/` | 微信小程序测试，基础 API |

### 学习资源

| 资源 | 类型 | 推荐理由 |
|------|------|---------|
| Vue3 Composition API 入门 | 文章 | 快速上手 Composition API |
| Pinia 官方文档 | 文档 | 状态管理最佳实践 |
| uni-app 迁移指南 | 官方文档 | 官方迁移方案参考 |

---

**版本**：v1.0.0（2026-04-06）
**更新内容**：
- 增加选项式 API 兼容性说明
- 增加复杂 JS 转写警告
- 增加超长字符串处理警告
- 增加 App.vue 升级要点
- 增加 Mixins 处理建议（对比 Composables）
- 增加自动化测试环境准备检查清单
- 增加 WXML 选择器语法说明
- 增加测试结果汇总脚本（进阶版）
- 增加 Vue3 开发指南（Composition API 最佳实践）
- 增加 References（官方文档 + 内嵌技能 + 学习资源）
- **内嵌相关技能**：Vue、Vue Expert、weapp-automated-testing 已复制到 `skills/` 子目录
**标签**：uni-app, Vue3, 迁移, 微信小程序, 自动化测试
