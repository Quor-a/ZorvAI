---
name: frontend-spec
description: 企业级前端开发规范 Skill，包含命名、目录、CSS/JS/TS、Vue/React、Git、构建、性能、安全完整规范
---

# 前端开发规范 Skill（企业级）

本 Skill 提供**完整、可落地、团队统一**的前端开发规范，适用于中后台、移动端、Web 应用。

## 覆盖范围
- 项目结构与目录规范
- 文件命名规范
- HTML 编写规范
- CSS / SCSS 规范
- JavaScript / TypeScript 规范
- Vue / React 编码规范
- Git 提交规范
- ESLint / Prettier 规范
- 构建与性能优化规范
- 安全规范

## 使用方式
你可以直接问：
- 前端项目目录怎么设计？
- JS 变量命名有什么规范？
- Vue 组件编写最佳实践
- Git commit 信息怎么写？
- 如何配置 ESLint 规范？
- 前端性能优化有哪些规则？

---

## 1. 项目目录规范（通用）
src/ 
├── api/ # 接口请求 
├── assets/ # 静态资源 
├── components/ # 公共组件 
├── composables/ # 组合式函数 
├── constants/ # 常量 
├── hooks/ # 业务 hooks 
├── layouts/ # 布局 
├── pages/ # 页面 
├── router/ # 路由 
├── store/ # 状态管理 
├── styles/ # 全局样式 
├── utils/ # 工具函数 
└── App.vue/main.ts


---

## 2. 命名规范
### 文件命名
- 组件：大驼峰 `UserList.vue`
- 工具/页面：短横线 `user-info.ts`
- 样式：短横线 `index.scss`、`button.scss`

### 变量/函数
- 变量：小驼峰 `userList`
- 常量：全大写下划线 `MAX_COUNT`
- 布尔：is/has/should 开头 `isVisible`
- 函数：动词开头 `getUserInfo()`

---

## 3. HTML 规范
- 语义化标签：header/main/section/aside/footer
- 类名使用短横线：`class="user-card"`
- 禁止冗余标签、禁止行内样式
- 图片必须加 alt，懒加载使用 loading="lazy"

---

## 4. CSS / SCSS 规范
- 使用 BEM 或短横线命名
- 禁止嵌套超过 3 层
- 公共样式抽离到 variables/mixins
- z-index 统一管理
- 单位优先使用 rem / vh / %
- 颜色使用变量，禁止硬编码

---

## 5. JavaScript 规范
- 使用 const/let，禁止 var
- 优先使用箭头函数
- 异步优先 async/await
- 禁止嵌套过深（<3 层）
- 禁止魔法数字，抽为常量
- 数组优先使用 map/filter/reduce

---

## 6. TypeScript 规范
- 必须定义接口/类型
- 禁止 any，能用 unknown 替代
- 接口使用 I 前缀或后缀 Type
- 函数参数与返回值必须标注类型
- 类型复用优先使用 type / interface

---

## 7. Vue 规范
- 组件名多单词，避免与 HTML 重名
- props 必须定义类型、默认值、校验
- 指令缩写：: @ #
- 计算属性避免副作用
- watch 慎用，优先 computed
- 方法命名遵循动词+名词

---

## 8. React 规范
- 组件使用函数式 + Hooks
- 状态扁平化，避免嵌套
- 禁止在循环/条件中使用 Hooks
- 事件处理函数以 handle 开头
- 样式使用 CSS Modules / styled-components

---

## 9. Git 规范
提交格式：
type(scope): content

type：
- feat: 新功能
- fix: 修复
- docs: 文档
- style: 格式
- refactor: 重构
- perf: 性能
- test: 测试
- chore: 构建/工具

---

## 10. ESLint + Prettier 规范
- 2 空格缩进
- 单引号
- 语句末尾分号
- 文件末尾空行
- 禁止未使用变量
- 禁止 console（生产环境）

---

## 11. 性能规范
- 图片压缩、WebP
- 懒加载图片、组件
- 路由懒加载
- 防抖节流处理高频事件
- 大数据列表使用虚拟滚动
- 减少冗余渲染

---

## 12. 安全规范
- 防止 XSS：过滤用户输入
- 防止 CSRF：token 校验
- 敏感信息不放在 localStorage
- 接口必须做权限控制
- 禁止前端明文存储密钥/Token
