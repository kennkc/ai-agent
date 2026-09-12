# Phase 2 演示脚本：感官期——五感采集与数据质检 👁️✋

> **演示目标**：证明生命体"五感"已接通——渠道可插拔、R0/R1 双模式采集、五字段标准化、质检拦截、隔离暂存与回滚、意图识别双级级联全部可用，并可在 Console 感官视图实时观测。
> **前置条件**：Docker、JDK 21、Maven 3.9+、Python 3.12+、Node（仅前端语法校验用）
> **对应需求**：R2-01 ~ R2-10（Phase2-感官期/需求设计文档.md）

---

## 一、自动化验证（无需 Docker，先跑这一层）

### 1.1 Java 全量构建与单测

```bash
cd services/java
../../scripts/mvn-dev.sh clean package
```

预期（2026-09-12 实测）：

```
[INFO] proto-contracts .................................... SUCCESS
[INFO] gateway-service .................................... SUCCESS
[INFO] session-manager .................................... SUCCESS
[INFO] sense-service ...................................... SUCCESS
[INFO] body-service ....................................... SUCCESS
[INFO] BUILD SUCCESS
```

| 模块 | 测试数 | 失败 | 错误 | 测试类 |
|------|-------:|-----:|-----:|-------:|
| gateway-service | 2 | 0 | 0 | 1 |
| session-manager | 4 | 0 | 0 | 2 |
| **sense-service** | **47** | **0** | **0** | **7** |
| body-service | 1 | 0 | 0 | 1 |
| **合计** | **54** | **0** | **0** | **11** |

sense-service 的 7 个测试类覆盖（含新增 IPv6 unique-local / CGNAT SSRF 回归）：`TouchChannelTest`、`CollectPipelineTest`、`ChannelHealthMonitorTest`、`DataNormalizerTest`、`QualityGateTest`、`StagingStoreTest`、`TextExtractorTest`。

### 1.2 Python 意图识别与 OCR

```bash
cd services/python/nlp-service
../venv/Scripts/python.exe tests/test_intent.py     # Windows
# 或 ../venv/bin/python tests/test_intent.py        # Linux/macOS
../venv/Scripts/python.exe tests/test_ocr.py
```

预期（2026-09-12 实测，19 项全通过）：

```
  PASS test_rule_engine_covers_at_least_ten_scenarios
  PASS test_rule_engine_hits_high_confidence
  PASS test_cascade_routes_to_rule_first
  PASS test_cascade_falls_back_to_l0_model
  PASS test_l0_accuracy_on_holdout_corpus
  PASS test_l0_latency_below_50ms
  ...（共 15 项）

L0 留出集准确率: 90.0% (样本 60) | P99 延迟 0.052ms
  知识问答 80% · 计算器 100% · 天气查询 80% · 汇总报告 100% · 任务创建 100%
  日程提醒  60% · 数据查询  80% · 情感闲聊 100% · 故障排查 100% · 文档写作 80%
  情报采集 100% · 代码助手 100%

OCR 引擎状态: {'available': False, 'engine': 'unavailable',
              'candidates': [{'engine':'paddleocr','available':False},
                             {'engine':'tesseract','available':False}]}
```

> **R2-08 达标判定**：准确率 90.0% ≥ 85% ✅；P99 延迟 0.052ms < 50ms ✅。
> **R2-03 说明**：本机未安装 PaddleOCR/Tesseract 时，OCR 接口诚实上报 `available=false`，不伪造识别结果；视觉渠道在 Console 显示为 `DEGRADED`。安装引擎后自动转为可用。

### 1.3 Console 前端语法校验

```bash
node --check web/console/js/provider.js
# 预期: 无输出（语法通过）
```

---

### 1.4 Phase2 审核修复

