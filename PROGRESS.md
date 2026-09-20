# Agent-Lifeform Development Progress

> Last updated: 2026-09-19

## Archived stage reports

Phase 0 / Phase 1 / Phase 2 / Phase 3 stage reports (stage report + execution log + test &
acceptance report, each with an HTML twin) are archived in
`docs/项目进度日志报告/`. Phase 0/1 reports were added retroactively on
2026-09-15 (the three-doc convention was established at Phase 2).

## Current milestone

Phase 0, Phase 1, Phase 2 and Phase 3 development scope is implemented on the long-lived branches
`workbuddy/main`, `codex/main` and `dev`, which since 2026-09-16 point at the
same commit (all three kept in sync via fast-forward; the `workbuddy/main` line brought in the
Java per-file documentation set `docs/java-services/` and the middleware real-state fixes, the
`codex` line brought in the wp-bff control-plane hardening, data-source degradation surfacing
and CI additions — merged on a semantic basis, no side was overwritten).
Phase 2 (感官期 / sensory stage, R2-01~R2-10) landed on 2026-09-12; after the D-1 cross-service
fix (2026-09-13) and the hardening pass below the suite stood at **61 Java tests green**.
Phase 3 (躯体期 / body stage, R3-01~R3-09 + R-C03) landed on 2026-09-18 — body-service was
rewritten into a persisted knowledge pipeline (parse -> chunk -> embed -> three-tier store ->
semantic retrieve -> rerank -> RAG) and **DEBT-001 closed**.

A **requirement-audit & gap-closure pass** followed on 2026-09-18 (see
`docs/优化日志/2026-09-18-Phase3需求审核与补全.md`): every Phase 3 requirement was reconciled
against the code, the production tree was scanned for stub/placeholder implementations
(**none found**), and the DEBT registry was cross-checked against the `DEBT-0xx` markers in code.
Four in-scope gaps were found and closed — knowledge ingest was unreachable from the work
platform (no BFF write path), the R3-02 "format parse" step was missing, the IN-05 iteration
parameters did not exist, and the three-tier reconciliation task required by the design's risk
section was absent. Four **out-of-scope** requirements from the Phase 3 requirement doc
(IN-01 skill packages, WB-03 expert center, WB-05 skill marketplace, MC-01 collaboration-bus
service) were never delivered and are now registered instead of silently dropped.

