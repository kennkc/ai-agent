# Agent-Lifeform Development Progress

> Last updated: 2026-09-12

## Current milestone

Phase 0, Phase 1 and Phase 2 development scope is implemented on branch `workbuddy/main`.
Phase 2 (感官期 / sensory stage, R2-01~R2-10) landed on 2026-09-12 with 52 Java tests green.

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

## Phase 2 (感官期 / Sensory Stage)

Requirement coverage R2-01 ~ R2-10. All source under `services/java/sense-service`,
`services/python/nlp-service` and `web/console`.

### Channels & collection

- [x] R2-01 `SenseChannel` pluggable abstraction (`Register/Collect/Healthy/Close`), `ChannelRegistry` auto-discovers Spring beans
- [x] R2-02 `TouchChannel`: URL fetch + local file read + raw text; HTML body extraction via jsoup; encoding detection
- [x] R2-03 `VisualChannel` + `NoseChannel` + `AudioChannel` + `TasteChannel` reserved channels with truthful `available()` reporting
- [x] R2-03 image OCR delegated to nlp-service `/api/nlp/ocr` (PaddleOCR / Tesseract), reports `available=false` when no engine installed
- [x] R2-04 `DataNormalizer`: five-field tagging (`source_channel/tenant_id/timestamp/freshness/confidence`)

### Scheduling, quality and staging

- [x] R2-05 `R0Scheduler`: Spring `@Scheduled` rule-driven polling with per-rule intervals, log and manual `runNow`
- [x] R2-06 `R1DynamicHandler` + `SenseCommand`: instruction -> channel routing -> collect -> response, per-round channel cap
- [x] R2-09 `ChannelHealthMonitor`: probe + DEGRADED/DOWN thresholds + auto-isolation + recovery tracking; exponential backoff retry (resilience4j) + `DeadLetterStore`
- [x] R2-10 `StagingStore` with MinIO/local dual backend, quality gate before staging, batch rollback

### Intent recognition

- [x] R2-07 `rules.py`: keyword/regex/scenario rule engine covering 12 scenarios (required >= 10)
- [x] R2-08 `l0_model.py`: lightweight L0 classifier; `cascade.py` rule-first -> L0 fallback cascade
- [x] Measured: holdout accuracy **90.0%** (target >= 85%), P99 latency **0.052 ms** (target < 50 ms)

### Console & observability

- [x] R-C02 Console sensory view: five-sense matrix, channel drill-down (recent batches with score/attempts/degraded/reject_reason)
- [x] R-C09 `ConsoleDataProvider` mock/api dual data source, zero UI change switch via `?ds=api` / `?ds=mock`
- [x] `SenseAdminController` exposes `/api/sense/channels`, `/channels/{type}`, `/channels-health`, `/stats`, `/batches`, `/batches/{id}/rollback`, `/rules`, `/deadletters`
- [x] Gateway route `/api/sense/**` -> `lb://sense-service`

### Build hardening

- [x] protobuf plugin deadlock fixed: generated Java sources (90 files, 6 proto) committed into `proto-contracts/src/main/java`; `protobuf-maven-plugin` removed from default lifecycle, kept behind the `proto-gen` profile
- [x] `scripts/mvn-dev.sh` wrapper (direct Maven launcher) works around broken `mvn.cmd` on this host

## Frontend

- [x] Vue 3 + Vite + TypeScript + Element Plus work-platform scaffold
- [x] Vue Router + Pinia + ConsoleDataProvider Mock/API switch
- [x] Global search, notification center and persisted preferences
- [x] Overview enhanced cockpit: life-colony concurrency topology, model call trend, model runtime topology and optimization suggestions
- [x] Overview vitals summary, service health and growth timeline drill-down
- [x] Tasks create/retry/archive/reorder and chat result workspace (artifacts/files/diff/preview)
- [x] Vitals realtime status, organ report, brain trace, senses drill-down and evolution trend
- [x] Multi-agent bus topology with mode cards, agent message rails, non-flickering MC-P stream, stacked artifacts and gates
- [x] Automation cron validation, case assembly and L3/L4 approval workflow displays
- [x] npm run typecheck passed
- [x] npm run build passed

## Verification completed

- `mvn clean package`: passed, 52 tests / 0 failures / 0 errors
  - gateway-service 2 | session-manager 4 | **sense-service 45** | body-service 1
- Python `tests/test_intent.py`: 15 passed (L0 holdout 90.0%, P99 0.052 ms)
- Python `tests/test_ocr.py`: 4 passed
- `node --check web/console/js/provider.js`: passed
- `contract-check.py`: 0 FAIL
- `work-platform` contract check: passed
- gRPC Java generation: 6 proto files, 90 generated Java sources
- Python proto generation: 12 generated files in services/python/nlp-service/generated
- Phase demo scripts: `docs/demo/Phase0-DEMO.md`, `docs/demo/Phase1-DEMO.md`, `docs/demo/Phase2-DEMO.md`
- Phase 2 stage reports archived in `docs/项目进度日志报告/` (stage report, execution log, test & acceptance report; each with a generated HTML twin)
- `scripts/md2html-report.py` renders those reports to self-contained HTML (requires anaconda Python, which has the `markdown` package)

## Remaining

- Full Docker/Jaeger runtime smoke test requires Docker daemon.
- OCR end-to-end acceptance requires installing PaddleOCR or Tesseract; until then the
  visual channel stays `DEGRADED` and the API truthfully reports `available=false`.
- Phase 3+ still pending (body-service knowledge ingest / semantic retrieval integration,
  Work Platform BFF/API mode).

