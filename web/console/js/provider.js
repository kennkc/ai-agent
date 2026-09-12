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
 * ═══════════════════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const params = new URLSearchParams(global.location.search);
  const DATA_SOURCE = (params.get('ds') || global.localStorage.getItem('console_data_source') || 'mock').toLowerCase();
  const API_BASE = params.get('api') || global.localStorage.getItem('console_api_base') || 'http://127.0.0.1:8080';
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
  }

  /* ── API 数据源（经网关访问真实服务）────────────────────────────────── */
  class ApiDataSource {
    constructor(base, tenantId) {
      this.name = 'api';
      this.base = base.replace(/\/$/, '');
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
  }

  const provider = DATA_SOURCE === 'api'
    ? new ApiDataSource(API_BASE, TENANT_ID)
    : new MockDataSource();

  global.ConsoleDataProvider = {
    source: provider,
    dataSource: DATA_SOURCE,
    apiBase: API_BASE,
    tenantId: TENANT_ID,
    mock: MOCK_SENSE_MATRIX
  };
})(window);
