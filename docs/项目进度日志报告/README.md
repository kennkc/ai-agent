# 项目进度日志报告 · 目录索引

> Agent-Lifeform · AI Agent 生命体架构 · 分阶段开发的过程归档与验收留痕

本目录用于**阶段性开发的过程留痕**：每完成一个开发阶段，在此归档该阶段的
**执行日志（过程）**、**阶段性报告（结论与问题复盘）**、**测试验收报告（证据）** 三类文档，
并提供 HTML 版本便于直接阅读与汇报。

---

## 目录定位与约定

| 目录 | 用途 |
|---|---|
| `docs/项目进度日志报告/` | **本目录** — 分阶段开发的过程日志与验收报告（按阶段归档） |
| `docs/demo/` | 各阶段的端到端演示脚本（怎么跑起来看到效果） |
| 项目根 `PROGRESS.md` | 全局进度总览（跨阶段的 checklist 视图） |

**三类文档的分工**：

| 文档类型 | 回答的问题 | 面向读者 |
|---|---|---|
| 开发执行日志 | 这段时间**做了什么、怎么做的** | 接手开发者、复盘者 |
| 阶段性报告 | 阶段**达成了什么**、遇到**什么问题、怎么解决** | 技术负责人、决策者 |
| 测试验收报告 | **凭什么判定通过**（可复现证据） | 验收方、质量把关者 |

---

## 文件清单

### Phase 2 · 感官期（2026-09-12）

| 文件 | 说明 |
|---|---|
| `Phase2-阶段性报告.md` · `.html` | 阶段目标、交付物清单、R2-01~R2-10 需求覆盖矩阵、关键设计落地说明、**13 项问题与解决思路**、风险与遗留、阶段结论 |
| `Phase2-开发执行日志.md` · `.html` | 六阶段执行流程时间线（对账→实现→构建攻坚→缺陷修复→集成核验→全量验收）、6 条 ADR 决策记录、命令执行台账、环境侧记录 |
| `Phase2-测试验收报告.md` · `.html` | 71 项测试逐项结果（Java 逐类逐用例 + Python）、R2-01~R2-10 逐项验收判定、DoD 对照、关键指标实测、4 个缺陷的修复与回归、待补验证项、验收结论 |

**阶段数据速览**：代码基线 `850f49d`（147 文件）· 测试 **71/71 通过** · 意图识别准确率 **90.0%** / P99 **0.052ms** · 全量构建 5 模块 SUCCESS

---

## 命名规范

后续阶段请按以下格式在本目录追加：

```
Phase<N>-阶段性报告.md       + .html
Phase<N>-开发执行日志.md     + .html
Phase<N>-测试验收报告.md     + .html
```

- `<N>`：阶段序号（阿拉伯数字），如 `Phase3`、`Phase4`
- 阶段名称若需体现，可写进文档内标题（如「Phase 3 躯体期 · 阶段性报告」），**文件名保持简洁**
- 一个阶段一套三份，**不合并**（三类文档读者与用途不同）

---

## 生成 HTML 版本

本目录的 HTML 由脚本统一生成（样式一致、含目录导航）：

```bash
# 从项目根执行；必须使用 anaconda Python（含 markdown 3.4.1，托管 Python 无此包）
E:/software/anaconda3/python.exe scripts/md2html-report.py "docs/项目进度日志报告/Phase2-阶段性报告.md"

# 批量转换整个目录
E:/software/anaconda3/python.exe scripts/md2html-report.py --all "docs/项目进度日志报告"
```

> **注意事项**（踩坑记录）：
> - 必须用 **anaconda Python**：`E:/software/anaconda3/python.exe`；托管 Python 未安装 `markdown` 包。
> - 中文字符路径需加引号。
> - `--all` 批量模式**自动排除 `README.md`**（索引页保留纯 Markdown 即可），如需转换用单文件模式。
> - 修改 `.md` 后**需重新生成对应 `.html`**，两者一并提交，避免版本漂移。
> - HTML 为自包含单文件（无外部依赖），可直接双击打开、也可用浏览器打印为 PDF。

---

## 复现本阶段验证

```bash
# Java 全量构建 + 测试（52 项）
cd services/java && ../../scripts/mvn-dev.sh clean package

# Python 测试（19 项）
cd services/python/nlp-service
../venv/Scripts/python.exe tests/test_intent.py
../venv/Scripts/python.exe tests/test_ocr.py

# 契约校验
python scripts/contract-check.py

# 端到端演示（需 Docker 基础设施）
./scripts/start.sh all && ./scripts/healthcheck.sh
# 然后按 docs/demo/Phase2-DEMO.md 执行
```

---

## 归档维护要求

新增阶段报告时，请同步完成：

- [ ] 三份 `.md` 齐备（阶段性报告 / 执行日志 / 测试验收报告）
- [ ] 生成对应 `.html` 并一并提交
- [ ] 在本 README 的「文件清单」中登记新阶段条目
- [ ] 更新项目根 `PROGRESS.md` 的对应阶段章节与验证结果
- [ ] 未通过项 / 待补验证项**必须显式登记**，不得省略（诚实留痕优先于好看）

---

*本目录建立于 2026-09-12 · 维护：WorkBuddy*
