/* ═══════════════════════════════════════════════════════════════════════════
 * ConsoleDataProvider · Console 统一数据模块（R-C09）
 * ---------------------------------------------------------------------------
 * 设计目标：Mock / Api 双数据源切换**零 UI 改动**。
 *   - 视图层只依赖 provider 暴露的方法与字段（均为 snake_case，与后端契约一致）
 *   - 切换方式：URL 参数 ?ds=api 或 localStorage.console_data_source
 *               （DATA_SOURCE=mock → api，无需改动任何视图代码）
 *   - API 模式：经网关（默认 http://127.0.0.1:8080）访问真实服务；
 *               尚未开发的视图接口自动回落 Mock 并标记 degraded=true（不静默失败）
 *
 * 阶段：Phase 2 感官期 —— 感官视图（R-C02）已接入真实 API：/api/sense/channels 等
 *       Phase 4 大脑期 —— 大脑视图 / 交互终端（R-C04）接入 wp-bff 大脑端点：
 *         GET  /api/wp/brain      模型路由 + 语义缓存 + 检索的模型指标与降级状态
 *         POST /api/wp/brain/ask  多轮问答（带来源标注 R4-08 / 缺口检测 R4-07）
 *       这两个端点**不经过网关令牌**（wp-bff 仅控制端点要求令牌），故走独立 BFF_BASE。
 * ═══════════════════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const params = new URLSearchParams(global.location.search);
  const DATA_SOURCE = (params.get('ds') || global.localStorage.getItem('console_data_source') || 'mock').toLowerCase();
  const API_BASE = params.get('api') || global.localStorage.getItem('console_api_base') || 'http://127.0.0.1:8080';
  const BFF_BASE = params.get('bff') || global.localStorage.getItem('console_bff_base') || 'http://127.0.0.1:8090';
  const TENANT_ID = params.get('tenant') || 'default';

  /* ── Mock 数据（与后端契约同构，字段 snake_case）───────────────────────── */
  const MOCK_SENSE_MATRIX = {
    sensors: [
      { type: 'VISUAL', name: '视觉', icon: '👁️', status: 'DEGRADED', isolated: false, available: true,
        collect_count: 42, accepted_count: 38, rejected_count: 4, item_count: 38,
        quality_pass_rate: 90.48, rate_per_minute: 3.5, last_record_at: 0, last_record_text: '2026-09-12 09:41:00' },
      { type: 'AUDIO', name: '听觉', icon: '👂', status: 'DOWN', isolated: true, available: false,
        collect_count: 0, accepted_count: 0, rejected_count: 0, item_count: 0,
        quality_pass_rate: 0, rate_per_minute: 0, last_record_at: 0, last_record_text: '' },
      { type: 'TOUCH', name: '触觉', icon: '✋', status: 'UP', isolated: false, available: true,
        collect_count: 186, accepted_count: 179, rejected_count: 7, item_count: 179,
        quality_pass_rate: 96.24, rate_per_minute: 12.4, last_record_at: 0, last_record_text: '2026-09-12 09:45:12' },
      { type: 'NOSE', name: '嗅觉', icon: '👃', status: 'DEGRADED', isolated: false, available: true,
        collect_count: 26, accepted_count: 22, rejected_count: 4, item_count: 22,
        quality_pass_rate: 84.62, rate_per_minute: 1.2, last_record_at: 0, last_record_text: '2026-09-12 09:30:08' },
      { type: 'TASTE', name: '味觉', icon: '👅', status: 'UP', isolated: false, available: true,
        collect_count: 18, accepted_count: 18, rejected_count: 0, item_count: 152,
        quality_pass_rate: 100, rate_per_minute: 0.8, last_record_at: 0, last_record_text: '2026-09-12 09:40:00' }
    ],
    total: 5, up_count: 2, degraded_count: 2, down_count: 1
  };

  const MOCK_SENSE_STATS = {
    summary: { total_batches: 272, accepted: 257, rejected: 15, quality_pass_rate: 94.49 },
    collect_rate_trend: [
      { bucket: '09:40', value: 12 }, { bucket: '09:41', value: 18 }, { bucket: '09:42', value: 9 },
      { bucket: '09:43', value: 21 }, { bucket: '09:44', value: 16 }, { bucket: '09:45', value: 24 }
    ],
    staging: { active_backend: 'minio', minio: { available: true }, local: { available: true } },
    dead_letter: { pending: 3, total: 15 },
    event: { topic: 'lifeform.sense.collected', available: true, published: 257 },
    r0: { enabled: true, tick_ms: 60000, rules: 2, triggered: 41 },
    config: { quality_threshold: 0.6, retry_max_attempts: 3, r1_max_channels_per_round: 3, ocr_enabled: true }
  };

  const MOCK_SENSE_CHANNEL_DETAIL = {
    TOUCH: {
      type: 'TOUCH', name: '触觉', icon: '✋', status: 'UP', isolated: false, available: true,
      collect_count: 186, accepted_count: 179, rejected_count: 7, item_count: 179,
      quality_pass_rate: 96.24, rate_per_minute: 12.4, last_record_text: '2026-09-12 09:45:12',
      recent_batches: [
        { batch_id: 'b-7f31', staging_status: 'ACCEPTED', quality_score: 0.98, item_count: 1, timestamp: 0, reject_reason: null, degraded: false, attempts: 1 },
        { batch_id: 'b-7f30', staging_status: 'ACCEPTED', quality_score: 0.92, item_count: 1, timestamp: 0, reject_reason: null, degraded: true, attempts: 2 },
        { batch_id: 'b-7f2e', staging_status: 'REJECTED', quality_score: 0.14, item_count: 0, timestamp: 0, reject_reason: 'BELOW_QUALITY_THRESHOLD', degraded: false, attempts: 3 }
      ]
    },
    VISUAL: {
      type: 'VISUAL', name: '视觉', icon: '👁️', status: 'DEGRADED', isolated: false, available: true,
      collect_count: 42, accepted_count: 38, rejected_count: 4, item_count: 38,
      quality_pass_rate: 90.48, rate_per_minute: 3.5, last_record_text: '2026-09-12 09:41:00',
      recent_batches: [
        { batch_id: 'b-8a02', staging_status: 'ACCEPTED', quality_score: 0.88, item_count: 1, timestamp: 0, reject_reason: null, degraded: false, attempts: 1 },
        { batch_id: 'b-8a01', staging_status: 'REJECTED', quality_score: 0.21, item_count: 0, timestamp: 0, reject_reason: 'OCR returned empty text', degraded: false, attempts: 1 }
      ]
    },
    AUDIO: { type: 'AUDIO', name: '听觉', icon: '👂', status: 'DOWN', isolated: true, available: false,
      collect_count: 0, accepted_count: 0, rejected_count: 0, item_count: 0, quality_pass_rate: 0,
      rate_per_minute: 0, last_record_text: '', recent_batches: [] },
    NOSE: { type: 'NOSE', name: '嗅觉', icon: '👃', status: 'DEGRADED', isolated: false, available: true,
      collect_count: 26, accepted_count: 22, rejected_count: 4, item_count: 22, quality_pass_rate: 84.62,
      rate_per_minute: 1.2, last_record_text: '2026-09-12 09:30:08',
      recent_batches: [{ batch_id: 'b-9c11', staging_status: 'ACCEPTED', quality_score: 0.81, item_count: 3, timestamp: 0, reject_reason: null, degraded: false, attempts: 1 }] },
    TASTE: { type: 'TASTE', name: '味觉', icon: '👅', status: 'UP', isolated: false, available: true,
      collect_count: 18, accepted_count: 18, rejected_count: 0, item_count: 152, quality_pass_rate: 100,
      rate_per_minute: 0.8, last_record_text: '2026-09-12 09:40:00',
      recent_batches: [{ batch_id: 'b-a120', staging_status: 'ACCEPTED', quality_score: 0.94, item_count: 20, timestamp: 0, reject_reason: null, degraded: false, attempts: 1 }] }
  };

  /* ── Mock 数据源 ─────────────────────────────────────────────────────── */
  class MockDataSource {
    constructor() { this.name = 'mock'; this.degraded = false; }

    async getSenseMatrix() { return structuredClone(MOCK_SENSE_MATRIX); }

    async getSenseStats() { return structuredClone(MOCK_SENSE_STATS); }

    async getSenseChannelDetail(type) {
      const detail = MOCK_SENSE_CHANNEL_DETAIL[String(type || '').toUpperCase()];
      if (!detail) throw new Error('unknown channel: ' + type);
      return structuredClone(detail);
    }

    /** Mock 模式下其余视图沿用 Console 静态测试数据（接口先行占位） */
    async getViewData(view) {
      return { view, source: 'mock', degraded: false, payload: null };
    }

    /**
     * 大脑视图（R-C04）Mock 数据。
     * `source='mock'` + `degraded=true`：**数字是演示值，不得被当作真实运行指标**。
     */
    async getBrainOverview() {
      return {
        source: 'mock', available: true, degraded: true,
        degraded_reasons: ['mock_data_source'],
        llm: { available: false, engines: [{ name: 'template', level: 'L1', available: true }],
               stats: { calls: 0, completion_tokens: 0 } },
        semantic_cache: { backend: 'memory', degraded: true, stats: { lookups: 0, hits: 0, hit_rate: 0 } },
        retrieval: { backend: 'body-service', degraded: true, chunks: 0 },
        model_runtime: [
          { node: 'llm_gateway', state: 'mock', calls: 0 },
          { node: 'semantic_cache', state: 'mock', calls: 0 },
          { node: 'retrieval', state: 'mock', calls: 0 },
        ],
        sessions: { source: '', note: 'Mock 数据源：会话真相需切 ?ds=api 后由 session-manager 提供' },
      };
    }

    /** 交互终端（R-C04）Mock 问答：明确标注为演示答复，不冒充模型输出 */
    async askBrain(question, sessionId, context) {
      return {
        available: true, source: 'mock', question,
        answer: `（演示答复 · 未调用大脑层）已收到：${question}。切到 ?ds=api&bff=http://127.0.0.1:8090 后将走真实检索增强链路。`,
        generator: 'mock', degraded: true, degraded_reasons: ['mock_data_source'],
        sources: [], gap: { has_gap: true, usable_chunks: 0, coverage: 0 },
        chain: [], decision_id: '',
      };
    }
  }

  /* ── API 数据源（经网关访问真实服务）────────────────────────────────── */
  class ApiDataSource {
    constructor(base, tenantId, bffBase) {
      this.name = 'api';
      this.base = base.replace(/\/$/, '');
      this.bffBase = String(bffBase || 'http://127.0.0.1:8090').replace(/\/$/, '');
      this.tenantId = tenantId;
      this.token = null;
      this.degraded = false;
      this.lastError = null;
    }

    async ensureToken() {
      if (this.token) return this.token;
      const response = await fetch(`${this.base}/api/auth/token?tenantId=${encodeURIComponent(this.tenantId)}`, { method: 'POST' });
      if (!response.ok) throw new Error(`token endpoint HTTP ${response.status}（开发令牌端点需 DEV_TOKEN_ENDPOINT_ENABLED=true）`);
      const body = await response.json();
      this.token = body.token;
      return this.token;
    }

    async request(path, options) {
      const token = await this.ensureToken();
      const opts = options || {};
      const response = await fetch(`${this.base}${path}`, Object.assign({}, opts, {
        headers: Object.assign({
          Authorization: `Bearer ${token}`,
          'Accept': 'application/json',
          // 多租户隔离：后端按 X-Tenant-Id 分区暂存与指标，缺失则退化为 default
          'X-Tenant-Id': this.tenantId
        }, opts.headers || {})
      }));
      if (!response.ok) throw new Error(`${path} HTTP ${response.status}`);
      return response.json();
    }

    // ── 感官视图（Phase 2 真实数据）──
    async getSenseMatrix() { return this.request('/api/sense/channels'); }

    async getSenseStats() { return this.request('/api/sense/stats'); }

    async getSenseChannelDetail(type) {
      return this.request(`/api/sense/channels/${encodeURIComponent(String(type).toUpperCase())}`);
    }

    async getSenseBatches(status) {
      const query = status ? `?status=${encodeURIComponent(status)}&limit=20` : '?limit=20';
      return this.request(`/api/sense/batches${query}`);
    }

    async rollbackBatch(batchId) {
      return this.request(`/api/sense/batches/${encodeURIComponent(batchId)}/rollback`, { method: 'POST' });
    }

    async collect(descriptor) {
      return this.request('/api/sense/collect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(descriptor || {})
      });
    }

    /** 其余视图接口尚未开发 → 回落 Mock 并显式标记 degraded */
    async getViewData(view) {
      this.degraded = true;
      return { view, source: 'api', degraded: true, payload: null,
               message: `${view} 视图真实接口尚未开发（Phase 3+），当前回落 Mock 数据` };
    }

    /* ── 大脑视图 / 交互终端（R-C04，Phase 4）────────────────────────────
     * 走 wp-bff（BFF_BASE）而非网关：这两个端点不需要控制令牌。
     * 与网关请求的区别仅在 base 与鉴权头，返回结构与 Mock 同源（snake_case）。
     */
    async requestBff(path, options) {
      const opts = options || {};
      const response = await fetch(`${this.bffBase}${path}`, Object.assign({}, opts, {
        headers: Object.assign({
          'Accept': 'application/json',
          'X-Tenant-Id': this.tenantId
        }, opts.headers || {})
      }));
      if (!response.ok) throw new Error(`${path} HTTP ${response.status}`);
      const body = await response.json();
      // BFF 统一信封 {code,message,data} —— 视图只消费 data
      return body && typeof body === 'object' && 'data' in body ? body.data : body;
    }

    async getBrainOverview() {
      const data = await this.requestBff('/api/wp/brain');
      return Object.assign({ source: 'api' }, data);
    }

    async askBrain(question, sessionId, context) {
      const data = await this.requestBff('/api/wp/brain/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          question,
          session_id: String(sessionId || ''),
          intent: '',
          context: Array.isArray(context) ? context : [],
        })
      });
      return Object.assign({ source: 'api' }, data);
    }
  }

  const provider = DATA_SOURCE === 'api'
    ? new ApiDataSource(API_BASE, TENANT_ID, BFF_BASE)
    : new MockDataSource();

  global.ConsoleDataProvider = {
    source: provider,
    dataSource: DATA_SOURCE,
    apiBase: API_BASE,
    bffBase: BFF_BASE,
    tenantId: TENANT_ID,
    mock: MOCK_SENSE_MATRIX
  };
})(window);
