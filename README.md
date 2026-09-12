# Agent-Lifeform · AI Agent 生命体架构

> Phase 0/1/2. Java 21 + Python 3.12 + static prototypes. 52 Java tests + 19 Python tests green.

## Architecture overview

`用户/网关 -> session-manager -> nlp-service -> body-service -> 模板回答`

Phase 2 sensory loop:

`R0 定时 / R1 指令 → 五感渠道 → 标准化五字段 → 质检 → 隔离暂存(MinIO) → 事件(lifeform.sense.collected) → Console 感官视图`

## Project structure

- `proto/`: cross-language contracts (6 proto files; generated Java stubs committed under `proto-contracts/src/main/java`)
- `services/java/gateway-service`: routing, JWT, tenant propagation, gRPC health
- `services/java/session-manager`: Redis session state, NATS/Kafka adapter, VS1 ask flow
- `services/java/sense-service`: five-sense channels, R0/R1 collection, quality gate, staging, dead letter
- `services/java/body-service`: local retrieval MVP for VS1
- `services/python/nlp-service`: rule + L0 cascade intent recognition, OCR proxy
- `contracts/`: executable BFF OpenAPI copies used by `contract-check.py`
- `web/`: static prototypes (`console` contains the Phase 2 sensory view with mock/api data provider)
- `docs/demo/`: per-phase acceptance scripts (Phase0 / Phase1 / Phase2)

## Quick start

Linux/macOS:

```bash
./scripts/start.sh all
./scripts/healthcheck.sh
```

Windows:

```bat
scripts\start-dev.bat all
scripts\healthcheck.bat
```

The dev scripts set a local-only `JWT_SECRET` and enable the loopback-only token endpoint. Do not reuse the dev secret in production.

## Security model

- The dev token endpoint is disabled by default and loopback-only when enabled.
- All business API calls must carry a valid JWT.
- The gateway propagates `X-Tenant-Id` from verified JWT claims.
- Session/body/sense services must not trust tenant IDs from request parameters or request bodies.
- URL collection blocks loopback, private, link-local, multicast, and redirect targets by default.

## VS1 smoke test

```bash
curl -X POST "http://127.0.0.1:8080/api/auth/token?tenantId=default"
# copy token
curl -X POST http://127.0.0.1:8080/api/session -H "Authorization: Bearer <token>"
curl -X POST http://127.0.0.1:8080/api/body/ingest -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"doc_id":"doc-1","title":"架构说明","content":"Agent-Lifeform uses gateway session nlp body services","source":"demo"}'
curl -X POST http://127.0.0.1:8080/api/session/<session_id>/ask -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"question":"Agent-Lifeform 的架构是什么"}'
```

## Verification

```bash
cd services/java
../../scripts/mvn-dev.sh clean package     # 52 tests, 0 failures

cd services/python/nlp-service
../venv/Scripts/python.exe tests/test_intent.py   # 15 passed, L0 holdout 90.0%, P99 0.052 ms
../venv/Scripts/python.exe tests/test_ocr.py      # 4 passed

python scripts/contract-check.py
python scripts/contract-check.py --work-platform --openapi contracts/work-platform-bff-openapi.yaml
```

> `scripts/mvn-dev.sh` is a Maven wrapper that launches Maven directly through
> `plexus-classworlds`, used because `mvn.cmd` is broken on some Windows hosts.

## Status

- [x] Phase 0 skeleton and core security hardening
- [x] Phase 1 gateway/JWT/NATS/Kafka/NATS health baseline
- [x] Phase 2 sensory stage R2-01~R2-10 (channels, R0/R1 collection, quality gate, staging, intent cascade)
- [x] VS1: session -> intent -> local retrieval -> template answer
- [x] Java and Python proto/gRPC contract generation
- [x] Console sensory view with mock/api dual data source
- [ ] OCR end-to-end acceptance (requires installing PaddleOCR or Tesseract)
- [ ] Full Docker runtime smoke test
- [ ] Phase 3-8 features


