--
-- PostgreSQL database dump
--

\restrict ubfuCvRX767awMauNBJ6NXhgyfeSosFVEsYh16sHD4N4VMUVX9WWWxERcjr0D9E

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

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: brain_decision_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.brain_decision_log (
    decision_id text NOT NULL,
    tenant_id text NOT NULL,
    session_id text DEFAULT ''::text,
    question text NOT NULL,
    intent text DEFAULT ''::text,
    answer text DEFAULT ''::text,
    generator text DEFAULT ''::text,
    model text DEFAULT ''::text,
    degraded boolean DEFAULT false,
    confidence real DEFAULT 0,
    payload jsonb NOT NULL,
    created_at double precision NOT NULL,
    created_at_iso text DEFAULT ''::text
);


--
-- Name: knowledge_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_chunk (
    chunk_id character varying(96) NOT NULL,
    doc_id character varying(64) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    chunk_index integer,
    heading character varying(512),
    content text,
    char_count integer,
    vector_backend character varying(64),
    created_at bigint
);


--
-- Name: knowledge_document; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_document (
    doc_id character varying(64) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    title character varying(512),
    source character varying(256),
    char_count integer DEFAULT 0,
    chunk_count integer DEFAULT 0,
    status character varying(32) NOT NULL,
    backend character varying(64),
    created_at bigint,
    updated_at bigint,
    error character varying(1024)
);


--
-- Name: llm_model_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.llm_model_config (
    id bigint NOT NULL,
    tenant_id character varying(64) DEFAULT 'default'::character varying NOT NULL,
    config_key character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    provider character varying(64) DEFAULT 'custom'::character varying NOT NULL,
    base_url character varying(512) DEFAULT ''::character varying NOT NULL,
    model character varying(160) DEFAULT ''::character varying NOT NULL,
    api_key_cipher text DEFAULT ''::text NOT NULL,
    api_key_hint character varying(64) DEFAULT ''::character varying NOT NULL,
    tier character varying(4) DEFAULT 'L2'::character varying NOT NULL,
    max_tokens integer DEFAULT 512 NOT NULL,
    temperature numeric(3,2) DEFAULT 0.20 NOT NULL,
    timeout_ms integer DEFAULT 8000 NOT NULL,
    routing_weight integer DEFAULT 100 NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    extra jsonb DEFAULT '{}'::jsonb NOT NULL,
    last_probe_at timestamp with time zone,
    last_probe_ok boolean,
    last_probe_latency_ms integer,
    last_probe_error character varying(512) DEFAULT ''::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64) DEFAULT ''::character varying NOT NULL
);


--
-- Name: llm_model_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.llm_model_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: llm_model_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.llm_model_config_id_seq OWNED BY public.llm_model_config.id;


--
-- Name: memory_entity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_entity (
    id text NOT NULL,
    tenant_id text NOT NULL,
    name text NOT NULL,
    kind text DEFAULT 'concept'::text,
    aliases jsonb DEFAULT '[]'::jsonb,
    mentions integer DEFAULT 1,
    created_at double precision NOT NULL,
    updated_at double precision NOT NULL
);


--
-- Name: memory_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_relation (
    id text NOT NULL,
    tenant_id text NOT NULL,
    source_id text NOT NULL,
    target_id text NOT NULL,
    rel text NOT NULL,
    weight real DEFAULT 1.0,
    evidence text DEFAULT ''::text,
    created_at double precision NOT NULL,
    updated_at double precision NOT NULL
);


--
-- Name: tool_audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool_audit_log (
    audit_id character varying(64) NOT NULL,
    call_id character varying(64) NOT NULL,
    tenant_id character varying(64) NOT NULL,
    tool_name character varying(96) NOT NULL,
    tool_version character varying(32),
    args_summary character varying(2048),
    success boolean NOT NULL,
    output text,
    error_code character varying(64),
    error_message character varying(1024),
    latency_ms bigint,
    sandboxed boolean,
    sandbox_backend character varying(48),
    degraded boolean,
    requested_by character varying(96),
    created_at bigint
);


--
-- Name: llm_model_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.llm_model_config ALTER COLUMN id SET DEFAULT nextval('public.llm_model_config_id_seq'::regclass);


--
-- Name: brain_decision_log brain_decision_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.brain_decision_log
    ADD CONSTRAINT brain_decision_log_pkey PRIMARY KEY (decision_id);


--
-- Name: knowledge_chunk knowledge_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_pkey PRIMARY KEY (chunk_id);


--
-- Name: knowledge_document knowledge_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_document
    ADD CONSTRAINT knowledge_document_pkey PRIMARY KEY (doc_id);


--
-- Name: llm_model_config llm_model_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.llm_model_config
    ADD CONSTRAINT llm_model_config_pkey PRIMARY KEY (id);


--
-- Name: memory_entity memory_entity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_entity
    ADD CONSTRAINT memory_entity_pkey PRIMARY KEY (id);


--
-- Name: memory_entity memory_entity_tenant_id_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_entity
    ADD CONSTRAINT memory_entity_tenant_id_name_key UNIQUE (tenant_id, name);


--
-- Name: memory_relation memory_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_relation
    ADD CONSTRAINT memory_relation_pkey PRIMARY KEY (id);


--
-- Name: memory_relation memory_relation_tenant_id_source_id_target_id_rel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_relation
    ADD CONSTRAINT memory_relation_tenant_id_source_id_target_id_rel_key UNIQUE (tenant_id, source_id, target_id, rel);


--
-- Name: tool_audit_log tool_audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_audit_log
    ADD CONSTRAINT tool_audit_log_pkey PRIMARY KEY (audit_id);


--
-- Name: idx_brain_decision_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_brain_decision_tenant_time ON public.brain_decision_log USING btree (tenant_id, created_at DESC);


--
-- Name: idx_knowledge_chunk_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_chunk_tenant ON public.knowledge_chunk USING btree (tenant_id, doc_id, chunk_index);


--
-- Name: idx_knowledge_document_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_knowledge_document_tenant ON public.knowledge_document USING btree (tenant_id, updated_at DESC);


--
-- Name: idx_llm_model_config_tenant_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_llm_model_config_tenant_role ON public.llm_model_config USING btree (tenant_id, config_key, enabled);


--
-- Name: idx_memory_relation_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_memory_relation_tenant ON public.memory_relation USING btree (tenant_id, source_id);


--
-- Name: idx_tool_audit_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_audit_tenant ON public.tool_audit_log USING btree (tenant_id, created_at DESC);


--
-- Name: uq_llm_model_config_tenant_role_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_llm_model_config_tenant_role_name ON public.llm_model_config USING btree (tenant_id, config_key, name);


--
-- PostgreSQL database dump complete
--

\unrestrict ubfuCvRX767awMauNBJ6NXhgyfeSosFVEsYh16sHD4N4VMUVX9WWWxERcjr0D9E

