--
-- PostgreSQL database dump
--

\restrict QkD2AC3i5hw9UQxGgFKNo6x5ZvB92kl6cDe5ZJAQxUMwY2eJOJOvYKYVw8egQev

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
-- Name: knowledge_chunk; Type: TABLE; Schema: public; Owner: agent
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


ALTER TABLE public.knowledge_chunk OWNER TO agent;

--
-- Name: knowledge_document; Type: TABLE; Schema: public; Owner: agent
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


ALTER TABLE public.knowledge_document OWNER TO agent;

--
-- Name: knowledge_chunk knowledge_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: agent
--

ALTER TABLE ONLY public.knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_pkey PRIMARY KEY (chunk_id);


--
-- Name: knowledge_document knowledge_document_pkey; Type: CONSTRAINT; Schema: public; Owner: agent
--

ALTER TABLE ONLY public.knowledge_document
    ADD CONSTRAINT knowledge_document_pkey PRIMARY KEY (doc_id);


--
-- Name: idx_knowledge_chunk_tenant; Type: INDEX; Schema: public; Owner: agent
--

CREATE INDEX idx_knowledge_chunk_tenant ON public.knowledge_chunk USING btree (tenant_id, doc_id, chunk_index);


--
-- Name: idx_knowledge_document_tenant; Type: INDEX; Schema: public; Owner: agent
--

CREATE INDEX idx_knowledge_document_tenant ON public.knowledge_document USING btree (tenant_id, updated_at DESC);


--
-- PostgreSQL database dump complete
--

\unrestrict QkD2AC3i5hw9UQxGgFKNo6x5ZvB92kl6cDe5ZJAQxUMwY2eJOJOvYKYVw8egQev

