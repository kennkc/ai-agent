# 优化日志 · 2026-09-20 GitHub Actions 门禁落地与 proto 校验修复

> **触发**：项目评估发现「13 个 CI job 齐备却从未拦住任何问题」
> **根因**：远端托管在 GitHub，流水线却只写在 .gitlab-ci.yml —— GitHub 侧不会执行
> **范围**：CI 托管迁移 · proto 同步校验修复 · 忽略规则与版本声明

---

## 1. 根因：CI 定义与托管平台不匹配

```text
远端仓库           : git@github.com:kennkc/ai-agent.git   （GitHub）
CI 配置            : .gitlab-ci.yml                        （GitLab CI）
.github/workflows  : 不存在                                ← GitHub 无任何流水线
```

这解释了此前的现象：ruff 报 223 项、F601/F811 等缺陷进入代码，却没有任何门禁报警 ——
**不是门禁失效，而是门禁从未运行**。

## 2. 新增 .github/workflows/ci.yml（5 个 job）

| job | 内容 |
|---|---|
| java-test | mvn -B -ntp test + proto 生成物同步校验（Java 侧） |
| python-check | ruff==0.16.8 + pytest + proto 同步校验（Python 侧） |
| wp-bff-test | node --test（含写端点统一鉴权回归） |
| frontend-build | npm ci + typecheck + build |
| contract-check | 契约分层校验 + 跨服务超时预算校验 |

触发：push / PR 到三条长期分支，另支持手动 workflow_dispatch；concurrency 取消同分支重复运行。
.gitlab-ci.yml 保留以兼容原有 GitLab 环境。

## 3. 修复 proto 同步校验的三处缺陷（本地预演 CI 时发现，不修则 CI 必红）

| # | 问题 | 处置 |
|---|---|---|
| 1 | pick_python 只找 services/python/venv，而 setup-python-env.ps1 建的是 nlp-service/.venv | 增加探测（约定路径优先，.venv 标注为历史兼容） |
| 2 | 导入自检的 shell 判据在 Git Bash 与 Linux bash 下行为不同 → 误报失败 | 改用 grep -q 判据 |
| 3 | Java 侧用 cmp 逐字节比较：入库产物为 CRLF、protoc 输出为 LF → 90 个文件全部误报「内容不同」 | 改用 diff -q --strip-trailing-cr（忽略行尾，仍能发现真实漂移） |

第 3 条尤其关键：它在 Linux CI 上**同样会失败**，若不修，新加的 job 一推就红。

## 4. 忽略规则与版本声明

- .gitignore 补 .pytest_cache/ 与 .ruff_cache/（此前未覆盖，本机已生成但侥幸未被提交）
- 新增 .python-version = 3.12；README 4.4 标注「最低 3.10 / 推荐 3.12」

## 5. 与协作者提交的合并（语义协调）

推送时发现协作者已提交 f6399a4（对上一轮环境修复的复检收口）：

- 对方把 setup-python-env.ps1 的默认路径从 nlp-service/.venv 改回 services/python/venv
  （仓库既有约定：docs 中 15+ 处命令与 .gitignore 均按该路径）—— 这是我上轮引入的不一致，对方订正正确
- 对方同时校正了 PROGRESS 的基线数字口径

处置：rebase 到对方提交之上，双方改动共存；并把 pick_python 的注释改为
「约定路径优先 → .venv 历史兼容 → 系统 python3」，避免出现两套并列约定。

## 6. 验证（本机完整预演 CI 全部步骤）

| 步骤 | 结果 |
|---|---|
| proto 同步校验（Python 侧） | 通过（12 个文件 + 6 模块导入自检） |
| proto 同步校验（Java 侧） | 通过（90 个文件双向一致） |
| 契约分层校验 | 63 端点 / implemented 35 路径 / 0 FAIL |
| 跨服务超时预算 | ok=19 / gap=0 / fail=0 |
| pytest | 199 passed / 1 skipped |
| wp-bff node --test | 106 passed |
| 前端 typecheck | 通过 |

## 7. 未执行项

| 项 | 原因 |
|---|---|
| 沙箱镜像构建与 R5-03 复验 | Docker daemon 未运行（npipe 端点不可用） |
| BGE-M3 / bge-reranker 权重下载 | hf-mirror.com 可达，但模型体积 GB 级，需择机执行；scripts/model-readiness.py 已可检查并给出获取命令 |

---

*关联：.github/workflows/ci.yml · scripts/proto-sync-check.sh · docs/技术债台账.md*
