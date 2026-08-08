# Phase 0 演示脚本：生命体骨架启动 🖥️

> **演示目标**：证明"生命体骨架"已立起来——基础设施 + 3 个 Java 服务 + 1 个 Python 服务全部健康。
> **前置条件**：Docker、JDK 21、Maven 3.9+、Python 3.12+

---

## 演示步骤

### 第 1 步：一键启动

```bash
cd agent-lifeform
./scripts/start.sh all
```

预期输出（节选）：
```
[1/4] 启动基础设施...
[2/4] 构建并启动 Java 服务...
[3/4] 启动 Python 服务...
[4/4] 健康检查...
  ✅ Redis: 端口 6379 开放
  ✅ PostgreSQL: 端口 5432 开放
  ✅ Qdrant: 端口 6333 开放
  ✅ gateway-service: HTTP 200
  ✅ session-manager: HTTP 200
  ✅ sense-service: HTTP 200
  ✅ nlp-service: HTTP 200
```

### 第 2 步：Nacos 注册验证

浏览器打开 `http://127.0.0.1:8848/nacos`（默认账号 nacos/nacos）
→ 服务管理 → 服务列表 → 应看到：
- `gateway-service`（健康实例 1）
- `session-manager`（健康实例 1）
- `sense-service`（健康实例 1）

### 第 3 步：功能冒烟（3 条命令）

```bash
# ① 创建会话（验证 session-manager + Redis）
curl -X POST http://127.0.0.1:8081/api/session
# 预期: {"session_id":"xxx","status":"ACTIVE"}

# ② 触发采集（验证 sense-service 触觉渠道）
curl -X POST http://127.0.0.1:8082/api/sense/collect \
  -H 'Content-Type: application/json' \
  -d '{"data_source":"https://example.com"}'
# 预期: {"batch_id":"xxx","source_channel":"TOUCH","accepted":true,"content_preview":"..."}

# ③ 意图识别（验证 nlp-service）
curl -X POST http://127.0.0.1:8000/api/nlp/intent \
  -H 'Content-Type: application/json' \
  -d '{"text":"今天天气怎么样"}'
# 预期: {"intent":"天气查询","confidence":0.8}
```

---

## 量化验收

| 指标 | 目标 | 实测 |
|------|------|------|
| 服务注册数（Nacos） | ≥ 3 | ___ |
| 健康检查通过数 | 7/7 | ___ |
| 创建会话成功率 | 100% | ___ |
| 采集接口成功率 | ≥ 90% | ___ |
| 意图识别响应 | < 500ms | ___ |

---

## 故障排查

| 现象 | 排查 |
|------|------|
| Nacos 启动慢 | 等待 30s 重试；`docker compose logs nacos` 查看 |
| Java 服务无法注册 | 确认 Nacos 8848/9848 端口开放；application.yml 的 NACOS_ADDR |
| Maven 依赖下载慢 | 配置阿里云镜像 `settings.xml` |
| Qdrant 无法启动 | 检查 6333/6334 端口冲突 |

---

## 演示完成标准

- [x] 4 类服务全部健康（7/7 检查通过）
- [x] 3 个 Java 服务注册到 Nacos
- [x] 3 条冒烟命令全部返回预期结果
- [x] 演示时间 < 10 分钟

> 达标 → Phase 0 验收通过 → 进入 Phase 1（神经期·数据总线）
