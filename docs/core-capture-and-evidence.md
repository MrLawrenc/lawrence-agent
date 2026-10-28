# 核心采集、证据与基线比较

本轮能力位于 Agent/model/core，CLI 是操作入口，skill 只负责安装、后台进程管理和 AI 工作流。App 原有采集入口继续使用同一核心生命周期。

## 开销预算与自适应降级

```yaml
overhead:
  enabled: true
  queueMaxBytes: 16777216
  highWatermarkPercent: 75
  lowWatermarkPercent: 35
  minimumSamplingRatePercent: 1
  adviceBudgetNanosPerSecond: 50000000
  recoveryMillis: 10000
```

导出器同时限制 8192 条排队项和估算驻留字节，预算包括 worker 已取出但尚未投递完成的 batch。超过预算会拒收并累计 queueDroppedSpans/byteBudgetDroppedSpans，不阻塞业务线程等待队列。估算覆盖对象、字符串、属性和事件；不是精确堆占用或 wire size。

持续压力使模式 NORMAL → REDUCED → MINIMAL：降低新根 Trace 的头采样率，关闭参数、返回值、SQL 绑定值和 HTTP query 的详情采集；子 Span 继承根 Trace 的采样决定。压力下降并满足恢复等待时间后逐级恢复。最低采样率不会把用户配置的 0 或更低比例提高；慢/错误尾采样仍可能提升样本，因此不能把降级后的样本当作均匀总体。

SQL 绑定在降级期间仍跟踪成功的清除/变更，未采值标为 unknown，恢复后不会把旧参数冒充新值。部分 hook 的观测耗时用于降级判断，导出线程记录 wall time；都不是进程 CPU 测量，也不承诺硬 CPU 上限。`overhead.enabled=false` 关闭自动策略，导出字节上限继续提供内存保护。

最终 `agent-logs/overhead.json` 保留预算、丢弃计数、峰值及最多 128 次策略转换；每个 Span 保留实际采样率/模式。遗漏转换另计数，不把“最后恢复 NORMAL”解释成整个采集都未降级。

## core 原生长期采集

```bash
monitor jvm identity --pid <PID> --json
monitor capture --pid <PID> --config monitor.yml --duration 2h \
  --rolling --window 5m --max-bytes 2147483648 --json
```

原有普通 capture 的时长范围保持 500ms–60m。rolling 支持 500ms–7d，窗口 500ms–60m；同一任务最多 10000 个窗口。`--expected-start <ISO_INSTANT>` 可传入 jvm identity 的 startedAt，防止后台任务恢复时操作复用该 PID 的其他进程；core 自身也绑定目标启动时间，在 Collector 启动/Attach 前及存活检查时核对。

一个 MonitoringRuntime 持有一份租约，只挂载一次 Agent。新 Agent 在根 Span 写入同步 Trace 完成的 Span 数，Collector 收齐完整树后归档并释放组装内存；旧协议缺少该计数时保持原停止屏障行为，不凭安静时间猜测完整树。

```text
<master-session>/
  rolling-capture.json
  reports/performance-report.json   # 会话级最终质量
  spans/...                        # Agent 原始文件，保持原轮转配置
  diagnostics/...                  # 按样本/容量分段，JMX 连接持续复用
  windows/<time>/session-<id>/
    window.json
    reports/performance-report.json
    spans/rolling-spans.jsonl
    diagnostics/metrics.jsonl
    diagnostics/threads.jsonl
    diagnostics/jfr-checkpoint.json
```

树归入“接收完整树”的窗口，跨窗根保留完整子树并计数。窗口没有独立的 Agent drain ACK，报告明确为 partial；不会制造入队/接收数字使它变成 complete。总体完整性和字节码还原以 master 最终结果为准。归档失败通过 rollingArchiveError / rollingArchiveComplete 返回，不能跳过租约释放，也不覆盖已确认的还原结论。

rollingArchiveComplete 表示归档写盘未发生失败，不能代替每窗 JFR 检查点成功；直接使用 CLI 时还需检查窗口的 jfr-checkpoint.json，skill 会等待其终态并返回独立 jfrCheckpoint 结果。

诊断开启 rolling 时，样本上限与 JSONL 容量按 chunk 轮转，samples 是累计数，rollingChunks 是段数。JFR 保持有界环，长期任务在录制最长时限前更新本工具自己的录制，不操作用户其他录制。检查点有队列/时间限额；窗口 JFR 发布需达到 COMPLETED、FAILED、TIMED_OUT、SKIPPED_CAPACITY 或 UNAVAILABLE 终态。停止时先暂停定时切窗，当前窗口仍接收收尾的完整树和诊断样本，待 master 诊断最终生成后再关闭末窗、复制最终 JFR 尾部或明确不可用，避免收尾期间新切窗口没有检查点。

JFR 检查点是实际检查点时刻的最近 120 秒/32MiB，可能有延迟、跨窗和重复，不能当作整窗覆盖或跨窗累加。事件带自身时间与 Java 线程 ID，应按实际区间查询。主控制对诊断收尾继续最多等待 5 秒；超时保持 DEGRADED，后台不能覆盖该终态。

磁盘预算按约 2 秒间隔检查整个 master 目录及剩余空间，触限温和结束；突发写入可能越界，是软额度。不会删除已有证据，也不保证操作系统退出/重启/休眠时连续采集。

## 可查询证据和 JSONL 引用

