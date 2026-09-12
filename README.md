# Agent-Lifeform · AI Agent 生命体架构

> Phase 0/1 hardening + VS1 vertical slice. Java 21 + Python 3.12 + Vue 3 + Element Plus.

## Architecture overview

`用户/网关 -> session-manager -> nlp-service -> body-service -> 模板回答`

## Project structure

- `proto/`: cross-language contracts (proto files; generated stubs are not enabled yet)
- `services/java/gateway-service`: routing, JWT, tenant propagation, gRPC health
- `services/java/session-manager`: Redis session state, NATS/Kafka adapter, VS1 ask flow
- `services/java/sense-service`: touch channel, tenant-aware collection, SSRF guard
- `services/java/body-service`: local retrieval MVP for VS1
- `services/python/nlp-service`: rule-based intent MVP
- `contracts/`: executable BFF OpenAPI copies used by `contract-check.py`
- web/work-platform/: Vue 3 + Vite + TypeScript + Element Plus main workbench
- web/console/: legacy static Mock console

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
JAVA_HOME=<jdk21> mvn test
python scripts/contract-check.py
python scripts/contract-check.py --work-platform --openapi contracts/work-platform-bff-openapi.yaml
```

## Frontend verification

cd web/work-platform
npm install
npm run typecheck
npm run build

## Status

- [x] Phase 0 skeleton and core security hardening
- [x] Phase 1 gateway/JWT/NATS/Kafka/NATS health baseline
- [x] VS1: session -> intent -> local retrieval -> template answer
- [x] Java and Python proto/gRPC contract generation
- [x] Vue 3 + Element Plus frontend scaffold and production build
- [ ] Real BFF/UI API mode
- [ ] Phase 2-8 features



