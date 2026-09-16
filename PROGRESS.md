# Agent-Lifeform Development Progress

> Last updated: 2026-09-16

## Archived stage reports

Phase 0 / Phase 1 / Phase 2 stage reports (stage report + execution log + test &
acceptance report, each with an HTML twin) are archived in
`docs/项目进度日志报告/`. Phase 0/1 reports were added retroactively on
2026-09-15 (the three-doc convention was established at Phase 2).

## Current milestone

Phase 0, Phase 1 and Phase 2 development scope is implemented on `codex/phase0-1-hardening`
(the external `workbuddy/main` branch is merged in on a semantic basis, and `dev` is
fast-forwarded from the main line).
Phase 2 (感官期 / sensory stage, R2-01~R2-10) landed on 2026-09-12; after the D-1 cross-service
fix (2026-09-13) and the hardening pass below the suite stands at **61 Java tests green**.

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
- [x] Audit hardening: lazy OCR engine initialization, redirect-safe SSRF validation, MinIO credentials required from environment
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

- **D-1 fixed (2026-09-13)**: cross-service calls were failing because the JDK `HttpClient` default
  (HTTP/2) sends an h2c upgrade handshake that uvicorn/h11 rejects, which dropped the request body
  (FastAPI replied 422, Java surfaced 503). All outbound clients now go through a shared HTTP/1.1
  factory (`OutboundHttp` in session-manager, `HttpClients` in sense-service) with connect/read
  timeouts, graceful degradation (intent -> FALLBACK, retrieval -> empty) and a dedicated
  `AGENT_UPSTREAM_UNAVAILABLE` error code. Regression guards: `OutboundHttpTest`,
  `UpstreamDegradeTest`, `HttpClientsTest`.
  End-to-end re-verified: `POST /api/session/{id}/ask` -> 200 in 875 ms, Redis
  `session:{id}:messages` LLEN=2, and `ask` still returns 200 (FALLBACK) with nlp-service stopped.
- `mvn clean package`: passed, **61 tests / 0 failures / 0 errors**
  - gateway-service 2 | session-manager 10 | **sense-service 48** | body-service 1
  - (was 54 before the D-1 fix; +7 regression cases)
- Python `tests/test_intent.py`: 15 passed (L0 holdout 90.0%, P99 0.052 ms)
- Python `tests/test_ocr.py`: 4 passed
- `python -m pytest`: 19 passed
- `docker compose config`: passed with required MinIO environment variables
- OCR module import no longer initializes PaddleOCR at import time
- `node --check web/console/js/provider.js`: passed
- `contract-check.py`: 0 FAIL
- `work-platform` contract check: passed
- gRPC Java generation: 6 proto files, 90 generated Java sources
- Python proto generation: 12 generated files in services/python/nlp-service/generated
- Phase demo scripts: `docs/demo/Phase0-DEMO.md`, `docs/demo/Phase1-DEMO.md`, `docs/demo/Phase2-DEMO.md`
- Phase 2 stage reports archived in `docs/项目进度日志报告/` (stage report, execution log, test & acceptance report; each with a generated HTML twin)
- `scripts/md2html-report.py` renders those reports to self-contained HTML (managed Python + `markdown` installed via `pip install --target E:\AI\核心知识\.workbuddy\tmp\pylibs`; run with `PYTHONPATH` pointing there)

## 2026-09-15 Work Platform Observability Update (dev branch)

- New "观测区" group in work-platform: **MiddlewareView** + **TracingView**, backed by a minimal
  Ops BFF (`services/node/wp-bff`, zero-dependency Node, port 8090):
  - `GET /api/wp/middleware` — live TCP probes of all 8 middleware containers (state/latency/metrics)
  - `POST /api/wp/middleware/:key/start|stop` — real `docker compose up -d / stop` behind a strict
    8-key whitelist, fixed command shapes, in-memory ops tracking (starting/stopping) with watchdog,
    audit log at `logs/wp-bff-audit.log`. Unlisted keys -> 403.
  - `GET /api/wp/tracing` — real Jaeger data: registered services + per-service aggregation of the
    latest 20 traces (traces/spans/error rate/P99) + 12 most recent traces overall.
- Middleware page: per-card start/stop (down -> starting/stopping -> up, polled), plus **one-click
  serial start/stop** buttons — each middleware is confirmed healthy/stopped before moving to the
  next, with a progress banner. Full cycle verified on real containers: serial stop 8/8 down,
  serial start 8/8 up.
- Overview page: optimization suggestions now run a real execution loop (queued -> step progress +
  live log -> receipt) instead of flipping status; API contract `POST /suggestions/:id/apply` reserved.
- Dev environment: `.env` switched to `VITE_DATA_SOURCE=api`; vite dev server dual-stack listening
  (localhost / 127.0.0.1 / LAN IP all reachable, `host: true` + `allowedHosts: true` in config —
  note the dev script previously overrode config via `--host 0.0.0.0`).

## 2026-09-16 Engineering Hardening (optimization pass)

Scope: the P0/P1/P2 items from the progress review, **excluding Phase 3**.

