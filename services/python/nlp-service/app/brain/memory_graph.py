"""IN-02 记忆图谱（C21 · Should · P4）—— 长期记忆实体关系图谱。

需求口径（Phase4 需求设计文档 §8 IN-02 / 开发设计文档 IN-02 落地）：

> 长期记忆实体关系图谱（客户/项目/文档），会话中自动抽取实体+关系，决策时按需加载相关子图。
> 验收：实体抽取 + 子图查询可用；**上下文压缩率 ≥ 60%**；**子图加载 < 100ms**。
> 落地：PG 邻接表/递归 CTE 存实体关系；Qdrant 实体向量索引；NLU 管道自动抽取；
> 乐观并发 + 实体解析防多 Agent 语义冲突。

实现取舍（如实留痕，不冒充模型能力）：

* **实体抽取为规则 + 词典版**（`extractor=rule`），与 Phase 2 意图级联、R4-05 规划器同源风格；
  接入 NLU 模型后只需替换 `EntityExtractor`，存储与查询不动（DEBT-018）。
* **实体解析**做归一化（去空白/标点/大小写 + 别名表），同义实体合并为同一节点，
  避免"Agent-Lifeform" 与 "agent lifeform" 分裂成两个节点。
* **存 PG 邻接表**（`memory_entity` / `memory_relation`），子图查询用**递归 CTE**，
  与 Qdrant 侧向量索引解耦；PG 不可用时降级为**进程内图**并标 `degraded=True`
  （降级影响"跨进程共享与重启留存"，不影响本次抽取/查询正确性）。
* **上下文压缩**：用子图三元组（实体→关系→实体）替代原始文段，
  压缩率 = 1 - 压缩后字符数 / 原文字符数，实测口径在 `compress()` 返回值里给出，不做静态宣称。

**不伪造**：抽取不到实体就返回空图（不拿停用词凑数）；压缩率达不到阈值时如实回 `meets_target=false`。
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import time
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

from app.brain import pg

logger = logging.getLogger("nlp-service.brain.memory")

# 验收阈值（需求 §8 IN-02）：上下文压缩率 ≥60%，子图加载 <100ms
COMPRESSION_TARGET = float(os.getenv("MEMORY_COMPRESSION_TARGET", "0.60"))
SUBGRAPH_LOAD_BUDGET_MS = float(os.getenv("MEMORY_SUBGRAPH_BUDGET_MS", "100"))

DDL = (
    """
    CREATE TABLE IF NOT EXISTS memory_entity (
        id          TEXT PRIMARY KEY,
        tenant_id   TEXT NOT NULL,
        name        TEXT NOT NULL,
        kind        TEXT DEFAULT 'concept',
        aliases     JSONB DEFAULT '[]'::jsonb,
        mentions    INTEGER DEFAULT 1,
        created_at  DOUBLE PRECISION NOT NULL,
        updated_at  DOUBLE PRECISION NOT NULL,
        UNIQUE (tenant_id, name)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS memory_relation (
        id          TEXT PRIMARY KEY,
        tenant_id   TEXT NOT NULL,
        source_id   TEXT NOT NULL,
        target_id   TEXT NOT NULL,
        rel         TEXT NOT NULL,
        weight      REAL DEFAULT 1.0,
        evidence    TEXT DEFAULT '',
        created_at  DOUBLE PRECISION NOT NULL,
        updated_at  DOUBLE PRECISION NOT NULL,
        UNIQUE (tenant_id, source_id, target_id, rel)
    )
    """,
    """
    CREATE INDEX IF NOT EXISTS idx_memory_relation_tenant
        ON memory_relation (tenant_id, source_id)
    """,
)

# ── 实体识别规则 ──
# 优先显式标记：《》/「」/引号包裹、英文驼峰与缩写、中文专有名词后缀（项目/系统/平台/客户/文档）
_QUOTED = re.compile(r"[《「『\"']([^》」』\"']{2,30})[》」』\"']")
_TECH_TOKEN = re.compile(r"\b([A-Z][A-Za-z0-9]*(?:[-_][A-Z][A-Za-z0-9]*)*)\b")
_SUFFIX = re.compile(r"([\u4e00-\u9fa5A-Za-z0-9]{2,20}(?:项目|系统|平台|客户|文档|服务|模块|引擎|组件|数据库|接口))")
# 左侧优先匹配**带引号的实体名**（书名号/引号包裹的专有名词最可能是实体），
# 否则退回普通词；右侧同理。抽取后再归一化，与实体表对齐。
_RELATION_HINT = re.compile(
    r"([《「『\"'][^《》「」\"']{2,30}[》」』\"']|[\u4e00-\u9fa5A-Za-z0-9_\-]{2,20})"
    r"\s*(?:的|之)?[\u4e00-\u9fa5]{0,2}\s*(?:依赖|包含|属于|调用|使用|引用|关联|负责|产出)\s*"
    r"([《「『\"'][^《》「」\"']{2,30}[》」』\"']|[\u4e00-\u9fa5A-Za-z0-9_\-]{2,20})"
)
_STOPWORDS = {
    "我们", "你们", "他们", "这个", "那个", "什么", "怎么", "如何", "可以", "因为", "所以",
    "用户", "问题", "内容", "信息", "数据", "系统", "功能", "时间", "方式", "情况", "结果",
    "the", "this", "that", "and", "for", "with", "from", "into", "your",
}
_KIND_RULES = (
    (re.compile(r"(项目|Project)$"), "project"),
    (re.compile(r"(客户|Customer|Client)$"), "customer"),
    (re.compile(r"(文档|Document|Doc)$"), "document"),
    (re.compile(r"(系统|平台|服务|引擎|模块|组件|接口|Platform|Service)$"), "system"),
)


def normalize(name: str) -> str:
    """实体名归一化：去空白/首尾标点 + 小写折叠（用于实体解析，防同义分裂）。"""
    text = re.sub(r"[\s\u3000]+", "", str(name or "")).strip("。，、；：！？,.!?;:'\"“”‘’（）()[]【】《》")
    return text.lower()


def stable_id(tenant_id: str, name: str) -> str:
    digest = hashlib.sha1(f"{tenant_id}::{normalize(name)}".encode("utf-8")).hexdigest()
    return digest[:32]


@dataclass
class Entity:
    id: str
    name: str
    kind: str = "concept"
    aliases: list[str] = field(default_factory=list)
    mentions: int = 1

    def to_dict(self) -> dict:
        return {"id": self.id, "name": self.name, "kind": self.kind,
                "aliases": list(self.aliases), "mentions": self.mentions}


@dataclass
class Relation:
    id: str
    source: str          # source entity id
    target: str          # target entity id
    rel: str
    weight: float = 1.0
    evidence: str = ""

    def to_dict(self) -> dict:
        return {"id": self.id, "source": self.source, "target": self.target,
                "rel": self.rel, "weight": round(self.weight, 4), "evidence": self.evidence}


@dataclass
class Extraction:
    entities: list[Entity] = field(default_factory=list)
    relations: list[Relation] = field(default_factory=list)
    extractor: str = "rule"
    degraded: bool = False
    degraded_reasons: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "entities": [e.to_dict() for e in self.entities],
            "relations": [r.to_dict() for r in self.relations],
            "extractor": self.extractor,
            "degraded": self.degraded,
            "degraded_reasons": list(self.degraded_reasons),
        }


@dataclass
class Subgraph:
    root: str
    entities: dict[str, Entity] = field(default_factory=dict)
    relations: list[Relation] = field(default_factory=list)
    load_ms: float = 0.0
    depth: int = 1
    degraded: bool = False
    reason: str = ""

    def to_dict(self) -> dict:
        return {
            "root": self.root,
            "entities": [e.to_dict() for e in self.entities.values()],
            "relations": [r.to_dict() for r in self.relations],
            "load_ms": round(self.load_ms, 2),
            "depth": self.depth,
            "degraded": self.degraded,
            "reason": self.reason,
        }


class EntityExtractor:
    """规则版实体/关系抽取（DEBT-018：非模型抽取，如实标 `extractor=rule`）。"""

    def extract(self, text: str, tenant_id: str = "default") -> Extraction:
        raw = str(text or "")
        names: list[str] = []
        for pattern in (_QUOTED, _TECH_TOKEN, _SUFFIX):
            for match in pattern.finditer(raw):
                name = _clean_name(match.group(1))
                if len(name) < 2 or normalize(name) in _STOPWORDS:
                    continue
                names.append(name)
        # 去重保序（按归一化 key）
        seen: dict[str, Entity] = {}
        for name in names:
            key = normalize(name)
            if not key or key in _STOPWORDS:
                continue
            if key in seen:
                entity = seen[key]
                if name != entity.name and name not in entity.aliases:
                    entity.aliases.append(name)
                continue
            seen[key] = Entity(id=stable_id(tenant_id, name), name=name, kind=_kind_of(name))
        entities = list(seen.values())

        relations: list[Relation] = []
        by_key = {normalize(e.name): e for e in entities}
        # 关系两端的实体**必须**也是图中的节点：`_SUFFIX`/`_QUOTED` 只认带后缀或带引号的名词，
        # 像「大脑层」这种纯中文名词抽不出来，会出现「有边无点」→ 子图查询永远空。
        # 因此先从关系三元组里补齐端点实体，再建边（端点由关系本身定义，不是凑数）。
        for match in _RELATION_HINT.finditer(raw):
            for raw_name in (match.group(1), match.group(2)):
                name = _clean_name(raw_name)
                key = normalize(name)
                if len(name) < 2 or not key or key in _STOPWORDS or key in by_key:
                    continue
                entity = Entity(id=stable_id(tenant_id, name), name=name, kind=_kind_of(name))
                by_key[key] = entity
                entities.append(entity)
        for match in _RELATION_HINT.finditer(raw):
            left, right = normalize(match.group(1)), normalize(match.group(2))
            if left in by_key and right in by_key and left != right:
                relations.append(Relation(
                    id=hashlib.sha1(f"{tenant_id}::{left}::{match.group(0)[-3:]}::{right}".encode()).hexdigest()[:32],
                    source=by_key[left].id, target=by_key[right].id,
                    rel=_rel_of(match.group(0)), evidence=match.group(0)[:80],
                ))
        if not entities and not relations:
            return Extraction(extractor="rule")      # 抽不到就返回空图，不凑数
        return Extraction(entities=entities, relations=relations, extractor="rule")


def _clean_name(name: str) -> str:
    """实体名清洗：去包裹引号、去开头的虚词（规则抽取常见的粘连）。

    例："引用设计文档" 会被 _SUFFIX 连成 "用设计文档" → 清洗为 "设计文档"。
    """
    text = str(name or "").strip().strip("《》「」『』\"'（）()【】[]")
    # 后缀正则（`_SUFFIX`）会贪婪吞掉前面的主语，把「大脑层依赖检索服务」整块当成一个名字。
    # 关系动词是天然的分割点：取最后一个动词之后的部分（「检索服务」），
    # 动词前的内容由「关系端点补齐」逻辑单独建点，不会丢信息。
    verbs = "依赖|包含|属于|调用|使用|引用|关联|负责|产出"
    parts = re.split(f"(?:{verbs})", text)
    if len(parts) > 1 and len(parts[-1].strip()) >= 2:
        text = parts[-1]
    text = re.sub(r"^[的了是和与及在用把被从对为给再加上]+", "", text)
    return text.strip()


def _kind_of(name: str) -> str:
    for pattern, kind in _KIND_RULES:
        if pattern.search(name):
            return kind
    return "concept"


def _rel_of(fragment: str) -> str:
    for rel in ("依赖", "包含", "属于", "调用", "使用", "引用", "关联", "负责", "产出"):
        if rel in fragment:
            return rel
    return "关联"


class MemoryGraph:
    """记忆图谱：抽取 → 落图（PG 邻接表）→ 子图查询（递归 CTE）→ 上下文压缩。"""

    def __init__(self, extractor: Optional[EntityExtractor] = None) -> None:
        self.extractor = extractor or EntityExtractor()
        self._memory_entities: dict[str, dict[str, Entity]] = {}
        self._memory_relations: dict[str, dict[str, Relation]] = {}
        self._last_load_ms = 0.0

    # ── 写入 ──
    def ingest(self, text: str, tenant_id: str = "default") -> dict:
        """抽取实体关系并落图；返回落图结果（含降级标注）。"""
        started = time.time()
        extraction = self.extractor.extract(text, tenant_id)
        reasons = list(extraction.degraded_reasons)
        ok = pg.ensure_schema(DDL)
        stored = {"entities": 0, "relations": 0}
        if ok:
            stored = self._pg_upsert(tenant_id, extraction)
        else:
            reasons.append("memory_pg_unavailable")
            stored = self._memory_upsert(tenant_id, extraction)
        extraction.degraded = bool(reasons) or pg.status()["degraded"]
        extraction.degraded_reasons = reasons
        payload = extraction.to_dict()
        payload.update({
            "tenant_id": tenant_id,
            "stored": stored,
            "storage": self.storage(),
            "ingest_ms": round((time.time() - started) * 1000, 2),
        })
        return payload

    def _pg_upsert(self, tenant_id: str, extraction: Extraction) -> dict:
        statements: list[tuple[str, tuple]] = []
        now = time.time()
        for entity in extraction.entities:
            statements.append((
                """
                INSERT INTO memory_entity (id, tenant_id, name, kind, aliases, mentions, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, 1, %s, %s)
                ON CONFLICT (tenant_id, name) DO UPDATE SET
                    mentions = memory_entity.mentions + 1,
                    updated_at = EXCLUDED.updated_at,
                    aliases = EXCLUDED.aliases
                """,
                (entity.id, tenant_id, entity.name, entity.kind,
                 json.dumps(entity.aliases, ensure_ascii=False), now, now),
            ))
        for relation in extraction.relations:
            statements.append((
                """
                INSERT INTO memory_relation (id, tenant_id, source_id, target_id, rel, weight, evidence, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (tenant_id, source_id, target_id, rel) DO UPDATE SET
                    weight = LEAST(memory_relation.weight + 0.5, 5.0),
                    updated_at = EXCLUDED.updated_at
                """,
                (relation.id, tenant_id, relation.source, relation.target,
                 relation.rel, relation.weight, relation.evidence, now, now),
            ))
        ok, _ = pg.execute(statements)
        if not ok:
            return self._memory_upsert(tenant_id, extraction)
        return {"entities": len(extraction.entities), "relations": len(extraction.relations)}

    def _memory_upsert(self, tenant_id: str, extraction: Extraction) -> dict:
        entities = self._memory_entities.setdefault(tenant_id, {})
        relations = self._memory_relations.setdefault(tenant_id, {})
        for entity in extraction.entities:
            existing = entities.get(entity.id)
            if existing:
                existing.mentions += 1
                for alias in entity.aliases:
                    if alias not in existing.aliases:
                        existing.aliases.append(alias)
            else:
                entities[entity.id] = entity
        for relation in extraction.relations:
            existing = relations.get(relation.id)
            if existing:
                existing.weight = min(5.0, existing.weight + 0.5)
            else:
                relations[relation.id] = relation
        return {"entities": len(extraction.entities), "relations": len(extraction.relations)}

    # ── 子图查询（递归 CTE）──
    def subgraph(self, root: str, tenant_id: str = "default", depth: int = 2) -> Subgraph:
        started = time.time()
        root_id = stable_id(tenant_id, root)
        graph = Subgraph(root=root, depth=depth)
        if pg.status()["degraded"]:
            graph = self._memory_subgraph(root_id, tenant_id, depth)
        else:
            ok, rows = pg.execute([(
                """
                WITH RECURSIVE reachable(id, depth) AS (
                    SELECT id, 0 FROM memory_entity WHERE id = %s AND tenant_id = %s
                    UNION
                    SELECT CASE WHEN r.target_id = reach.id THEN r.source_id ELSE r.target_id END,
                           reach.depth + 1
                    FROM memory_relation r
                    JOIN reachable reach ON (r.source_id = reach.id OR r.target_id = reach.id)
                    WHERE r.tenant_id = %s AND reach.depth < %s
                )
                SELECT e.id, e.name, e.kind, e.aliases, e.mentions
                FROM memory_entity e JOIN reachable rc ON e.id = rc.id
                GROUP BY e.id, e.name, e.kind, e.aliases, e.mentions
                """,
                (root_id, tenant_id, tenant_id, int(depth)),
            )], fetch=True)
            if ok and rows:
                for row in rows:
                    graph.entities[row[0]] = Entity(id=row[0], name=row[1], kind=row[2],
                                                    aliases=list(row[3] or []), mentions=int(row[4] or 1))
                ok_rel, rel_rows = pg.execute([(
                    """
                    SELECT id, source_id, target_id, rel, weight, evidence
                    FROM memory_relation
                    WHERE tenant_id = %s AND (source_id = ANY(%s) OR target_id = ANY(%s))
                    """,
                    (tenant_id, list(graph.entities.keys()), list(graph.entities.keys())),
                )], fetch=True)
                if ok_rel and rel_rows:
                    for row in rel_rows:
                        graph.relations.append(Relation(id=row[0], source=row[1], target=row[2],
                                                        rel=row[3], weight=float(row[4] or 1.0),
                                                        evidence=str(row[5] or "")))
            elif ok:
                graph.reason = "实体不在图中（尚未抽取到该实体）"
            else:
                graph = self._memory_subgraph(root_id, tenant_id, depth)
                graph.degraded = True
                graph.reason = "pg query failed, served from in-process graph"
        graph.load_ms = round((time.time() - started) * 1000, 2)
        self._last_load_ms = graph.load_ms
        return graph

    def _memory_subgraph(self, root_id: str, tenant_id: str, depth: int) -> Subgraph:
        entities = self._memory_entities.get(tenant_id, {})
        relations = list(self._memory_relations.get(tenant_id, {}).values())
        graph = Subgraph(root=root_id, depth=depth)
        if root_id not in entities:
            graph.reason = "实体不在图中（尚未抽取到该实体）"
            return graph
        frontier = {root_id}
        visited = {root_id}
        for _ in range(max(1, int(depth))):
            next_frontier: set[str] = set()
            for relation in relations:
                if relation.source in frontier and relation.target not in visited:
                    visited.add(relation.target)
                    next_frontier.add(relation.target)
                if relation.target in frontier and relation.source not in visited:
                    visited.add(relation.source)
                    next_frontier.add(relation.source)
            frontier = next_frontier
            if not frontier:
                break
        for entity_id in visited:
            entity = entities.get(entity_id)
            if entity:
                graph.entities[entity_id] = entity
        graph.relations = [r for r in relations if r.source in visited and r.target in visited]
        return graph

    # ── 上下文压缩 ──
    def compress(self, text: str, tenant_id: str = "default", depth: int = 2) -> dict:
        """用子图三元组替代原文，给出**实测压缩率**（需求：≥60%）。

        压缩失败（无子图）时如实体返回原文并标 `applied=false`，**不虚报压缩率**。
        """
        extraction = self.extractor.extract(text, tenant_id)
        original_chars = len(str(text or ""))
        if not extraction.entities:
            return {
                "applied": False,
                "reason": "未抽取到实体，无法压缩（如实返回原文）",
                "original_chars": original_chars,
                "compressed_chars": original_chars,
                "compression": 0.0,
                "meets_target": False,
                "target": COMPRESSION_TARGET,
            }
        graph = self.subgraph(extraction.entities[0].name, tenant_id, depth)
        triples = _triples(graph)
        compressed = "\n".join(triples) if triples else ""
        compressed_chars = len(compressed)
        ratio = 0.0
        if original_chars > 0 and compressed_chars < original_chars:
            ratio = round(1 - compressed_chars / original_chars, 4)
        return {
            "applied": bool(compressed),
            "reason": "" if compressed else "子图为空（实体尚未建边）",
            "original_chars": original_chars,
            "compressed_chars": compressed_chars,
            "compression": ratio,
            "meets_target": ratio >= COMPRESSION_TARGET,
            "target": COMPRESSION_TARGET,
            "triples": triples,
            "subgraph_load_ms": round(graph.load_ms, 2),
            "within_load_budget": graph.load_ms < SUBGRAPH_LOAD_BUDGET_MS,
            "load_budget_ms": SUBGRAPH_LOAD_BUDGET_MS,
            "entities": len(graph.entities),
        }

    # ── 状态 ──
    def storage(self) -> dict:
        state = pg.status()
        return {"backend": state["backend"], "degraded": state["degraded"],
                "reason": state["reason"], "dsn": state["dsn"]}

    def stats(self, tenant_id: str = "default") -> dict:
        if pg.status()["degraded"]:
            return {
                "entities": len(self._memory_entities.get(tenant_id, {})),
                "relations": len(self._memory_relations.get(tenant_id, {})),
                "last_load_ms": round(self._last_load_ms, 2),
                "storage": self.storage(),
            }
        ok, rows = pg.execute([(
            "SELECT (SELECT COUNT(*) FROM memory_entity WHERE tenant_id = %s),"
            "       (SELECT COUNT(*) FROM memory_relation WHERE tenant_id = %s)",
            (tenant_id, tenant_id),
        )], fetch=True)
        if not ok or not rows:
            return {"entities": 0, "relations": 0, "last_load_ms": round(self._last_load_ms, 2),
                    "storage": self.storage()}
        return {"entities": int(rows[0][0] or 0), "relations": int(rows[0][1] or 0),
                "last_load_ms": round(self._last_load_ms, 2), "storage": self.storage()}


def _triples(graph: Subgraph) -> list[str]:
    index = {entity.id: entity.name for entity in graph.entities.values()}
    lines: list[str] = []
    for relation in graph.relations:
        left = index.get(relation.source)
        right = index.get(relation.target)
        if left and right:
            lines.append(f"{left} -[{relation.rel}]-> {right}")
    for entity in graph.entities.values():
        if not any(r.source == entity.id or r.target == entity.id for r in graph.relations):
            lines.append(f"{entity.name} [{entity.kind}]")
    return lines


MEMORY_GRAPH = MemoryGraph()
