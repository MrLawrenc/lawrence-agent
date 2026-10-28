# AI 分析工作流

先明确任务是修改 Agent Monitor，还是用它分析某个 JVM。修改仓库遵循 [AGENTS.md](../AGENTS.md)；安装、目标选择和后台采集遵循 [java-performance skill](../skills/java-performance/SKILL.md)。本文用于分析已存在的会话。

## 先读契约与质量

把实际会话目录记为 `SESSION`，把分析产物放到会话外的 `ANALYSIS` 目录。先读会话 `ai-analysis-prompt.txt`、`README.md` 和 `reports/ai-analysis-output.schema.json`，再核对 `session.json`、`config.yml`、`resource.json`、最终报告和相关 manifest。文件缺失、损坏或身份不一致必须披露。无法访问本机文件的 AI 需要实际文件内容；分享前由用户选择和脱敏，不默认上传整个会话。

| 会话情况 | 分析边界 |
| --- | --- |
| 最终 Trace 报告及质量证据齐全 | 按会话契约判断 usable/partial，仍受范围、采样和截断限制 |
| 仅诊断，有身份匹配的有效指标/线程样本 | 可给 partial 发现，引用真实 diagnostics 证据 |
| 零 Trace，但有有效独立请求统计 | 可给 partial 发现，核对 enabled、FINAL、completedRequests，引用原始请求统计 |
| rolling 窗口 | 只作 partial；还原和总体完整性看 master，JFR 检查点单独核对 |
| 运行中、质量缺失或没有有效样本 | 记录阻碍；不虚构完整性、Trace 或发现 |

CLI 成功、目标字节码已还原、Trace 完整、诊断可用、AI 引用验收通过是不同结果。未确认还原的会话应保留原文件和标记，不通过删除锁来重试。

## 定向取证

先构建有界索引，按症状选择时间、Trace、线程或方法过滤，再读取所需记录。不要整批读入 JSONL、JFR 或原始 Span。

```bash
MONITOR=./monitor-cli/build/install/monitor/bin/monitor
# 将以下路径替换成实际绝对路径
SESSION=/absolute/path/to/session
ANALYSIS=/absolute/path/to/analysis
mkdir -p "$ANALYSIS"
"$MONITOR" session show --session "$SESSION" --json
"$MONITOR" evidence index --session "$SESSION" --json
"$MONITOR" evidence query --session "$SESSION" --method OrderService --limit 20 --json
# 用 query 实际返回的 ID 替换占位值
"$MONITOR" evidence get --session "$SESSION" --id <EVIDENCE_ID> --json
```

`session show` 和 `ai verify` 只读；`evidence index` 会生成索引，不修改原始证据。源文件发生变化时重新索引并核对引用。JSON 引用使用文件相对路径和 RFC 6901 Pointer；JSONL 另带从 1 开始的 `line`，Pointer 在该行内解析。`evidenceId` 只能来自真实查询结果，不能自造。具体限制见 [核心采集与证据](core-capture-and-evidence.md)。

## 形成发现并验收

按会话 schema 输出严格 JSON，将代码围栏内的 JSON 保存为 `$ANALYSIS/ai-response.json`。最多五项发现；每项区分已观测事实、根因假设、缺失证据、修改建议和复测计划。缺乏有效证据时 findings 为空，记录问题和下一步测量。

```bash
if "$MONITOR" ai verify --session "$SESSION" \
  --response "$ANALYSIS/ai-response.json" --json > "$ANALYSIS/validation.json"; then
  verify_exit=0
else
  verify_exit=$?
fi
printf '%s\n' "$verify_exit" > "$ANALYSIS/validation.exit-code"
# 6 表示契约或引用未通过；保留结果后再处理失败
```

优先使用原生 Java 验收；旧会话缺 schema 时 CLI 可回退内置契约，需说明这一限制。Python 兼容入口为 `python3 scripts/verify-ai-analysis.py --session "$SESSION" --response "$ANALYSIS/ai-response.json"`，失败退出 1，与 CLI 的 6 不同。验收成功只证明契约和引用一致，不证明根因或优化收益。

不要从有偏 Trace 样本推算总体吞吐、错误率或 P95；独立 Servlet 统计只引用已记录窗口口径，不跨窗平均分位数。进程 CPU ratio 不等于请求 CPU；时间线重叠不等于因果；self time 不等于纯 CPU；缺源码不能编文件行号。

## 验收失败与证据不足

验收失败时先读 validation.json 的错误，再核对实际源文件、Pointer/行号、原值类型、单位及最近的 Trace/方法上下文。修正分析响应后重验，不修改原始证据或放宽 schema 来让结论通过。schema 回退不兼容、源文件损坏或质量不足无法修正时，记录限制与需要重采/重建的证据。

文件不存在或尚未最终生成时，不猜测内容；证据不足就保存符合可用契约的空 findings、quality.issues 和 nextMeasurements。部分有效证据可以支持 partial 观测；不能把所有验收失败都归为数据不足，也不能把修正后的格式通过等同于因果已证实。

## 交付和后续

交付中文结论、sessionId、真实证据路径、质量限制、验收结果和下一步验证。优化采用“基线 → 单项变更 → 同负载复测”；严格 compare 不可比时只说明原因，不宣称收益。跨服务关联与覆盖缺口见 [HTTP 文档](http-metrics-and-distributed-tracing.md)。

后台任务先保存每窗分析及验收结果，再 ack 实际返回的游标；恢复时复用 jobDirectory，不重复启动。回访只有用户提出且宿主支持调度时安排。详细流程由 skill 的 [证据分析与交付](../skills/java-performance/references/analysis.md) 和 [长时间采集](../skills/java-performance/references/long-captures.md) 维护。
