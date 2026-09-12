# Agent-Lifeform 工作平台前端

> 主前台框架：Vue 3 + Vite + TypeScript + Element Plus。

---

## 1. 定位

`web/work-platform` 是 Agent-Lifeform 的统一前台入口，承载三区 16 模块：

- 生命体区：总览、生命体征、决策沙盘、五感矩阵、进化视图、协作总线
- 工作台区：任务中心、任务对话、专家团队、技能市场、连接器、自动化、多模型管理、远程 IM 遥控、灵感案例
- 治理区：免疫审批
- 全局层：Agent 在线侧栏、全局搜索、通知中心、偏好设置、生命体征迷你条、Mock/API 数据源切换

当前版本已完成设计文档 §2 的阶段性展示覆盖，并补齐全局元素、模块四态中的关键空态/失败态、任务创建与重试、五感下钻、决策归因、协作 DAG、自愈详情、专家 Schema、技能安装、连接器授权、自动化创建、案例装配和 L4 双人审批展示。

---

## 2. 设计覆盖状态

| 模块 | 当前展示能力 | 后续真实接入 |
|------|--------------|--------------|
| 总览 | 今日摘要、五体征摘要、四指标卡、模型调用趋势、模型运行状态、AI 优化建议、服务健康、成长时间轴 | overview 聚合 API、`wp.model.metrics`、`wp.optimization.suggestion` |
| 生命体征 | 实时连接状态、异常阈值、心跳动画、器官健康、PDF 导出入口 | vitals/organs API、WS `wp.vitals.update` |
| 决策沙盘 | 六步思考链、模型/耗时/置信度/IO、审计与归因来源 | IN3 AuditLog |
| 五感矩阵 | 五感官状态、采集中断原因、渠道详情与最近样本 | sense API |
| 进化视图 | 正负反馈、幻觉率、满意度、周趋势、自愈详情 | R-C07 + case-library |
| 协作总线 | 协作模式模块、生命群落并发拓扑、总线行、MC-P 消息流（无持续闪烁）、DAG、共享工件层、协作验收门 | MC bus + WS |
| 任务中心 | 状态筛选、创建、失败重试、归档、拖动排序、进入对话 | BFF tasks API |
| 任务对话 | 流式回复、引用来源、附件、产物/文件/变更/预览 | Session + Artifact API |
| 专家团队 | 状态筛选、人设、工具白名单、输出 Schema | ExpertProfile API |
| 技能市场 | 分类/搜索、安装状态、安全审核、权限展示 | Skill Registry |
| 连接器 | 健康状态、OAuth 授权、启停、延迟与调用量 | connector-gateway |
| 自动化 | Cron 校验、启停、失败重试、推送渠道 | scheduler |
| 多模型管理 | 模型池、路由策略、Token 趋势、成本/质量/健康度 | model registry + router |
| 远程 IM 遥控 | 微信/企业微信/飞书/钉钉/QQ 渠道、任务下发、执行和结果回传 | IM adapter + remote command API |
| 灵感案例 | Prompt/专家/技能装配流、复用计数 | case-library |
| 免疫审批 | L1-L4 队列、TTL、原因、参数/目标/影响、L4 双人复核 | Approval API + WS |

> Mock 与 API 共用 `dataProvider` 结构。切换 `VITE_DATA_SOURCE=api` 后，页面结构无需调整。

---

## 3. 技术栈

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
| DAG | Vue Flow（预留；当前使用轻量 SVG/CSS 展示） |
| 实时 | WebSocket / socket.io-client（预留） |
| 测试 | Vitest + Vue Test Utils + Playwright（规划） |

---

## 4. 目录结构

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
    ├── config/modules.ts
    ├── layouts/PlatformLayout.vue
    ├── router/index.ts
    ├── stores/app.ts
    ├── styles/index.css
    ├── types/index.ts
    └── views/
        ├── OverviewView.vue
        ├── TasksView.vue
        ├── ChatView.vue
        └── ModuleView.vue
```

---

## 5. 本地开发

```bash
cd web/work-platform
npm install
npm run dev
```

默认地址：`http://127.0.0.1:3001`

---

## 6. 环境变量

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

## 7. 构建与验证

```bash
npm run typecheck
npm run build
npm run preview
```

构建产物位于 `web/work-platform/dist/`。

---

## 8. 前端更新流程

每次修改前端必须执行：

1. 更新模块定义：`src/config/modules.ts`、`src/types/index.ts`。
2. 更新路由：`src/router/index.ts`。
3. 更新布局或页面：`src/layouts/PlatformLayout.vue`、`src/views/*.vue`。
4. 更新数据层：`src/api/mock.ts`、`src/api/provider.ts`。
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

## 9. 提交前检查

```text
[ ] 模块定义已更新
[ ] 路由已更新
[ ] 全局搜索/通知/偏好入口可用
[ ] Mock/API 数据源结构一致
[ ] Loading/Empty/Error/Edge 关键状态已覆盖
[ ] npm run typecheck 通过
[ ] npm run build 通过
[ ] 阶段文档已同步
[ ] README/PROGRESS 已同步
[ ] 未提交 node_modules 与 dist
```

---

## 10. 后续计划

- 接入真实 BFF 与 WS 事件，移除阶段性 Mock 数据。
- 将轻量 SVG DAG 替换为 Vue Flow，并接入消息超过 50 条的虚拟滚动。
- 增加 Vitest / Vue Test Utils 组件测试与 Playwright 视觉回归。
- 使用 Element Plus 按需导入降低首屏包体。