- OCR 引擎改为惰性初始化，避免 `app.ocr` 导入时加载 PaddleOCR 导致测试/服务启动阻塞。
- URL 采集关闭 HttpClient 自动重定向，逐跳重新执行 SSRF 校验，并禁止 HTTPS → HTTP 降级。
- SSRF 防护新增 IPv6 unique-local（fc00::/7）和 IPv4 CGNAT（100.64.0.0/10）拦截。
- MinIO 默认凭据移除；Docker Compose 和 sense-service 改为从环境变量读取，缺少凭据时自动降级到本地暂存。
- 新增 2 项安全回归测试后，Java 测试总数由 52 增至 54，全部通过。
## 二、端到端演示（需 Docker 基础设施）

### 2.0 启动

```bash
./scripts/start.sh all
./scripts/healthcheck.sh          # 期望 7/7 通过，gRPC 端口 9091-9094 开放
```

sense-service 关键配置（`services/java/sense-service/src/main/resources/application.yml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `SENSE_FILE_ROOT` / `sense.file-root` | `data/sense-inbox` | 触觉渠道本地文件根目录（沙箱边界） |
| `SENSE_ALLOWED_HOSTS` | 空 | URL 采集白名单（空=仅公网，禁内网） |
| `sense.retry.max-attempts` | 3 | 指数退避重试上限 |
| `sense.quality.threshold` | 0.6 | 质检门槛 |
| `STAGING_BACKEND` | `auto` | auto→MinIO 优先，不可用回落本地 |
| `R0_ENABLED` / `R0_TICK_MS` | true / 60000 | R0 定时调度开关与轮询周期 |

### 2.1 R2-02 触觉渠道：三类数据源采集

```bash
TOKEN=$(curl -s -X POST "http://127.0.0.1:8080/api/auth/token?tenantId=default" | python -c "import sys,json;print(json.load(sys.stdin)['token'])")

# ① URL 采集（HTML 自动清洗取正文）
curl -s -X POST "http://127.0.0.1:8080/api/sense/collect" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"TOUCH","data_source":"https://example.com","query":"示例页面"}'

# ② 本地文件采集（相对 sense.file-root）
echo "这是一段用于触觉渠道文件采集验收的测试正文内容。" > data/sense-inbox/page.txt
curl -s -X POST "http://127.0.0.1:8080/api/sense/collect" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"TOUCH","data_source":"file://page.txt"}'

# ③ 纯文本直采
curl -s -X POST "http://127.0.0.1:8080/api/sense/collect" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"TOUCH","data_source":"Agent-Lifeform 感官期纯文本采集验收样例内容。"}'
```

预期响应关键字段：

```json
{
  "batch_id": "b-xxxx",
  "source_channel": "TOUCH",
  "tenant_id": "default",
  "staging_status": "ACCEPTED",
  "quality_score": 0.98,
  "item_count": 1,
  "attempts": 1,
  "degraded": false,
  "five_fields_complete": true
}
```

> **R2-01 / R2-02 / R2-04 达标**：`five_fields_complete=true` 即证明 `source_channel / tenant_id / timestamp / freshness / confidence` 五字段打标完整。

### 2.2 R2-10 质检拦截与批次回滚

```bash
# 低质数据（过短 + 重复乱码）应被拦截
curl -s -X POST "http://127.0.0.1:8080/api/sense/collect" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default" \
  -H 'Content-Type: application/json' \
  -d '{"channel":"TOUCH","data_source":"aaaa"}'
# 预期: "staging_status":"REJECTED","reject_reason":"BELOW_QUALITY_THRESHOLD"

# 查看暂存批次
curl -s "http://127.0.0.1:8080/api/sense/batches?status=REJECTED&limit=5" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default"
# 预期: {"tenant_id":"default","backend":"minio","count":N,"batches":[...]}

