# Phase 1 / VS1 Demo: Gateway -> Session -> Intent -> Local Retrieval

## Prerequisites

1. Start infrastructure and services: `./scripts/start.sh all`
2. Ensure `JWT_SECRET` is set by the start script and `DEV_TOKEN_ENDPOINT_ENABLED=true`.
3. Run `./scripts/healthcheck.sh` and confirm HTTP health plus gRPC ports 9091-9094.

## Demo

```bash
# 1. get a dev token (loopback only)
curl -s -X POST "http://127.0.0.1:8080/api/auth/token?tenantId=default"

# 2. create a session
curl -s -X POST "http://127.0.0.1:8080/api/session" \
  -H "Authorization: Bearer <TOKEN>"

# 3. ingest one local knowledge document
curl -s -X POST "http://127.0.0.1:8080/api/body/ingest" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"doc_id":"doc-1","title":"架构说明","content":"Agent-Lifeform uses gateway session nlp body services","source":"demo"}'

# 4. ask through the vertical slice
curl -s -X POST "http://127.0.0.1:8080/api/session/<SESSION_ID>/ask" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"question":"Agent-Lifeform 的架构是什么"}'
```

Expected:

- Session API returns `session_id`.
- Intent API returns a rule-based intent.
- Body API returns at least one chunk for the ingested document.
- Ask API returns `intent`, `answer`, and `citations`.

## Negative checks

```bash
# missing token => 401
curl -i -X POST "http://127.0.0.1:8080/api/session"

# cross-tenant session read => 403
curl -i "http://127.0.0.1:8080/api/session/<SESSION_ID>" \
  -H "Authorization: Bearer <TOKEN_FOR_OTHER_TENANT>"

# loopback SSRF => rejected
curl -s -X POST "http://127.0.0.1:8082/api/sense/collect" \
  -H "X-Tenant-Id: default" -H "Content-Type: application/json" \
  -d '{"data_source":"http://127.0.0.1:8080/actuator/health"}'
```
