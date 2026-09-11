# Agent-Lifeform Development Progress

> Last updated: 2026-09-11

## Current milestone

Phase 0 and Phase 1 development scope is implemented on branch `codex/phase0-1-hardening`.

## Phase 0

- [x] Monorepo structure
- [x] Maven multi-module build
- [x] Python FastAPI skeleton
- [x] docker-compose infrastructure skeleton
- [x] Nacos registration configuration
- [x] gRPC health service on ports 9091-9094
- [x] Java and Python proto/gRPC generation
- [x] CI build/test/package skeleton
- [x] contract-check integrated into CI
- [x] startup and health-check scripts

## Phase 1

- [x] Gateway routes for session, sense, nlp and body services
- [x] JWT authentication with verified tenant propagation
- [x] BusProxy abstraction over NATS
- [x] NATS request/reply baseline
- [x] Kafka producer and consumer baseline
- [x] OpenTelemetry OTLP configuration
- [x] Jaeger service in docker-compose
- [x] uniform error response model and HTTP status mapping
- [x] cross-tenant access protection
- [x] SSRF guard for URL collection
- [x] VS1: session -> intent -> local retrieval -> template answer

## Verification completed

- `mvn test`: passed
- `mvn package -DskipTests`: passed
- `contract-check.py`: 0 FAIL
- `work-platform` contract check: passed
- gRPC Java generation: 6 proto files, 90 generated Java sources
- Python proto generation: 12 generated files in services/python/nlp-service/generated

## Remaining

- Full Docker/Jaeger runtime smoke test requires Docker daemon.
- Work Platform still uses Mock data; BFF/API mode is Phase 2+ work.
- Phase 2-8 still pending.


