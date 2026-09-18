# -*- coding: utf-8 -*-
"""导出 lifeform-redis 现存键快照 -> db/snapshots/redis-keys.json"""
import json, subprocess, sys

CONTAINER = "lifeform-redis"

def rc(*args):
    r = subprocess.run(["docker", "exec", CONTAINER, "redis-cli", *args],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout

keys = [k for k in rc("KEYS", "*").splitlines() if k.strip()]
out = {"exported_at": "2026-09-18T23:40:00+08:00", "container": CONTAINER, "key_count": len(keys), "keys": []}

for k in sorted(keys):
    typ = rc("TYPE", k).strip()
    ttl = rc("TTL", k).strip()
    if typ == "string":
        val = {"value": rc("GET", k).strip()}
    elif typ == "hash":
        val = {"fields": json.loads(rc("HGETALL", k))}
    elif typ == "list":
        val = {"values": rc("LRANGE", k, "0", "-1").splitlines()}
    elif typ == "set":
        val = {"members": sorted(rc("SMEMBERS", k).splitlines())}
    else:
        val = {"raw": rc("DUMP", k).strip()[:120]}
    out["keys"].append({"key": k, "type": typ, "ttl_seconds": int(ttl), **val})

path = "db/snapshots/redis-keys.json"
with open(path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print("keys exported:", len(keys))
# 落盘复验
d = json.load(open(path, encoding="utf-8"))
assert d["key_count"] == len(keys)
print("verify OK ->", path)
