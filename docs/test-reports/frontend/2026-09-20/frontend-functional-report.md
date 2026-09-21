# 前端功能测试报告（MVP）

> **日期**：2026-09-20（2026-09-21 回归更新）
> **代码基线**：以 `docs/项目进度总览.md` 登记的最新 `codex/main` 为准
> **框架**：Vitest 3.2.7 + Vue Test Utils 2.5.1 + jsdom 26.1.0
> **命令**：`npm run test:report`

## 1. 结论

- 测试文件：**10 个**
- 测试用例：**27 passed / 0 failed**
- 语句覆盖率：**31.91%**（1789 / 5605）
- 分支覆盖率：**70.14%**（423 / 603）
- 函数覆盖率：**31.65%**（63 / 199）
- 行覆盖率：**31.91%**（1789 / 5605）
- 覆盖率门禁：行/语句 25% · 函数 20% · 分支 65% · `npm run test:report` 强制执行
- 类型检查：`npm run typecheck` 通过
- 生产构建：`npm run build` 通过（仅有 Element Plus 主包 >500 kB 的体积提示）

本报告是**前端 MVP 功能测试基线**，不是全量覆盖率验收。当前优先覆盖主题、路由、数据源降级、真实协作域选择、总览驾驶舱、中间件监控、模型配置和服务控制页；其余页面与真实 BFF/E2E 仍待后续补充。

## 2. 覆盖范围

| 测试文件 | 用例数 | 覆盖内容 |
|---|---:|---|
| `src/api/status.test.ts` | 3 | 降级记录去重、恢复清理、20 条上限 |
| `src/config/modules.test.ts` | 3 | 置顶入口、规划状态、分组合法性 |
| `src/api/provider.test.ts` | 5 | 真实协作域选择、Phase 6 空态、侧栏 Agent 在线、模型运行态、Prometheus 指标总览 |
| `src/stores/app.test.ts` | 3 | 深浅主题切换、偏好持久化、Mock/API 数据源切换 |
| `src/router/router.test.ts` | 3 | 总览、模型、中间件、协作页及动态模块路由 |
| `src/views/OverviewView.test.ts` | 2 | 今日摘要/模型监控/OPS 真实数据渲染、加载失败时最近快照与明确告警 |
| `src/views/MiddlewareView.test.ts` | 2 | 真实中间件卡片/健康摘要、观测服务不可用状态 |
| `src/views/ModelsView.test.ts` | 2 | 模型配置不可用态、OpenRouter 兼容配置渲染与角色装配显示 |
| `src/views/MetricsView.test.ts` | 2 | Prometheus / Micrometer 指标页面、不可读显式降级 |
| `src/views/ServicesView.test.ts` | 2 | 应用服务控制台真实状态、托管/外部区分、BFF 降级只读态 |

## 3. 测试发现并修复的问题

1. 组件测试暴露出 `MiddlewareView` 在 `enabled=false` 且无 `summary` 时会渲染崩溃；已改为安全读取 summary 并增加回归用例。
2. 真实协作域接线最初在 provider 测试中错误模拟了 collab-bus 原始结构，而 BFF 才是字段映射边界；已改为模拟 BFF 已映射契约，并断言 `/collab/domains` 与真实域详情均被调用。
3. `provider.ts` 的真实域列表解析曾产生隐式 `any`，已补显式类型，恢复类型检查与生产构建通过。
4. 左侧菜单从平铺入口改为分组折叠后，原 E2E 直接点击隐藏子项的路径失效；已把测试改为先展开所属分组，并锁定“常用入口 → 分组 → 子项”的新交互。
5. “多模型管理”原模型池运行态与路由策略为 Mock；现已改为 BFF 聚合真实配置、探测、Token 用量和 Prometheus 指标，成本/质量/配额/队列显式标记未接入。

## 4. 覆盖率摘要

| 文件/模块 | 行覆盖率 | 分支覆盖率 | 说明 |
|---|---:|---:|---|
| `src/api/status.ts` | 100% | 90% | 数据源降级状态 |
| `src/api/provider.ts` | 23.5% | 34.24% | 新增 Phase 6、Agent 在线、模型运行态与 Prometheus 指标读路径；其余 API 分支待扩测 |
| `src/config/modules.ts` | 100% | 100% | 菜单置顶、分组与成熟度元数据 |
| `src/stores/app.ts` | 94.8% | 35% | 主题、偏好、数据源 |
| `src/router/index.ts` | 100% | 100% | 路由表解析 |
| `src/views/OverviewView.vue` | 73.24% | 77.96% | 今日摘要、模型监控、观测摘要与失败快照 |
| `src/views/MiddlewareView.vue` | 53.25% | 94.23% | 中间件监控主路径 |
| `src/views/ModelsView.vue` | 70.87% | 67% | 模型配置、真实运行态、探测与用量展示、统一操作按钮结构 |
| `src/views/MetricsView.vue` | 96.75% | 75.3% | Prometheus target、HTTP、JVM、Hikari、业务指标与告警 |
| `src/views/ServicesView.vue` | 76.66% | 92.5% | 应用服务监控与启停主路径 |
| 其他视图 | 0% | — | 下一步分批补组件/E2E |

## 5. 报告归档

- 本报告：`docs/test-reports/frontend/2026-09-20/frontend-functional-report.md`
- HTML 版本：`docs/test-reports/frontend/2026-09-20/frontend-functional-report.html`
- JUnit：`docs/test-reports/frontend/2026-09-20/junit.xml`
- 覆盖率摘要：`docs/test-reports/frontend/2026-09-20/coverage-summary.json`
- 完整 HTML 覆盖率：构建产物 `web/work-platform/coverage/index.html`，建议由 CI artifact 保存，不提交 Git。

## 6. 后续优化建议

1. 继续补知识库、协作总线、远程 IM、Agent 在线等高交互页面的组件测试；
2. 增加 provider 的 Mock/API/超时/404/降级分支覆盖，重点验证“不伪造真实数据”的约束；
3. Playwright 已引入并覆盖总览、主题切换、后台服务与模型页导航；后续扩展登录、服务启停、协作域切换和真实 BFF 降级；
4. 已设置 25% 行/语句、20% 函数、65% 分支硬门禁，补齐核心页面后提高到 40%；
5. 为关键按钮和状态补充稳定的 `data-testid`；
6. 将 `npm run test:report` 纳入 CI 并上传 JUnit/覆盖率 artifact。