# 批次回滚
curl -s -X POST "http://127.0.0.1:8080/api/sense/batches/<BATCH_ID>/rollback" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default"
# 预期: {"batch_id":"<BATCH_ID>","rolled_back":true,"backend":"minio"}
```

拒绝原因枚举（`QualityGate.rejectReason`，单一事实来源）：

| reject_reason | 触发条件 |
|---------------|----------|
| `EMPTY_CONTENT` | 内容为空或全空白 |
| `ENCODING_GARBLED` | 可打印字符占比 < 0.8 |
| `REPETITIVE_GARBAGE` | 重复乱码特征（如 `aaaa`、周期性片段） |
| `BELOW_QUALITY_THRESHOLD` | 长度 < 20 或加权质量分 < 0.6 |

> 加权公式：`score = 0.45·内容分 + 0.20·长度分 + 0.20·可打印分 + 0.15·信息密度分`

### 2.3 R2-05 R0 定时调度 / R2-06 R1 动态采集

```bash
# R0：查看规则与调度日志
curl -s "http://127.0.0.1:8080/api/sense/rules" -H "Authorization: Bearer $TOKEN"
# 预期: {"stats":{"enabled":true,"tick_ms":60000,"rules":2,"triggered":N},"rules":[...],"log":[...]}

# R0：手动触发某条规则（演示用，不必等 60s）
curl -s -X POST "http://127.0.0.1:8080/api/sense/rules/r0-touch-knowledge/run" \
  -H "Authorization: Bearer $TOKEN"

# R1：多渠道路由指令（单轮渠道上限 3）
curl -s -X POST "http://127.0.0.1:8080/api/sense/command" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: default" \
  -H 'Content-Type: application/json' \
  -d '{"command_id":"cmd-demo-1","channels":["TOUCH","VISUAL","NOSE"],"data_source":"感官期 R1 动态采集验收文本内容样例。","query":"动态采集"}'
# 预期: 返回各渠道逐项结果 + 汇总（命中渠道数 ≤ 3；DOWN 渠道被跳过并计入 skipped）
```

默认 R0 规则（`r0-touch-knowledge` 每 300s、`r0-taste-quality` 每 600s）见 `application.yml`。

### 2.4 R2-09 渠道健康与隔离

```bash
# 五渠道健康总览
curl -s "http://127.0.0.1:8080/api/sense/health" -H "Authorization: Bearer $TOKEN"
# 预期: {"service":"sense-service","status":"UP|DEGRADED","channels":[{"channel":"AUDIO","status":"DOWN"},...]}

# 隔离与恢复快照
curl -s "http://127.0.0.1:8080/api/sense/channels-health" -H "Authorization: Bearer $TOKEN"
# 预期: {"channels":{...},"recoveries":[...]}
```

降级判定阈值：连续失败 `degrade-after: 1` 次 → `DEGRADED`；`down-after: 3` 次 → `DOWN` 并自动隔离，采集请求降级到可用渠道；恢复后自动重连并记录到 `recoveries`。

### 2.5 R2-07 / R2-08 意图识别双级级联

```bash
curl -s -X POST "http://127.0.0.1:8000/api/nlp/intent" \
  -H 'Content-Type: application/json' \
  -d '{"text":"帮我汇总本周科创板 50 的行情数据"}'
# 预期: {"intent":"汇总报告","confidence":0.9,"engine":"rule"}

curl -s -X POST "http://127.0.0.1:8000/api/nlp/intent" \
  -H 'Content-Type: application/json' \
  -d '{"text":"嗯那个啥来着"}'
# 预期: {"intent":"情感闲聊","confidence":<低>,"engine":"l0"}  ← 规则未命中，L0 模型兜底

curl -s "http://127.0.0.1:8000/api/nlp/intent/stats"
# 预期: 引擎画像 + 各引擎命中计数
```

级联顺序：**规则引擎优先（高置信直出）→ 未命中转 L0 模型兜底 → 仍不可判则归 `情感闲聊`**。

### 2.6 Console 感官视图（R-C02 / R-C09）

```bash
# Mock 模式（默认，无需后端）
start web/console/index.html

