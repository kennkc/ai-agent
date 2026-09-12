# Agent-Lifeform 工作平台前端

> 主前台框架：Vue 3 + Vite + TypeScript + Element Plus。

---

## 1. 定位

`web/work-platform` 是 Agent-Lifeform 的统一前台入口，承载：

- 生命体区：总览、生命体征、决策沙盘、五感矩阵、进化视图、协作总线
- 工作台区：任务中心、任务对话、专家团队、技能市场、连接器、自动化、灵感案例
- 治理区：免疫审批

当前阶段已完成 Vue 工程骨架、主要页面、路由、Pinia 状态、Mock/API 数据源结构和 Element Plus 布局。

---

## 2. 技术栈

| 能力 | 选型 |
|------|------|
| 框架 | Vue 3 |
| 构建 | Vite |
| 语言 | TypeScript |
| UI | Element Plus |
| 路由 | Vue Router |
| 状态 | Pinia |
| 请求 | Axios |
| 服务端状态 | TanStack Query for Vue（预留） |
| DAG | Vue Flow（预留） |
| 实时 | WebSocket / socket.io-client（预留） |
| 测试 | Vitest + Vue Test Utils + Playwright（规划） |

---

## 3. 目录结构

```text
web/work-platform/
├── index.html
├── package.json
├── package-lock.json
├── tsconfig.json
├── vite.config.ts
├── .env.example
└── src/
    ├── main.ts
    ├── App.vue
    ├── api/
    │   ├── mock.ts
    │   └── provider.ts
    ├── config/
    │   └── modules.ts
    ├── layouts/
    │   └── PlatformLayout.vue
    ├── router/
    │   └── index.ts
    ├── stores/
    │   └── app.ts
    ├── styles/
    │   └── index.css
    ├── types/
    │   └── index.ts
    └── views/
        ├── OverviewView.vue
        ├── TasksView.vue
        ├── ChatView.vue
        └── ModuleView.vue
```

---

## 4. 本地开发

```bash
cd web/work-platform
npm install
npm run dev
```

默认地址：

```text
http://127.0.0.1:3001
```

---

## 5. 环境变量

从 `.env.example` 创建本地环境文件：

```bash
VITE_DATA_SOURCE=mock
VITE_API_BASE=/api/wp
VITE_TENANT_ID=default
```

- `mock`：使用 `src/api/mock.ts`
- `api`：通过 `src/api/provider.ts` 请求 BFF
- `VITE_API_BASE`：BFF 基础路径
- `VITE_TENANT_ID`：默认租户

---

## 6. 构建与验证

```bash
npm run typecheck
npm run build
npm run preview
```

构建产物位于：

```text
web/work-platform/dist/
```

---

## 7. 前端更新流程

每次修改前端必须执行：

1. 更新模块定义：
   - `src/config/modules.ts`
   - `src/types/index.ts`
2. 更新路由：
   - `src/router/index.ts`
3. 更新布局或页面：
   - `src/layouts/PlatformLayout.vue`
   - `src/views/*.vue`
4. 更新数据层：
   - `src/api/mock.ts`
   - `src/api/provider.ts`
5. 保持 Mock 与 API 返回结构一致。
6. 执行类型检查和构建：

```bash
npm run typecheck
npm run build
```

7. 同步更新阶段文档和 HTML 镜像。
8. 更新根目录 `README.md` 与 `PROGRESS.md`。
9. 提交代码。

---

## 8. 提交前检查

```text
[ ] 模块定义已更新
[ ] 路由已更新
[ ] Mock/API 数据源结构一致
[ ] npm run typecheck 通过
[ ] npm run build 通过
[ ] 阶段文档已同步
[ ] README/PROGRESS 已同步
[ ] 未提交 node_modules 与 dist
```

---

## 9. 后续计划

- 接入 WorkPlatformDataProvider 的真实 BFF 数据
- 增加 Vue Flow 协作与 DAG 视图
- 增加 WebSocket 实时任务进度
- 增加 Vitest / Vue Test Utils 组件测试
- 使用 Element Plus 按需导入降低首屏包体