Three further hardening passes then ran on 2026-09-18 without adding features — a Mock/API
alignment pass, a doc-figure consistency pass, and an exception-flow consolidation pass
(the last one is recorded in `docs/优化日志/2026-09-18-异常流程归纳与全平台错误信封统一.md`).
All three are summarised in [Phase 3 post-closure hardening](#phase-3-post-closure-hardening-2026-09-18).

**Phase 4 (大脑期 / brain stage, R4-01~R4-09 + R-C04) landed on 2026-09-19** — see
[Phase 4](#phase-4-大脑期--brain-stage-2026-09-19) below for the full record.

A **requirement-audit & gap-closure pass** on Phase 4 followed on 2026-09-19 (see
`docs/优化日志/2026-09-19-Phase4大脑期需求审核与补全.md`): re-reading the implementation against
the requirement doc surfaced five defects the unit tests and the acceptance report had both
missed — two semantic-level pseudo-implementations (semantic cache claimed a Redis backend but
only wrote an in-process dict; the decision-replay endpoint returned `200 + available:true` with
an empty chain for *any* id), one cross-service tenant-resolution inconsistency, one cross-pipeline
consistency defect (knowledge ingest did not invalidate the retrieval hot cache) and one
extraction-caliber gap (memory-graph edges were written without their endpoint nodes). All five
are fixed with added regression tests, and the previously-undelivered items (IN-02 memory graph,
the X4 self-verify/annotate steps, the three decision cards, the overview `model_calls` /
`model_runtime` metrics, the 50-case intent evaluation set, P50/P95/P99 latency) are landed.

The suite now stands at **162 Java + 124 Python + 55 wp-bff tests green** (341 total), contract
check 0 FAIL (implemented 11 paths / 12 methods), Java doc coverage 113/113; the work platform
and the Console both run against the real brain chain.
The Java split is gateway 2 · session-manager 43 · sense-service 53 · body-service 64.

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

## Phase 3 (躯体期 / Body Stage)

Requirement coverage R3-01 ~ R3-09 plus terminal-line R-C03. Source under
`services/java/body-service` (rewritten), `services/python/nlp-service`, `services/node/wp-bff`
and `web/work-platform`. Closes **DEBT-001** (in-memory retrieval -> Qdrant vector search).

### Storage & pipeline

- [x] R3-01 three-tier storage facade: `StorageFacade` + `MetadataStore` (`PgMetadataStore` cold /
      `InMemoryMetadataStore` graceful fallback) + `HotCacheStore` (Redis hot, tenant-prefixed
      fingerprints) + `TierRouter` (configurable heat-based tiering)
- [x] R3-02 `ChunkProcessor`: heading/paragraph boundaries, 800-char blocks, 50-char overlap,
      heading kept inside the block, merge only within the same heading; Python `chunking.py`
      mirrors the same algorithm for cross-language consistency checks
- [x] R3-03 embedding via nlp-service `/api/nlp/embed` (BGE-M3 when available, otherwise a
      deterministic hash n-gram 768-dim backend that reports `degraded=true` — honest degradation,
      never faked); `EmbeddingClient` over HTTP/1.1
- [x] R3-04 `QdrantClient`: collection bootstrap, vector-size reconciliation, batch upsert,
      tenant-filtered search; point ids are name-based UUIDs (`docId#index`) so re-ingest is
      idempotent
- [x] R3-05 `RetrievalService`: cache-first -> vector recall -> rerank, with per-stage latency
      sampling
- [x] R3-06 `RerankClient` + nlp-service `/api/nlp/rerank` (cross-encoder when available, otherwise
      lexical-overlap rerank); real health probe; degrades to recall order without blocking
- [x] R3-07 `RagPipeline`: retrieve -> compose answer + traceable citations; reports
      `generator=template` truthfully (DEBT-002 still open, Phase 4)
- [x] R3-08 cache-first strategy: normalized query fingerprint, documented hit rate
- [x] R3-09 `KnowledgeController`: `POST/GET /api/body/knowledge`, `POST /api/body/knowledge/batch`,
      `POST /api/body/retrieve`, `POST /api/body/retrieve/plan`, `POST /api/body/rag/answer`,
      `GET /api/body/knowledge/stats`, `GET /api/body/knowledge/reconcile`,
      `DELETE /api/body/knowledge/{docId}`, `GET /api/body/health`
- [x] Sense -> body closed loop: `SenseCollectedConsumer` subscribes `lifeform.sense.collected`;
      `SenseEventPublisher` payload extended with `title`/`content` so events are self-contained
- [x] Removed the superseded in-memory implementation (`BodyStore`, `BodyController`,
      `BodyStoreTest`) - the DEBT-001 write-off

### Session & work platform

- [x] session-manager `BodyClient.retrieve()` declares explicit `top_k` + cache-first semantics and
      degrades to an empty result set when body-service is unavailable
- [x] wp-bff new proxy endpoints `GET /api/wp/knowledge` and `POST /api/wp/knowledge/search`
      (query-term parsing + `buildSnippet` hit highlighting); implemented-endpoint registry 6 -> 8
- [x] Contract: both endpoints + `KnowledgeStats` / `KnowledgeSearchResult` schemas registered with
      `x-wp-status: implemented`
- [x] R-C03 work-platform body view (`KnowledgeView.vue`): knowledge totals, retrieval quality
      (P99 / hit rate / cache hit rate), three-tier storage health, and a retrieval test panel with
      highlighted snippets and recall-vs-rerank scores

### Verification (2026-09-18)

- Java full suite `scripts/mvn-dev.sh test`: **114 passed / 0 failed** (gateway 2 · session 10 ·
  sense 48 · body 54; body-service grew from 1 test to 54 across 9 classes)
- `nlp-service` pytest: **52 passed** (intent 15 + OCR 4 + chunking 12 + embedding 13 + rerank 8)
- wp-bff `node --test`: **27 passed**
- `contract-check.py --work-platform`: implemented 8 paths <-> BFF 8 paths (9 methods), **0 FAIL**
- `java-doc-coverage.py`: body-service 36/36, repo-wide **103/103** hand-written sources
- Frontend `vue-tsc --noEmit` + `vite build`: passed
- End-to-end (Docker: qdrant/redis/postgres/kafka/jaeger + 3 services):
  **35 PASS / 0 FAIL** — retrieval P99 **366 ms**, hit rate **0.925**, cache hit rate **0.45**,
  ingest failures 0, tenant isolation enforced, re-ingest idempotent, delete purges vectors
  (no orphans), Kafka sense event ingested and recalled

### Phase 3 requirement re-audit (2026-09-18, second pass)

An independent audit re-read the Phase 3 requirement spec against the actual source instead of
trusting the phase reports. Outcome: **no fake/placeholder implementation found** (4 honest
degradations, 2 real gaps, 0 pseudo-implementations), but one functional break and several
stale doc figures were fixed:

- [x] **Gap — R3-02 format parsing step was missing**: added `chunk/DocumentParser.java`
  (md / html / text normalisation + heading extraction); PDF is explicitly rejected with a
  unified error code instead of being silently chunked as text (DEBT-013)
- [x] **Gap — work platform could not ingest at all** (biggest find): added BFF
  `POST /api/wp/knowledge` ingest proxy + `KnowledgeView.vue` ingest panel (upload/paste,
  format selector, honest `degraded` reporting)
- [x] `IngestService` now surfaces `format / parser / degraded` in `IngestOutcome`
- [x] Contract: `/knowledge` POST documented with `KnowledgeIngestResult` schema; `contract-check.py`
  count semantics aligned (path-level vs method-level no longer look contradictory)
- [x] Tests added: `DocumentParserTest` (7) · `StorageFacadeTest` (5) · wp-bff ingest cases (+5)
- [x] **Security gap closed on the write path**: the new `POST /api/wp/knowledge` initially bypassed
  auth (it reused the read-only `/knowledge` exemption), i.e. an unauthenticated write path into the
  knowledge base. It now passes the same `origin-allowlist + X-WP-Control-Token` check as
  `/middleware/{key}/start|stop`; 2 negative cases added (401 without token / 403 off-allowlist,
  neither may reach body-service). The Vite dev proxy already injects the token for `/api/wp/*`,
  so the front end is unaffected — direct access to :8090 is now rejected.
- [x] Doc truthfulness fixes: removed non-existent `status: READY` and non-runnable curl examples
  from the DEMO; corrected source/test counts across module docs
- [x] Registered dangling `DEBT-010/011/012` that code referenced but the registry did not list


## Phase 4 (大脑期 / Brain Stage, 2026-09-19)

Scope: R4-01~R4-09 plus the terminal line R-C04 (Console brain view + interaction terminal).
Baseline `19c5347` — 39 files changed, +3839/-83.

### Brain layer (Python nlp-service, new `app/brain/`, 1374 lines + 399 lines of tests)

- [x] **R4-03** `llm_gateway.py` — L1/L2/L3 routing, timeout + retry, token accounting; with no
  engine configured it uses the deterministic template backend and **truthfully reports**
  `degraded=true` / `generator=template` (DEBT-015/016)
- [x] **R4-04** `semantic_cache.py` — Redis vector similarity @0.95 with in-process fallback
  (marked `degraded`); **measured hit rate 57.1%~66.7%** against DoD >= 20%
- [x] **R4-05** `planner.py` — intent -> task template (qa / summarize / retrieve / chat);
  reports `planner=rule` honestly (DEBT-017)
- [x] **R4-06** `pipeline.py` — LangGraph `StateGraph` chain `plan -> retrieve -> gap -> generate`
  emitting the `chain[]` decision trace used by the D5 replay view
- [x] **R4-07 / R4-08** `gap.py` — coverage-based gap detection + source annotation
  (title / score / rerank_score / snippet)
- [x] Endpoints `/api/nlp/brain/{ask,plan,health,cache/stats}`

### Session layer (Java session-manager)

- [x] **R4-01** `fsm/SessionFsm` — `NEW -> ACTIVE <-> IDLE -> TIMEOUT/CLOSED`, terminal-state
  rejection, timeout recoverable
- [x] **R4-02** `fsm/SessionStore` — Redis Hash + TTL 2h + last-K-turn context;
  **verified live**: kill + restart the process, the session comes back `ACTIVE` with its messages
- [x] **R4-06** `orchestration/BrainClient` — `/ask` goes through the brain layer and falls back to
  local retrieval with `generator=local-retrieval` (never a fabricated answer)

### BFF + terminal line

- [x] wp-bff `GET /api/wp/brain`, `POST /api/wp/brain/ask`, `GET /api/wp/brain/{decision_id}`;
  contract flipped `planned -> implemented`, errors carry the unified envelope
- [x] **R4-09** work-platform chat view sources, gap hint, degradation badge, cache-hit marker and
  decision-chain replay panel, all from the real brain response
- [x] **R-C04** Console brain view renders real model/cache/retrieval metrics and the interaction
  terminal completes multi-turn dialogue (Mock/Api parity preserved, R-C09)

### Two calibrated-after-measurement decisions (registered, not silent)

- Coverage threshold: the design's 0.85 assumes semantic embeddings + cross-encoder reranking;
  the current backends score 0.10~0.28, so the default is **0.35** (`GAP_COVERAGE_THRESHOLD`,
  tunable) with a usable-score floor of 0.15 — documented in `docs/技术债台账.md` §4.14
- Usability scoring keys on `score` (vector recall); `rerank_score` is display/ordering only
  because the lexical reranker compresses it into 0.10~0.12

### Runtime-only defects found and fixed (see 技术债台账 §4.15)

1. Spring constructor-injection ambiguity — session-manager **failed to boot**
   (`No default constructor found`); fixed with `@Autowired` on the primary constructor plus
   `@Component` on `SessionStore`, guarded by the new `SessionWiringTest`
2. BFF read the knowledge counts from the wrong nesting level (`knowledge.knowledge.chunks`) so
   `GET /api/wp/brain` always reported `chunks: 0`
3. The "information gap" notice hard-coded `threshold 0.85` while the effective value is 0.35

### Verification (2026-09-19, real Redis / Qdrant / PostgreSQL)

- session-manager 8081 -> nlp 8000 -> body 8083: create (ACTIVE), ask#1 186ms (5 sources,
  coverage 0.509, `template` degradation marker), ask#2 104ms (multi-turn), context 4 turns,
  close (CLOSED), asking on a closed session -> **409 AGENT_CONFLICT**, restart -> session restored
- Vite 3001 -> BFF 8090: `POST /api/wp/brain/ask` 200 with 5 sources; `GET /api/wp/brain` 200 with
  three healthy model-runtime nodes; blank question -> 400 `AGENT_BAD_REQUEST`
- Console (vm smoke harness): metrics render from the live BFF; two real dialogue turns; `ds=mock`
  falls back to labelled demo data; BFF down renders a failure notice instead of a fake reply

Known gap carried forward: **no real LLM engine is attached**, so generation stays on the template
backend and is labelled as such (DEBT-015/016). The Console's intent-distribution and
session-trend charts have no backend statistics endpoint yet and are explicitly labelled demo.

### Requirement-audit gap-closure 补记 (2026-09-19)

Re-reading the Phase 4 implementation against the requirement doc surfaced five defects that the
unit suite and the acceptance report had both missed (registered in `docs/技术债台账.md` §4.16,
evidence in `docs/优化日志/2026-09-19-Phase4大脑期需求审核与补全.md`):

| # | Defect | Nature | Fix |
|---|---|---|---|
| A1 | Semantic cache claimed Redis but only wrote an in-process dict | **pseudo-implementation (red line)** | read/write split by backend; Redis Hash + TTL + tenant prefix + capacity trim; memory only as degraded fallback; real-Redis cases added |
| A2 | Decision replay returned `200 + available:true + chain:[]` for any id | **pseudo-implementation (red line)** | real IN3 AuditLog (PG `brain_decision_log`, JSONB payload); replay by `decision_id` + tenant; **miss -> 404**; negative cases added |
| A3 | QA read the tenant from the **body** but replay from the **header** | cross-service caliber mismatch | unified `resolve_tenant()` (header first, body fallback) across brain/memory endpoints |
| A4 | Knowledge ingest did **not** invalidate the retrieval hot cache | cross-pipeline consistency | single/batch ingest both call `invalidateTenant`; 4 regression cases |
| A5 | Memory-graph relation endpoint wrote edges without registering endpoint nodes | extraction-caliber gap | relation endpoint upserts nodes before edges; `_clean_name` splits on relation verbs |

Undelivered items landed in the same pass: **IN-02 memory graph** (entity/relation extraction +
PG adjacency list + recursive-CTE subgraph + context compression, compression ratio **0.883**,
load **35ms**), the **X4 self-verify + annotate** steps (chain now
`intent→plan→retrieve→generate→verify→annotate`), the three decision cards (confidence / audit /
provenance), the overview cockpit `model_calls` / `model_runtime` aggregation (BFF
`GET /api/wp/brain`), the **50-case intent evaluation set** (`tests/evalset_phase4.json`, cascaded
**98.33%** / L0 fallback 90.0%), and P50/P95/P99 latency via `latency_percentiles()`.

Console's **intent distribution** is now switched to live session-manager statistics
(`GET /api/wp/session/stats`); only **session trend (last 7 days)** remains a labelled demo value.
Contract gate fixed: `normalize_frontend_path` now strips `?...` before comparison (a query string
is a path's input, not its identity), so `contract-check.py` is 0 FAIL again.

Two e2e harnesses (`.workbuddy/tmp/e2e_phase4.py`, `e2e_session_phase4.py`) run green against real
Redis / PG / Kafka: semantic cache `backend=redis degraded=false`, cache-hit `similarity=1.0` at 3ms;
6-step replay with `404` on unknown/cross-tenant id; memory graph `backend=postgres`, 3 entities /
2 relations; session FSM create→ask→close→**409**→Redis-restored context; BFF session proxy passthrough.

### gRPC port conflict fixed (2026-09-19, DEBT-018)

`session-manager`'s gRPC health default port was `9092` — the same host port Kafka binds — so the
service only started when `GRPC_PORT=19092` was passed manually (and `healthcheck.sh`'s
`check_port session-grpc 9092` was silently probing Kafka). The default is now **19092** in
`application.yml`, `healthcheck.sh` probes 19092, and the service registry §5 documents the move.
Verified by starting the jar with **no `GRPC_PORT` override**: `Started SessionManagerApplication in
6.482s`, 19092 listening (session-manager) with 9092 still Kafka, `/actuator/health` = `UP`.
The latent `sense-service 9093 ↔ Alertmanager (P7) 9093` overlap is registered, not silently
changed. Docs updated in step: `docs/java-services/*`, `docs/proto契约使用说明.md`, `docs/demo/Phase*`,
`docs/技术债台账.md` (§4.17), `docs/项目进度总览.md`.

## Phase 5 (四肢期 / Limb Stage, 2026-09-19)

Scope: R5-01~R5-08 plus the terminal-line R-C05 (pre) and the innovation item IN-06
(tool-contract governance). Delivered as a **new independent Maven module** `tool-executor`
(HTTP 8084 / gRPC 9095) — no existing service was rewritten, so there is no compatibility debt.

### Registry & execution (R5-01 / R5-02)

- [x] `registry/ToolRegistry` — register / list / detail / unregister / deprecate (30-day window);
      semver versioning with a **SHA-256 schema fingerprint** (first 16 hex chars) as the stable
      "did the schema change" criterion; action classification `REGISTER` / `VERSION_UPGRADE` /
      `SCHEMA_CHANGE` / `REREGISTER` / `DEPRECATE` / `UNREGISTER`
- [x] `registry/ToolBootstrap` — the three built-ins self-register on `ApplicationReadyEvent`;
      consumer declarations (`brain-planner` / `combo-task` / `code-assist`) feed the impact analysis
- [x] `exec/ToolExecutor` — fixed order whitelist -> schema -> sensitive-args -> circuit-breaker ->
      timed execution -> normalisation -> audit, with two invariants: **audit is never lost**
      (the method never throws; failures return a normalised result and the controller picks the
      HTTP semantics) and **a timeout never hangs** (dedicated daemon pool, hard timeout,
      `cancel(true)`, consecutive-failure breaker at 3 / 30 s)
- [x] Failure paths return the sandbox facts (`exit_code` / `sandbox_backend` / `stdout` /
      `stderr`, clipped to 2000 chars) so "non-zero exit code 1" is no longer the only clue

### Sandbox isolation (R5-03 / R5-05)

- [x] `sandbox/SandboxExecutor` is the single entry point: static pre-check first (**12 dangerous-code
      rules** hit -> rejected before any backend is touched), then Docker (isolated) else the
      restricted-process fallback
- [x] `sandbox/DockerSandboxBackend` — `--network none`, `--memory/--memory-swap` 256m, `--cpus 0.5`,
      `--pids-limit 64`, `--read-only` + 16m tmpfs, `--user 1000:1000`, `--cap-drop ALL`,
      `--security-opt no-new-privileges`; `available()` probes both the daemon **and** the image,
      cached 15 s
- [x] `sandbox/RestrictedProcessBackend` — **honest degradation, not an isolation boundary**:
      results always carry `degraded=true`, and `SandboxExecutor.execute()` forcibly re-stamps the
      flag so a fallback result can never be presented as a sandbox result
- [x] `tools/CalculatorTool` — hand-written recursive-descent parser, **no `eval` / no script
      engine** (a calculator is the tool an LLM triggers most; `eval` would bypass the whole
      validation gate)
- [x] `tools/HttpTool` — SSRF check first, then the domain allow-list (`*.suffix` supported),
      response body clipped to `HTTP_MAX_BODY_BYTES`
- [x] `tools/CodeTool` — always via `SandboxExecutor`; pipes `sandbox_backend` / `sandbox_degraded` /
      `rejected` through so "where did this actually run" stays observable

### Security gates & audit (R5-07 / R5-08)

- [x] `guard/ToolGuard` — whitelist (not listed -> rejected **without reaching the executor**),
      JSON-Schema **Draft-07** argument validation returning field-level `details`, and sensitive-arg
      scanning (12 global rules + per-tool patterns) covering file deletion, disk destruction,
      privilege escalation, sensitive files, reverse shells, path traversal, download-and-execute,
      dynamic execution and raw network access
- [x] `audit/ToolAuditLog` — **PG `tool_audit_log` is the truth source**, Kafka
      (`lifeform.tool.invoked`) is an event sidecar only (a bus hiccup must not lose an audit);
      PG unavailable -> in-process ring buffer with `degraded=true` and an honest `dropped` count;
      argument summaries redact `token` / `secret` / `password` / `key` / `credential`
- [x] Blocked-counting caliber is shared between the SQL and the in-memory path: only whitelist /
      sensitive-arg / sandbox rejections count as **security blocks** — an argument-format error
      (`AGENT_TOOL_ARGS_INVALID`) does not

### IN-06 tool-contract governance

- [x] Impact analysis over **explicitly registered** consumers; `ImpactReport.note` states that
      hard-coded callers will not appear (registered as DEBT-019)
- [x] **The gate now reads the schema hash**: `contracts/tool-schema-baseline.json` is compared by
      both `registry/ToolSchemaBaselineTest` and `scripts/contract-check.py`, so "changed a tool
      schema but forgot the baseline" fails instead of relying on the `contract_test_required` flag
      being noticed by a human

### Terminal line (R-C05 预) & BFF

- [x] wp-bff proxies 9 methods under `/api/wp/tools*` (`POST /tools/execute` is a control endpoint:
      origin allow-list + `X-WP-Control-Token`); contract flipped to `implemented` with the
      `ErrorEnvelope.code.enum` extended by the seven tool-domain codes
- [x] work-platform **ExecutionView**: tool cards (version / `schema_hash` / timeout / sandbox flag /
      breaker badge), a real trial-run drawer, sandbox status lamp, audit stream, success rate and
      P50/P95/P99, plus the **IN-06 impact drawer** driven by `getToolImpact(name)`
- [x] `scripts/healthcheck.sh` now also probes **8084 / 9095 / 8090**

### Verification (2026-09-19)

- `scripts/mvn-dev.sh test`: **251 passed / 0 failed** (gateway 2 · session 43 · sense 53 ·
  body **67** · **tool-executor 86**; body +3 from the GAP-closing round)
- `pytest`: **158 passed / 1 skipped** · wp-bff `node --test`: **87 passed**
- `contract-check.py --work-platform`: 60 endpoints, implemented 30 paths / 31 methods, **0 FAIL**;
  tool baseline 3 tools <-> 3 registered
- `timeout-budget-check.py`: 19 cross-service edges, **ok=19 / gap=0 / fail=0** — **all inversions
  cleared** on 2026-09-19 (GAP-closing round, direction: *tighten the inner layer, cover from the
  outer layer*):
  - **GAP-01 closed by tightening the downstream**: `BRAIN_TOTAL_BUDGET_MS` 25000 -> **20000** (the
    old 25 s cap exceeded the cascade worst case of 22 s and never bound); session `BRAIN_TIMEOUT`
    30 s is now exactly 1.5x. Raising the upstream to 33 s instead would have pushed the session
    total budget to 38 s and worsened GAP-06 to require >= 57 s — headroom is a function of *both*
    sides.
  - **GAP-05 closed by giving the downstream an end-to-end budget (and fixing a registry lie)**:
    body `/retrieve` pipeline = embed (12 s) + Qdrant (2 s) + rerank (10 s); the registered "10 s"
    was merely body's outbound read timeout. New `common/BudgetGuard.java` wraps the warm pipeline
    in `RETRIEVAL_TOTAL_BUDGET_MS = 10 s` (504 `AGENT_TIMEOUT`, never an empty result disguised as
    "no data"); upstream `RETRIEVAL_BUDGET_MS` 8000 -> **15000**, and the second caliber
    `RETRIEVAL_TIMEOUT_SECONDS` was retired (retrieval.py derives its timeout from budget.py).
  - **GAP-06 closed with a dedicated tier**: new `ASK_TIMEOUT_MS = 53000` serves only the
    session `/ask` edge; `GENERATE` stays 45000 (shared by `/brain/ask` and memory edges). A guard
    assertion enforces `ASK > GENERATE` so the two tiers cannot silently merge back.
  - Earlier round for context: **wp-bff's own 5 inversions were cleared** (`brain/ask` + `memory/*`
    5000 -> 45000 against an LLM-cascade worst case of 22000 ms; knowledge ingest `30000` hard-coded
    -> `WRITE` 60000). The inversions were *already live*, not a future risk: even with only
    `TemplateEngine`, worst case `3 s x 2 = 6 s` exceeded the old 5000 ms default.
- **REC-01 — deadline propagation (2026-09-19)**: `nlp-service` gained `app/budget.py`
  (`run_with_budget` / `BudgetExceeded`), all budgets returning `504 AGENT_TIMEOUT`; the LLM cascade
  uses `min(level timeout, remaining budget)` per level and reports `deadline_exceeded`;
  `session-manager` gained `RequestBudget` (35 s per ask, per-hop `clamp`, no call is issued once
  the budget is spent) plus named tiers (`INTENT` 5 s / `RETRIEVE` 15 s / `BRAIN` 30 s).
  Lesson recorded in the registry header: capping a downstream budget makes its worst case
  *provable* **and** changes the upstream headroom ratio — both sides must be reconciled in the
  same round.
- **Gate blind spot found and fixed while reconciling the table**: the original anchor regex
  `READ_TIMEOUT = Duration\.ofSeconds\((\d+)\)` **substring-matched** `DEFAULT_READ_TIMEOUT`, so TB-11 /
  TB-12 silently kept reading the stale 10 s default instead of their real tier. Anchors were tightened
  to named tiers and downstream anchors added for TB-06 / TB-09 / TB-11 / TB-12 / TB-13 / TB-15 / TB-16.
  Reverse-verified in the GAP-closing round: a planted anchor drift (19999 vs 20000) → `fail=1 / exit=1`.
- **Test-writing pitfall caught by a red test**: stubbing `embed` does nothing because the pipeline
  calls the mocked `embedOne` (the interception layer never reaches the real body); stubs must target
  the layer that is actually invoked, otherwise the test is fake-green.
- `doc-consistency-check.py`: **0 FAIL** — anomaly IDs unique, section numbers unique,
  md/html twins in sync
- `java-doc-coverage.py`: **160/160** (`BudgetGuard.java` registered; tool-executor 43/43)
- Runtime probes on :8084 — `calculator` `sqrt(2)+pow(2,10)` OK; `code` `print(128*17)` OK
  (`sandboxed=true, sandbox_backend=docker, degraded=false`);
  `rm -rf` and outbound-network samples both **403 + audited**;
  `GET /api/tool/sandbox` reports `docker_available=true` / `active_backend=docker` /
  `isolated=true` / `degraded=false`

> **Closed (2026-09-19, real-link round)**: `agent-sandbox:latest` **is** built on this host
> (124 MB) and **R5-03 is now verified on the real Docker isolation path**, not only the degraded
> backend — kernel-level evidence: outbound → `Errno -3 Temporary failure in name resolution`,
> rootfs write → `OSError: Errno 30 Read-only file system`. The earlier `docker_available=false`
> was a *timing* artifact (the image was not yet built when the JVM probed), and a second defect
> was found behind it: `DockerSandboxBackend.available()` ran `docker info` + `image inspect`
> **synchronously on the request thread** (5.7 s cold on Windows), which exceeded wp-bff's 2.5 s
> sub-request budget (`server.js:46`) and made `/api/wp/tools` report
> `sandbox:null + partial:["sandbox_unavailable"]` — *a slow answer expressed upstream as a
> missing one*. Fixed by making `available()` **O(1) and non-blocking** (probe once at startup,
> refresh on a daemon thread behind a 15 s TTL, single-flight latch); endpoint latency
> 5.7 s → 0.5 s, `partial:false`. Guarded by 4 new assertions in
> `sandbox/DockerSandboxBackendTest` (slow probe must not block the caller, no probe storm within
> TTL, zero probe cost when disabled). See
> `docs/优化日志/2026-09-19-工作平台真实链路打通与沙箱探测非阻塞化.md`.
> DEBT-019~021 (in-process registry, networknt-vs-everit choice, metrics not on Prometheus) are
> registered with triggers rather than silently carried.

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
- [x] Chat view against the real brain chain: sources with similarity, gap hint, degradation
  badge, cache-hit marker and decision-chain replay (R4-09)
- [x] Console brain view + interaction terminal on real data with Mock/Api parity (R-C04)
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
- **Java source documentation** in `docs/java-services/`: per-file description of all 72 hand-written
  Java sources (6 gateway / 13 session-manager / 35 sense-service / 4 body-service main + 14 test classes)
  plus the 90 generated proto sources described by contract; 7 documents with HTML twins
- `scripts/md2html-report.py` renders reports/docs to self-contained HTML. **Must run with anaconda Python**
  (`E:/software/anaconda3/python.exe`); the managed Python has no `markdown` package.
  Usage: `E:/software/anaconda3/python.exe scripts/md2html-report.py --all "<dir>"` (`--all` skips `README.md`)

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
- Middleware cards now expose the probe type (`TCP 端口可达性`) and the indicator formerly labelled
  "进程：健康" is renamed to "端口状态：可达/不可达", so a green card is not mistaken for deep
  process health.

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

## 2026-09-16 Contract Alignment & Overview Aggregation (optimization pass 2)

Follow-up to the progress review: this pass fixes the structural gap between the BFF contract and the
implementation, and gives the overview page its first real data. Phase 3 stays frozen (the team is
consolidating Phase 0-2 material). Branch decisions recorded in README 8.4: `workbuddy/main` remains
an independent line (no merge) and `dev` belongs to another developer machine.

### P0 · Contract <-> implementation alignment

- Contract grew from 31 to **37 endpoints**, each path carrying an `x-wp-status` marker:
  **implemented 6 | planned 31**.
- Registered the five observability/Ops endpoints (`/healthz`, `/overview`, `/middleware`,
  `/middleware/{key}/start`, `/middleware/{key}/stop`, `/tracing`) plus
  `/suggestions/{suggestion_id}/apply`, which the front end was already calling but the contract
  never declared.
- `scripts/contract-check.py` gained three rules: implemented endpoints must be registered and marked
  `implemented`; every path called from `provider.ts` must match a contract template; every endpoint
  must carry `x-wp-status`.
- Negative tests prove the gate bites: removing `/tracing` from the contract -> exit 1
  (`FAIL 实现端点未登记契约: /tracing`); marking `/middleware` as planned -> exit 1
  (`FAIL 实现端点状态不符: /middleware`).

### P1 · BFF overview aggregation

- New `GET /api/wp/overview` aggregates only what has a real source: `observability.middleware`
  (8 TCP probes with up/down/pending) and `observability.tracing` (services, sampled spans, recent
  errors, sampled P99). The 15 still-unimplemented domains are returned in `gaps` and surfaced as
  degradation by the front end - no silent filling.
- Front end: `provider.getOverview()` calls the aggregate endpoint, and `OverviewView` gained an
  "OPS 观测区实时摘要" strip with four cards (port reachability / registered services / recent error
  traces / pending data domains).

### P1 · Governance and CI

- README 8.4 records the branch decisions: `git fetch` before pushing to `dev` (another machine's
  branch); `workbuddy/main` stays independent.
- CI gained a `docker-smoke` job (compose up + `healthcheck.sh`; requires a dind runner, currently
  `allow_failure: true`).

### Verification (2026-09-16, pass 2)

- wp-bff `node --test`: **14 passed**
- `scripts/contract-check.py --work-platform`: 37 endpoints, implemented 6 / planned 31, **0 FAIL**
- Vue `npm run typecheck` and `npm run build`: passed
- Live check: started wp-bff on :8091 and called `GET /api/wp/overview` - returned real probe data
  (Docker not running -> up=0/8, Jaeger `enabled=false`)
- Optimization log archived at `docs/优化日志/2026-09-16-契约对齐与总览聚合.md` (plus generated HTML)

## 2026-09-17 Documentation Consolidation

Goal: make the archived documentation answer two questions on its own — "where are we now?" and
"how is each feature developed?" — without reading the commit history.

- Added **`docs/项目进度总览.md`** (+ HTML): stage status table (Phase 0/1/2 accepted, hardening
  period in progress, Phase 3+ frozen, V1 design-ready), per-service feature matrix
  (Java 58 main + 14 test = 72 sources / 90 generated proto sources; nlp-service intent + OCR;
  work-platform; wp-bff), the 2026-09-16 test baseline, branch & remote topology, remaining items
  and the 6 registered engineering gaps. Stale figures from older archives are explicitly marked
  as such rather than edited in place.
- Added **`docs/功能开发流程.md`** (+ HTML): the shared 8-step pipeline (§8.0-8.5) plus a
  per-feature development flow for gateway / session+VS1 / sense pipeline / intent / OCR / body /
  work-platform / wp-bff / infrastructure / contract governance / docs toolchain, each with
  requirement source, code entry points, contracts, tests and acceptance criteria; ends with an
  add-a-feature checklist and this host's specific pitfalls.
- Added **`docs/README.md`**: docs map, reading order and the two supported HTML-generation
  environments (anaconda here, managed Python + `PYTHONPATH` on the other machine).
- Updated **`docs/项目进度日志报告/README.md`**: registered the hardening period (2026-09-15~16),
  refreshed reproduction commands (Java baseline **61**, not the archived 54), and documented both
  HTML generation environments.
- Branch naming recorded: the long-lived line is now **`codex/main`** (`codex/phase0-1-hardening`
  archived and removed on 2026-09-16); `codex/main` = `dev` = `workbuddy/main` = `ab2ad29`.
  `main` remains the untouched initial commit and is intentionally not maintained.

### Verification (2026-09-17)

- `scripts/md2html-report.py --all` regenerated the new + touched documents' HTML twins
  (anaconda Python 3.10.9, markdown 3.4.1)
- Doc coverage self-check: `python scripts/java-doc-coverage.py`
- No source code changed in this pass; Java/Python/contract/wp-bff baselines carry over from
  the 2026-09-16 figures above.

## Phase 3 post-closure hardening (2026-09-18)

After the requirement re-audit, three independent passes ran without adding new features.
Each is recorded in detail under `docs/优化日志/`.

### 1 · Runtime semantics & Mock/API alignment (`bce7921`)

A front-to-back connectivity pass: making the running services, the contract and the front end
tell the same story.

- [x] **Route semantics corrected**: unmapped routes answered 500 instead of 404 across the three
      Java services, because Spring route-layer exceptions fell through to the catch-all handler —
      "endpoint missing" was indistinguishable from "service broken". Now mapped to
      `AGENT_NOT_FOUND` / `AGENT_METHOD_NOT_ALLOWED` / `AGENT_UNSUPPORTED_MEDIA_TYPE`, each with a
      test (see `docs/java-services/07 §5.1`)
- [x] **Mock <-> real response reconciled in both directions** — the forward check alone had been
      silently dropping fields the real API returns and Mock does not declare
- [x] Proxy targets / ports externalised (`WP_BFF_URL` / `WP_GATEWAY_URL` / `WP_DEV_PORT`);
      `.env.example` template aligned with the accepted state

### 2 · Doc-figure consistency pass (`8ba5544`)

Doc figures had drifted **while every gate was green** — the gates check existence, not accuracy.

- [x] Test counts, source-line counts and endpoint counts re-checked against actual command output
      across `docs/java-services/04~07`, `docs/项目进度总览`, `docs/功能开发流程` and both READMEs
- [x] Latest cross-cutting behaviour written into the human-facing docs (§5.1 route-layer dispatch,
      §5.2 unified exception egress)
- [x] Corrected an interface example that 404s if copy-pasted (`POST /api/body/rag` -> `/rag/answer`)
- [x] Doc coverage gate restored to green (**103/103 -> 106/106**)

### 3 · Exception-flow consolidation & unified error envelope (`6616616` + `d4a114b`)

Progress and quality were re-audited against the running code rather than against the phase
reports' conclusions. Three real gaps surfaced — **all of them in failure paths that every green
gate had missed**:

- [x] Error responses came in **three different shapes** (Java `{code,message,details}` ·
      wp-bff `{error}` · nlp-service leaking the FastAPI default body). One shape everywhere now,
      with an `ErrorEnvelope` schema added to the contract
- [x] wp-bff answered **404** for an unsupported method where the Java services answer **405** —
      opposite semantics for the same situation, which disguises "wrong method" as "endpoint does
      not exist". Now 405 + `Allow` header, driven by a `ROUTE_GUARD` derived from the single
      `IMPLEMENTED_ENDPOINTS` registry so that no third source of truth is introduced
- [x] nlp-service had **zero** HTTP-layer tests; `tests/test_http_errors.py` added (+10)
- [x] Added **`docs/异常流程归纳.md`**: runtime dispatch, degradation visibility, the planned-endpoint
      3-state rule (404 = not implemented · 4xx = misused · 5xx = broken) and a **D-01~D-30**
      catalogue of development-process exceptions

Three remainders are **registered rather than glossed over** (待办 18/19/20):
gateway (8080) is still unverified at runtime because **Spring WebFlux does not use
`@ControllerAdvice`**; the BFF body-layer unavailable case deliberately answers
`200 + available=false`; and the unified error envelope still has **no gate guarding it**.

### Verification (2026-09-18, post-closure)

- Java `scripts/mvn-dev.sh test`: **130 passed / 0 failed** (gateway 2 · session 15 · sense 53 · body 60)
- `nlp-service` pytest: **62 passed** · wp-bff `node --test`: **34 pass / 0 fail**
- `contract-check.py --work-platform`: implemented 8 paths <-> BFF 8 paths (9 methods), **0 FAIL**
- `java-doc-coverage.py`: **106/106** hand-written sources
- HTML twins regenerated, `STALE` check empty; the three long-lived branches are all at `d4a114b`

> Java counts are taken from the Maven log, **not** by summing surefire reports: a stale
> `body-service/target/surefire-reports/com.agent.body.store.BodyStoreTest.txt` from 2026-09-13
> (its test class was removed long ago) inflates the sum to 131.

## 2026-09-20 Environment Reproducibility Fix (optimization pass 3)

Follow-up to a hands-on evaluation on a Windows host: the suite was re-verified end to end and three
**environment-level** defects were found and fixed. No business logic changed.

### Findings (reproduced, not hypothesized)

- `pip install -r requirements.txt` **crashes on a Chinese Windows host**:
  `UnicodeDecodeError: 'gbk' codec can't decode byte 0x80` — pip reads requirement files using the
  system locale (GBK) while the file was UTF-8 with Chinese comments (96 non-ASCII bytes).
- `requirements-dev.txt` **did not declare pytest**, although every documented verification step runs
  `python -m pytest`; installing both requirement files still left pytest missing.
- No venv existed in the repo and the system interpreter lacked `langgraph`/`redis`, so four
  brain-stage test modules could not even be collected.

### Fixes

- `requirements.txt`: Chinese comments replaced with English (file is now pure ASCII); dependency set unchanged.
- `requirements-dev.txt`: added `pytest>=8.0` and `httpx>=0.27.0` (TestClient transport).
- New `scripts/setup-python-env.ps1`: creates `services/python/nlp-service/.venv`, forces
  `PYTHONUTF8=1`, installs both requirement files, then runs pytest (`-Recreate` / `-SkipTest` supported).
- New `scripts/model-readiness.py`: checks whether `sentence-transformers` is importable and whether
  `BAAI/bge-m3` and `BAAI/bge-reranker-v2-m3` are present in the HF cache, prints the acquisition
  commands (including the hf-mirror endpoint) and the re-index warning. Turns DEBT-010/011 from a
  note into a checkable state.
- README 4.4 documents the workflow.

### Verification (2026-09-20, Windows host)

- `scripts/setup-python-env.ps1` run against a **deleted venv**: install succeeded (exit 0).
- `pytest` on that fresh venv: **158 passed / 1 skipped** — matches the documented baseline.
- `scripts/model-readiness.py`: correctly reports both models missing, with actionable guidance.
- Java `mvn test`: **252 passed / 0 failures / 0 errors**
  (gateway 2 · session 43 · sense 53 · body 68 · tool-executor 86).
- wp-bff `node --test`: **87 passed**; `contract-check.py --work-platform`: 60 endpoints, 0 FAIL;
  `timeout-budget-check.py`: ok=19 / gap=0 / fail=0; Vue `typecheck` + `build`: passed.

### Not changed

- Element Plus on-demand import and the `ModuleView.vue` split stay deferred (need a visual
  regression baseline).
- The sandbox image build remains an environment step: `infra/docker/sandbox/Dockerfile` and its
  README already cover it; Docker was not running on this host, so R5-03 was not re-verified here.

## Remaining

- ~~Full Docker/Jaeger runtime smoke test requires Docker daemon.~~ Done on 2026-09-13:
  8 infrastructure containers + 4 Java services + nlp-service + work-platform all running,
  `healthcheck.sh` 16/16 OK, business chain (token -> session -> ask -> Redis/Kafka) green.
- OCR end-to-end acceptance requires installing PaddleOCR or Tesseract; until then the
  visual channel stays `DEGRADED` and the API truthfully reports `available=false`.
- ~~Phase 3+ still pending (body-service knowledge ingest / semantic retrieval integration).~~
  Done on 2026-09-18: Phase 3 (R3-01~R3-09 + R-C03) delivered the full knowledge pipeline,
  three-tier storage, semantic retrieval, reranking, RAG and the sense->body closed loop;
  DEBT-001 closed. **Phase 4 (大脑期 / brain stage, R4-01~R4-09 + R-C04) landed on 2026-09-19**;
  **Phase 5 (四肢期 / limb stage, R5-01~R5-08 + R-C05 预 + IN-06) landed on 2026-09-19**.
  Phase 6 (小脑期 / orchestration) is next.
- **Sandbox true isolation is unverified on this host**: `agent-sandbox:latest` is not built, so
  `docker_available=false` and execution goes through the restricted-process fallback
  (`degraded=true`). Build the image and re-run the escape-sample set before R5-03 is written up
  as verified.
- Tool registry is in-process only (no etcd) and tool metrics are not on Prometheus — DEBT-019/021.
- Real embedding (BGE-M3) and cross-encoder reranker weights are not installed on this host;
  the services fall back to deterministic backends and report `degraded=true` (honest degradation,
  never faked). Installing the models switches them automatically.
- Large-document ingest currently relies on the event payload carrying the body text; reading
  back from staging storage for oversized documents is a registered follow-up debt.
- Work Platform BFF/API mode: **minimal Ops subset done** (2026-09-15) — middleware
  observe/start/stop + tracing observe are real; 2026-09-18 added real knowledge stats/search.
  Remaining endpoints (tasks/approvals/models/vitals, WebSocket events) are still pending and fall
  back to Mock; since 2026-09-16 that fallback is no longer silent (header badge + banner +
  per-module alert).
- `ModuleView.vue` split and Element Plus on-demand import: assessed, but both need a visual
  regression baseline before execution (see 2026-09-16 section).