# API 模式（接真实 sense-service，需网关已启动）
start "web/console/index.html?ds=api&tenant=default"
```

预期：五感矩阵 5 张卡片（视/听/触/嗅/味）显示状态徽标、采集批次、质检通过率、速率、最近记录；点击卡片下钻渠道详情（最近批次表含质量分/尝试次数/降级/拒绝原因）；切换 `?ds=api` 与 `?ds=mock` **零 UI 改动**，仅数据源注脚变化。

| 数据源 | 行为 |
|--------|------|
| `?ds=mock` | 使用 `provider.js` 内置静态测试数据 |
| `?ds=api` | 经网关 `http://127.0.0.1:8080` 访问 `/api/sense/channels`、`/api/sense/stats`、`/api/sense/channels/{type}`；携带 `Authorization` + `X-Tenant-Id` |
| API 失败 | 视图显示可操作排查提示（网关未启 / `DEV_TOKEN_ENDPOINT_ENABLED` / 回退 mock），不静默失败 |

---

## 三、量化验收（DoD 对齐）

| 需求 | 验收标准 | 实测 | 结论 |
|------|----------|------|------|
| R2-01 | 渠道可插拔，新增渠道不改核心代码 | `ChannelRegistry` 动态发现 `SenseChannel` Bean，五渠道零改核心 | ✅ |
| R2-02 | URL/文件/文本三类源可采集并标准化 | `TouchChannelTest` + 端到端三命令 | ✅ |
| R2-03 | 基础 OCR 返回文本 | 引擎缺失时诚实上报 `available=false`；安装后可识别 | ⚠️ 待装引擎 |
| R2-04 | 采集数据五字段完整 | `five_fields_complete=true` | ✅ |
| R2-05 | 定时任务按规则触发；调度日志完整 | `/api/sense/rules` 返回 `stats/rules/log` | ✅ |
| R2-06 | 指令→渠道选择→采集→回传 | `/api/sense/command` 多渠道路由 + 单轮上限 | ✅ |
| R2-07 | 10 个预置场景识别准确 | 12 场景规则库（测试断言 ≥ 10） | ✅ |
| R2-08 | 延迟 < 50ms；准确率 ≥ 85% | P99 **0.052ms**；留出集 **90.0%** | ✅ |
| R2-09 | 渠道宕机自动隔离；恢复自动重连 | `ChannelHealthMonitorTest` + `channels-health` | ✅ |
| R2-10 | 低质数据被拦截；批次可回滚 | `QualityGateTest` / `StagingStoreTest` + rollback 接口 | ✅ |

---

## 四、故障排查

| 现象 | 排查 |
|------|------|
| 五感卡片全 `DOWN` | 检查渠道探针依赖：NATS 是否可用、`SENSE_FILE_ROOT` 是否存在 |
| 视觉渠道恒 `DEGRADED` | 本机未装 OCR 引擎（PaddleOCR/Tesseract）→ 安装后自动转 UP |
| 暂存 `backend=local` 而非 `minio` | MinIO 未启动或密钥不符 → `docker compose logs minio`；`STAGING_BACKEND` 可强制 |
| 采集全部 `REJECTED` | 内容过短（< 20 字符）或门槛过高 → 调 `sense.quality.threshold` / `min-length` |
| Console API 模式报 token 失败 | 网关需 `DEV_TOKEN_ENDPOINT_ENABLED=true`，且仅允许 loopback 调用 |
| 构建卡在 protobuf 插件 | 已修复：生成源码入库 + 默认生命周期不再调 protoc；契约变更时用 `-Pproto-gen` 重新生成 |

---

## 五、演示完成标准

- [x] Java 全量 `clean package` 通过，54 测试 0 失败
- [x] Python 意图识别 15 项 + OCR 4 项全通过
- [x] 意图识别准确率 ≥ 85%、P99 < 50ms（R2-08 硬指标）
- [x] 五渠道抽象落地，低质数据被质检拦截，批次可回滚
- [x] Console 感官视图 mock/api 双数据源零 UI 改动切换
- [ ] 端到端 Docker 运行时冒烟（需 Docker daemon 与 OCR 引擎就绪）

> 达标 → Phase 2 验收通过 → 进入 Phase 3（躯体期·知识入库与语义检索）