```bash
monitor evidence index --session <SESSION> --json
monitor evidence query --session <SESSION> --from <EPOCH_MS> --to <EPOCH_MS> \
  --trace <TRACE_ID> --thread <JAVA_THREAD_ID> --method OrderService --limit 20 --json
monitor evidence get --session <SESSION> --id <EVIDENCE_ID> --json
monitor timeline --session <SESSION> --trace <TRACE_ID> --limit 100 --json
```

过滤条件可单独使用。索引包含历史 JSON/JSONL、gzip、原始 Span 数组、线程快照条目和 JFR 时间事件。query 是有界元数据查询，get 才校验源文件 SHA-256、记录哈希并读取值。跨会话记录/容器 ID、目录外路径、索引改写或源文件变化会拒绝；需要源快照稳定后重新索引。

索引最多 200000 条、64MiB；单源最多 128MiB（gzip 按解压后），单记录最多 4MiB，查询最多 200 条；文件数和总扫描量也有界。达到上限、跳过坏源均显式报告 truncated/errors，时间线继续披露索引缺口。

AI 输出证据条目可使用：

```json
{
  "file": "diagnostics/metrics.jsonl",
  "line": 42,
  "jsonPointer": "/ProcessCpuLoad",
  "evidenceId": "由 evidence query 返回的 64 位十六进制 ID",
  "value": 0.2,
  "unit": "ratio",
  "traceId": null,
  "method": null
}
```

以上只是字段示例，不能原样当作真实证据。line 从 1 开始，Pointer 在该行 JSON 内解析；JSON 文件不提供 line。evidenceId 可选，提供时必须匹配文件、行和 Pointer 所在记录，并保持索引与源文件哈希一致。`ai verify` 只读，不为引用自动创建索引。原生和 Python 验收均校验真实值、单位及最近 session/Trace/方法上下文；通过不证明根因或优化收益。

timeline 使用实际时间及线程标识关联 Span、JFR、指标与线程快照，校验返回来源及用于推导区间的 focus Trace 来源。它只输出时间窗关联，不能把 GC 重叠、ThreadPark 或进程 CPU 直接认定为请求变慢的原因。

## 严格基线比较

```yaml
experiment:
  workloadId: order-search
  datasetId: seeded-data-v1
  loadProfile: 20-clients-60s
  warmupId: 2000-warmup-requests
  buildId: commit-or-build-id
```

```bash
monitor compare --before <BEFORE_SESSION> --after <AFTER_SESSION> --json
monitor compare --before <BEFORE_SESSION> --after <AFTER_SESSION> --observed --json
```

默认 strict 要求双方最终质量/原始 manifest 对账、session 身份、采集范围/采样/依赖/诊断/分析/开销/详情配置一致，并检查目标 JVM、启动参数指纹、OS/架构/主机/处理器和实验声明。buildId 需提供但允许不同；负载、数据集、预热声明需一致。开销策略发生过切换或缺失转换证据也不能按稳定基线放行。

不满足时 comparability.eligible=false，退出 5，只提供原始观测行与原因，不生成变化百分比。`--observed` 显式要求原来的观测差值，仍不证明收益。eligible=true 仅表示声明及记录满足比较门槛，工具不能凭声明证明实际流量完全相同；optimizationProven 始终为 false，仍需受控重复实验和语义正确性验证。

## 验证入口

专项与真实子 JVM 回归位于 OverheadBudgetTest、EvidenceStoreTest、StrictComparisonTest、RollingArchiveTest、RollingIntegrationTest 和 AiAnalysisValidatorTest。`scripts/verify-performance-skill.py` 验证独立复制 skill 的安装/缓存、原生滚动采集、JFR 末窗、证据查询/引用、时间线、严格对比阻断、增量消费与温和停止；只连接自建 JVM。

2026-10-27 本轮验收：完整 check、公开 API/架构检查与 App/CLI 构建通过；Java 145 项（Agent 64、core 69、CLI 6、model 6）、Python 31 项零失败。真实 skill 流程完成 2 个诊断窗、一次挂载的 3 个 Trace 窗、JFR 末窗保留、温和停止与业务 JVM 存活验证。异步消费回归确认 PENDING 检查点不会提前进入 AI 游标。

同日后续 Colima 三服务 skill 实测：修复 STOP 期间切窗导致检查点缺失后，三个 JVM 各采集 90 秒、共 9 窗，JFR 检查点全部成功；63 次入口请求、4 条跨服务边可核验，9 份窗口 AI 分析通过引用验收后才 ack，篡改数值被拒绝。各 master 已还原、零丢弃；完整 check、App/CLI 构建通过，Java 报告更新为 164 项、Python 40 项。仅证明该短时开发负载的实际流程，小时级稳定性与生产开销仍需专项验证。复现入口见 [Docker 验收项目](../examples/docker-performance-lab/README.md)。

本机证据：`monitor-cli/build/skill-verification/run-wqc8olmw/summary.json` 与 `monitor-cli/build/core-final-verification.json`。最终分发包另验证主会话 1911 条索引、原始 Span 数组 Pointer `/0`、历史 JSONL 的 Java/Python 同值校验、时间线与严格比较条件不足退出 5。独立复核发现的生命周期、参数恢复、在途计数、身份/哈希与容量披露问题均已修复并回归；这些是功能与正确性验收，不是生产开销或优化收益承诺。