### P0 · wp-bff control-plane hardening

- Control endpoints (`POST /api/wp/middleware/:key/start|stop`) now require **both** an origin
  check (Origin/Referer against `WP_BFF_ALLOWED_ORIGINS`) and a control token
  (`X-WP-Control-Token`, compared with `timingSafeEqual`). Missing/invalid token -> 401,
  foreign origin -> 403; both cases are audit-logged as `REJECT_AUTH`.
- Token resolution: `WP_BFF_CONTROL_TOKEN` env var, otherwise a random token generated at boot,
  written to `services/node/wp-bff/logs/wp-bff-control-token` (mode 0600) and printed once to
  stdout. There is **no** unauthenticated fallback mode.
- CORS: `Access-Control-Allow-Origin: *` removed; the header is echoed only for allow-listed
  origins and `Vary: Origin` is always set. Preflight from a foreign origin returns 403.
- New read-only `GET /api/wp/healthz` reports token source, origin allow-list and key inventory
  without leaking the token.
- `server.js` refactored into `createServer(options)` with injectable spawn/probe/fetch, so the
  control plane is testable. `services/node/wp-bff/test/server.test.js` adds **14 regression
  cases** (auth, CSRF, allow-list, fixed command shape, idempotency, CORS, tracing fallback,
  sampling metadata).
- Dev wiring: the Vite proxy reads the token file **server-side** and injects the header, so the
  token never reaches the browser bundle. The proxy deliberately does **not** rewrite `Origin`,
  keeping the CSRF check effective through the proxy.

### P0 · API mode no longer silently falls back to Mock

- New `web/work-platform/src/api/status.ts` tracks degradation per data scope (overview, vitals,
  organs, brain, senses, evolution, collaboration, experts, skills, connectors, automations,
  cases, approvals, models, remote_channels, online_agents, middleware, tracing).
- `provider.ts`'s `safe()` records a degradation entry on failure / clears it on success;
  `getMiddleware` and `getTracing` register degradation when the payload lacks `enabled`.
- UI: the header shows an `API · 降级 N` badge, a warning banner sits above the routed view with a
  drill-down dialog (scope + reason + time), and each module page shows its own degradation alert
  when its data domain is still served from Mock.

### P1 · Observability truthfulness

- Trace aggregation now returns `sample_size`, `sample_limit` and `p99_basis`
  (`sampled_recent_traces`); the tracing page states that P99/spans come from the most recent 20
  traces per service and are **not** full 24h metrics.
- Recent-trace ordering switched from local time-string comparison to the absolute
  `start_time_ms` value, removing a cross-midnight ordering bug.
- Middleware cards now expose the probe type (`TCP 端口可达性`) next to latency and port, so a
  green card is not mistaken for deep process health.

### P1 · CI coverage

- `.gitlab-ci.yml` gains `python-test` (pytest, 19 cases) and `wp-bff-test`
  (`node --check` + `node --test`, 14 cases); `java-test`, `frontend-build` (typecheck + build)
  and `contract-check` were already present.

### P2 · Frontend delivery

- All routes use dynamic `import()` now: overview, tasks, chat, the 13 module pages, middleware
  and tracing are separate chunks instead of one initial bundle.
- Added `@types/node` plus `"types": ["vite/client", "node"]` so `vite.config.ts` is type-checked.
- Build output: `element` vendor chunk 952 kB (still a full import — on-demand import is deferred
  until a visual regression baseline exists), `ModuleView` 51 kB, `vue` 111 kB.
- `ModuleView.vue` (~85 kB, 13 module branches) is still one component; splitting it into
  per-module panels is queued behind the same visual-regression prerequisite.

### Verification (2026-09-16)

- `services/node/wp-bff` -> `node --test`: **14 passed**
- `services/python/nlp-service` -> `python -m pytest`: **19 passed**
- `web/work-platform` -> `npm run typecheck` passed, `npm run build` passed
- Manual API-mode degradation check: header badge + banner + per-module alert render when the
  BFF does not implement a data domain

## Remaining

- ~~Full Docker/Jaeger runtime smoke test requires Docker daemon.~~ Done on 2026-09-13:
  8 infrastructure containers + 4 Java services + nlp-service + work-platform all running,
  `healthcheck.sh` 16/16 OK, business chain (token -> session -> ask -> Redis/Kafka) green.
- OCR end-to-end acceptance requires installing PaddleOCR or Tesseract; until then the
  visual channel stays `DEGRADED` and the API truthfully reports `available=false`.
- Phase 3+ still pending (body-service knowledge ingest / semantic retrieval integration).
- Work Platform BFF/API mode: **minimal Ops subset done** (2026-09-15) — middleware
  observe/start/stop + tracing observe are real. Remaining endpoints (tasks/approvals/models/
  vitals/overview aggregation, WebSocket events) are still pending and fall back to Mock; since
  2026-09-16 that fallback is no longer silent (header badge + banner + per-module alert).
- `ModuleView.vue` split and Element Plus on-demand import: assessed, but both need a visual
  regression baseline before execution (see 2026-09-16 section).

