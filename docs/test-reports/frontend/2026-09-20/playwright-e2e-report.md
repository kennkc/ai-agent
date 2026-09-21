# 前端 Playwright E2E 冒烟报告

> **日期**：2026-09-20
> **框架**：Playwright ^1.63.0 + Chromium
> **命令**：`npm run test:e2e`
> **数据源**：`VITE_DATA_SOURCE=mock`（由 `cross-env` 强制注入，E2E 不依赖后端）

## 1. 结论

- 浏览器项目：Chromium
- Mock UI 用例：**2 passed / 0 failed**
- 真实 BFF/API 用例：**1 passed / 0 failed**
- 失败重试：本地 0 次，CI 1 次
- 失败产物：trace、截图、视频保留

## 2. 覆盖用例

| 用例 | 覆盖内容 |
|---|---|
| `overview loads and theme switches between dark and light` | 总览加载、默认深色、浅色切换、深色切换 |
| `core observability and model pages are reachable from navigation` | 后台服务控制台、模型接入配置页导航与标题渲染 |
| `live wp-bff overview and service catalog stay reachable` | 真实 wp-bff、API 数据源、服务目录与 WP BFF 项 |

## 3. 配置

- `web/work-platform/playwright.config.ts`
- `web/work-platform/playwright.live.config.ts`
- `web/work-platform/e2e/platform-smoke.spec.ts`
- `.env.test` 固定 Mock 数据源。
- CI 执行 `npx playwright install --with-deps chromium` 后运行 `npm run test:e2e`。

## 4. 边界

当前包含 Mock UI 冒烟与真实 wp-bff/API 冒烟，但仍不覆盖完整 Docker 全栈。后续应扩展：

- 服务启停；
- 协作域选择；
- 知识入库与检索；
- 大脑问答；
- 真实 API 降级；
- 主题与偏好持久化。
