# 前端功能测试报告（MVP）

> **日期**：2026-09-20  
> **代码基线**：`1461118` + 本轮前端测试框架  
> **框架**：Vitest 3.2.7 + Vue Test Utils 2.5.1 + jsdom 26.1.0  
> **命令**：`npm run test:report`

## 1. 结论

- 测试文件：**6 个**
- 测试用例：**15 passed / 0 failed**
- 语句覆盖率：**16.86%**（849 / 5035）
- 分支覆盖率：**78.1%**（214 / 274）
- 函数覆盖率：**40.24%**（33 / 82）
- 行覆盖率：**16.86%**（849 / 5035）

本报告是**前端 MVP 功能测试基线**，不是全量覆盖率验收。当前优先覆盖主题、路由、数据源降级、中间件监控和模型配置页；其余页面与真实 BFF/E2E 仍待后续补充。

## 2. 覆盖范围

| 测试文件 | 用例数 | 覆盖内容 |
|---|---:|---|
| `src/api/status.test.ts` | 3 | 降级记录去重、恢复清理、20 条上限 |
| `src/stores/app.test.ts` | 3 | 深浅主题切换、偏好持久化、Mock/API 数据源切换 |
| `src/router/router.test.ts` | 3 | 总览、模型、中间件、协作页及动态模块路由 |
| `src/views/MiddlewareView.test.ts` | 2 | 真实中间件卡片/健康摘要、观测服务不可用状态 |
| `src/views/ModelsView.test.ts` | 2 | 模型配置不可用态、OpenRouter 兼容配置渲染与角色装配显示 |
| `src/views/ServicesView.test.ts` | 2 | 应用服务控制台真实状态、托管/外部区分、BFF 降级只读态 |

## 3. 测试发现并修复的问题

组件测试暴露出 `MiddlewareView` 在以下响应下会渲染崩溃：

```json
{
  "enabled": false,
  "items": []
}
```

原因是 health summary 为空时直接读取 `overview.summary.down`。本轮已改为：

```ts
overview.value?.summary?.down ?? 0
```

并增加“中间件观测服务未启用”回归用例。

## 4. 覆盖率摘要

| 文件/模块 | 行覆盖率 | 分支覆盖率 | 说明 |
|---|---:|---:|---|
| `src/api/status.ts` | 100% | 100% | 数据源降级状态 |
| `src/stores/app.ts` | 94.8% | 38.88% | 主题、偏好、数据源 |
| `src/router/index.ts` | 100% | 100% | 路由表解析 |
| `src/views/MiddlewareView.vue` | 53.25% | 94.23% | 中间件监控主路径 |
| `src/views/ModelsView.vue` | 67.16% | 71.12% | 模型配置主路径 |
| `src/views/ServicesView.vue` | 76.66% | 92.5% | 应用服务监控与启停主路径 |
| `src/api/provider.ts` | 0% | 0% | 下一步补 API fallback/契约测试 |
| 其他视图 | 0% | — | 下一步分批补组件/E2E |

## 5. 报告归档

- 本报告：`docs/test-reports/frontend/2026-09-20/frontend-functional-report.md`
- JUnit：`docs/test-reports/frontend/2026-09-20/junit.xml`
- 覆盖率摘要：`docs/test-reports/frontend/2026-09-20/coverage-summary.json`
- 完整 HTML 覆盖率：构建产物 `web/work-platform/coverage/index.html`，建议由 CI artifact 保存，不提交 Git。

## 6. 后续优化建议

1. 增加 `provider.ts` 的 API/Mock/降级契约测试；
2. 给总览、知识库、协作总线、远程 IM、Agent 在线页面补组件测试；
3. 引入 Playwright，覆盖登录、路由、主题、服务启停和真实 BFF 降级；
4. 设置阶段性覆盖率门槛：先 25%，稳定后提高到 40%；
5. 为关键按钮和状态补充稳定的 `data-testid`；
6. 将 `npm run test:report` 纳入 GitHub Actions 并上传 JUnit/覆盖率 artifact。