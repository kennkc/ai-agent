--
-- PostgreSQL database dump
--

\restrict ahvDK3p3iuZpbh5QEm8M9YBOenae5cGOae2eMCyvV0kYHrlFKhjpdNgZoaa3UWe

-- Dumped from database version 16.15 (Debian 16.15-1.pgdg12+2)
-- Dumped by pg_dump version 16.15 (Debian 16.15-1.pgdg12+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: knowledge_chunk; Type: TABLE DATA; Schema: public; Owner: agent
--

INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('phase3-ingest#0', 'phase3-ingest', 'default', 0, '分块策略', '分块策略
文档按 Markdown 标题切分小节，小节内按空行切段落，段落累加至 800 字成块，
相邻块保留 50 字重叠尾巴，块首携带小节标题以保留上下文归属。', 82, 'hash-ngram-768', 1789732714294);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('phase3-ingest#1', 'phase3-ingest', 'default', 1, '状态机', '状态机
段落，段落累加至 800 字成块，
相邻块保留 50 字重叠尾巴，块首携带小节标题以保留上下文归属。
入库状态从 PENDING 开始，分块、嵌入、向量写入、元数据落库全部成功后置为 INDEXED；
任一步失败置为 FAILED 并记录原因，不允许出现"入库失败但显示成功"。', 143, 'hash-ngram-768', 1789732714294);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('tenant-b-doc#0', 'tenant-b-doc', 'tenant-b', 0, 'B 租户', 'B 租户
这是 tenant-b 的私有知识内容，不应被其他租户检索到。', 36, 'hash-ngram-768', 1789732714626);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('phase3-arch#0', 'phase3-arch', 'default', 0, '存储分层', '存储分层
躯体层采用三级存储：热层用 Redis 缓存高频问题，温层用 Qdrant 向量库承载语义召回，
冷层用 PostgreSQL 保存元数据真相。分层规则由访问频率与新鲜度共同决定，阈值可配置。', 101, 'hash-ngram-768', 1789732714817);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('phase3-arch#1', 'phase3-arch', 'default', 1, '检索链路', '检索链路
回，
冷层用 PostgreSQL 保存元数据真相。分层规则由访问频率与新鲜度共同决定，阈值可配置。
用户问题先查 Redis 缓存，未命中则调用嵌入服务向量化，在 Qdrant 中按租户过滤召回 TOP50，
再经重排模型取 TOP5。重排不可用时降级为按召回分排序，检索链路不中断。', 148, 'hash-ngram-768', 1789732714817);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('phase3-arch#2', 'phase3-arch', 'default', 2, '补充', '补充
户过滤召回 TOP50，
再经重排模型取 TOP5。重排不可用时降级为按召回分排序，检索链路不中断。
重入库应清理旧向量，避免重复召回。', 71, 'hash-ngram-768', 1789732714817);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('e2e-front#0', 'e2e-front', 'default', 0, '联通验证', '联通验证
本段用于验证工作平台前端经 BFF 到躯体层的写入链路。', 33, 'hash-ngram-768', 1789739033951);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('e2e-front#1', 'e2e-front', 'default', 1, '分层', '分层
本段用于验证工作平台前端经 BFF 到躯体层的写入链路。
热层 Redis、温层 Qdrant、冷层 PostgreSQL。', 65, 'hash-ngram-768', 1789739033951);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('e2e-align-001#0', 'e2e-align-001', 'default', 0, '', '�����洢�ֲ㣺�Ȳ� Redis���²� Qdrant �������⡢��� PostgreSQL ��ϵԪ��������Դ����㲻�洢������������ Qdrant ��ռ��', 99, 'hash-ngram-768', 1789739587926);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('audit-r2-001#0', 'audit-r2-001', 'default', 0, '', 'ͳһ�����ŷ⣺���з��񷵻� code/message/details ������·�ɲ������·�������ڷ��� 404��������֧�ַ��� 405 ���� Allow ͷ��ý�����Ͳ�֧�ַ��� 415����������ɼ������þ�Ĭα��ɹ���', 149, 'hash-ngram-768', 1789742049548);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('audit-r3-001#0', 'audit-r3-001', 'default', 0, '', '统一错误信封：所有服务返回 code/message/details 三键。路由层分流：路径不存在返回 404，方法不支持返回 405 并附 Allow 头，媒体类型不支持返回 415。降级必须可见，不得静默伪造成功。中文往返校验锚点：三级存储分层。', 125, 'hash-ngram-768', 1789742066206);
INSERT INTO public.knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content, char_count, vector_backend, created_at) VALUES ('audit-r4-001#0', 'audit-r4-001', 'default', 0, '', '统一错误信封：所有服务返回 code/message/details 三键。路由层分流：路径不存在 404，方法不支持 405，媒体类型 415。锚点词：三级存储分层。', 84, 'hash-ngram-768', 1789742081601);


--
-- Data for Name: knowledge_document; Type: TABLE DATA; Schema: public; Owner: agent
--

INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('phase3-ingest', 'default', '知识摄取管道', 'sense', 192, 2, 'INDEXED', NULL, 1789732714294, 1789732714332, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('tenant-b-doc', 'tenant-b', 'B 租户文档', 'manual', 38, 1, 'INDEXED', NULL, 1789732714626, 1789732714658, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('phase3-arch', 'default', '躯体层架构设计（v2）', 'manual', 241, 3, 'INDEXED', NULL, 1789732713879, 1789732714860, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('e2e-front', 'default', '前后端联通实测文档', 'work-platform', 78, 2, 'INDEXED', 'hash-ngram-768', 1789739033951, 1789739033951, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('e2e-align-001', 'default', '�ھ�������֤�ĵ�', 'e2e', 99, 1, 'INDEXED', 'hash-ngram-768', 1789739587926, 1789739587926, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('audit-r2-001', 'default', '�쳣���������֤�ĵ�', 'audit', 149, 1, 'INDEXED', 'hash-ngram-768', 1789742049548, 1789742049548, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('audit-r3-001', 'default', '异常流程审核验证文档', 'audit', 125, 1, 'INDEXED', 'hash-ngram-768', 1789742066206, 1789742066206, NULL);
INSERT INTO public.knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count, status, backend, created_at, updated_at, error) VALUES ('audit-r4-001', 'default', '异常流程审核验证文档·中文往返', 'audit', 84, 1, 'INDEXED', 'hash-ngram-768', 1789742081601, 1789742081601, NULL);


--
-- PostgreSQL database dump complete
--

\unrestrict ahvDK3p3iuZpbh5QEm8M9YBOenae5cGOae2eMCyvV0kYHrlFKhjpdNgZoaa3UWe

