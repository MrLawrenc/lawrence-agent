# Java 诊断工作台

本轮扩展围绕已有本机 Attach、Span 和会话体系实现。桌面工作台左侧的「诊断分析」在同一窗口展示证据；分类使用页内侧栏，刷新、采集及会话操作在顶部，命令结果也在页内查看，CLI 提供 `diag`、`profile`、`compare`。命令见 [CLI 工作流](cli-workflow.md)；原生滚动采集、证据索引及默认严格比较见 [核心采集与证据](core-capture-and-evidence.md)。

## 已实现的功能与边界

| 功能 | 入口 / 证据 | 统计边界 |
| --- | --- | --- |
| JVM 指标持久化 | 默认随 App/CLI 会话采集；指标时间线 | CPU、堆/非堆、类加载、GC、线程、缓冲池、可读池 MBean；CPU ratio 未知为 null |
| 连续线程快照 | 线程与锁；`diag threads` | 相同栈聚合、连续栈对比、锁持有者、死锁 ID；最多 1024 平台线程、每线程 64 帧；虚拟线程覆盖取决于 JVM |
| 未完成慢调用 | 未完成调用；`agent-logs/live-invocations.json` | 跟踪已增强的根调用；最多跟踪 4096，展示 128；停止时保留 STOP_BOUNDARY 快照 |
| SQL 聚合与重复查询 | SQL / 连接等待；报告 `diagnosticAggregates` | 按指纹统计次数/耗时/错误；同一请求相同指纹执行至少 5 次标为候选，不直接断言 N+1 |
| CPU、分配、锁与 I/O 剖析 | JFR 录制/导入；`profile record/analyze` | 可放大、搜索、键盘操作的离线火焰图；CPU 是采样数，分配是采样字节，等待是纳秒 |
| wall-clock 火焰图 | 采集诊断 → async-profiler；`profile async` | 需显式指定本机兼容的 `asprof`；支持 cpu/wall/alloc/lock，不下载或捆绑原生二进制 |
| 线程池与连接池 | 线程池 / 连接池；连接等待聚合 | 已观测 ThreadPoolExecutor 活跃/队列/完成/拒绝；Hikari 等已注册 MBean 的可读属性；DataSource.getConnection 与 SQL 分开计时 |
| 异步任务关联 | 异步任务；报告 asyncLinks；OTLP Span links | ThreadPoolExecutor、ScheduledThreadPoolExecutor 初次调度、ForkJoinTask、CompletableFuture 常见调度路径；记录提交来源、排队、执行，使用独立根 Trace 和 link，不合并为同步树 |
| 会话对比 | 对比会话；`compare` | 按方法/SQL 对齐观测次数、均值、累计/最大耗时、错误；采样、范围或质量不同明确警示，不声称已证明收益 |
| 异常聚合与风暴候选 | 异常；JFR `exceptionThrows/exceptionStormCandidates` | 错误 Span 可能重复记录传播；启用 exceptionEvents 才统计 JFR throw；同类每秒至少 100 个观测 throw 标为候选 |
| 内存增长与泄漏线索 | 内存与 GC；对象直方图/对比/堆转储 | 堆趋势、GCHeapSummary、分配采样、直方图差异；增长不等于泄漏，不内置完整 heap dominator/GC roots 分析器 |
| 条件触发 | diagnostics.trigger；trigger.json / trigger.jfr | CPU、堆、BLOCKED、未完成慢调用分别持续阈值触发；拒绝事件立即触发；每会话最多一次；保留前置指标与 JFR 环形窗口，最终 profile.jfr 包含直到停止的数据 |
| 类加载与环境 | 类来源、运行环境；`diag classes/environment` | 类名、ClassLoader 身份、来源 JAR、模块、重复类名、启动参数与系统属性；活动会话可刷新类来源，历史会话只读 |

异步范围不覆盖所有自定义 Executor、Reactor、虚拟线程或全部 CompletableFuture 同步 continuation。覆写 beforeExecute/afterExecute 且不调用父类的线程池可能缺失执行关联；提交来源可能因为采样而没有保存。不会替换 Runnable/Callable，因此保留原任务的身份、取消与队列语义。定时任务的后续周期不自动归因到第一次提交。

## 配置

旧 version: 2 配置兼容，缺失 diagnostics 时使用以下默认值。JFR 和条件触发按需启用，异常 throw 录制也需单独启用。

```yaml
diagnostics:
  enabled: true
  intervalMs: 1000
  threadIntervalMs: 5000
  maxSamples: 3600
  jfr: false
  exceptionEvents: false
  trigger:
    enabled: false
    cpuPercent: 85
    heapPercent: 90
    blockedThreads: 20
    consecutiveSamples: 3
    slowCallMs: 3000
```

普通会话指标与线程 JSONL 总预算 128MiB；达到样本数或字节预算后停止并标明限额。原生 rolling 按 chunk 轮转这些限额、复用 JMX 连接，总量受滚动任务磁盘预算约束。采样周期是两次采集之间的延迟，耗时较长时记录 samplingGaps，不虚构缺失数据。CPU 不可用为 null。

JFR 独占本工具创建的 recording，不修改用户已有 recording，保留最多最终 120 秒 / 32MiB，单个录制最长 1 小时，避免控制端异常退出后无限运行。`trigger.jfr` 是首次触发时的前置窗口；`profile.jfr` 是停止时的窗口，较长会话可能不再包含最初触发时刻。JFR 事件内容由目标 JDK 支持情况决定。GC 时间重叠、ThreadPark、重复栈与堆增长均不能单独证明根因。

## 会话产物

```text
diagnostics/
  README.md
  manifest.json          # schemaVersion=1、sessionId、状态、采集数、失败/缺样/限额
  metrics.jsonl          # timestampMillis 为 Unix 毫秒，CPU 为 0–1 ratio
  threads.jsonl
  latest.json
  threads-latest.json
  environment.json
  profile.jfr            # jfr=true 或 trigger.enabled=true 时
  jfr-analysis.json
  flamegraph.html
  trigger.json           # 触发后
  trigger.jfr
agent-logs/
  live-invocations.json
  class-origins.json
```

最终性能报告新增 `diagnosticAggregates`、`diagnosticArtifacts` 和 `diagnosticSummary`。诊断数据质量与 Span `captureQuality` 独立：字节码成功还原、Trace 完整、JVM 诊断完整是三个分别核验的事实。

`diag record` 是不注入业务 Agent 的诊断会话，不会生成虚构的 Trace 报告。它等待首个有效诊断样本（最长 20 秒）后再计时，创建 session.json、诊断证据、AI 提示词和 schema；没有增强时没有业务在途调用、类来源或未注册池的 Agent 证据。manifest 有错误、零样本或达到限额时 CLI 返回 5。

AI 诊断-only 发现必须为 partial，每条附真实诊断证据。JSONL 用 line（从 1 开始）和行内 Pointer，可附 evidenceId 核对源哈希；也可用 latest.json / threads-latest.json / manifest.json / jfr-analysis.json。不可凭诊断样本声明 Trace 完整或总体 P99。

## CLI 示例

以下使用构建后的 `monitor` 启动脚本；命令支持 `--json`，输出仍为单 JSON。

```bash
monitor diag threads --pid 12345 --count 3 --interval 1s --output threads.json
monitor diag environment --pid 12345
monitor diag record --pid 12345 --duration 60s --output ./captures
monitor diag classes --session ./session-dir --query OrderService --refresh
monitor profile record --pid 12345 --duration 30s --output ./profile
monitor profile analyze --recording ./profile/profile.jfr --output ./analysis
monitor profile async --pid 12345 --binary /path/to/asprof --event wall --duration 30s --output wall.html
monitor compare --before ./before-session --after ./after-session --output comparison.json
monitor diag histogram --pid 12345 --output heap-before.json
monitor compare --kind histogram --before heap-before.json --after heap-after.json
monitor diag heap-dump --pid 12345 --output ./heap.hprof
```

对象直方图默认包含未回收对象并避免主动请求 Full GC；`--live` 可能触发 Full GC。堆转储为显式操作，可能暂停目标并占用较大磁盘空间，不覆盖已有文件。asprof 需要与操作系统/架构兼容，错误会返回并保留说明。

本机测试发现 JMX 使用目标公布的 RMI 主机名时可能不可达；遇到 JRMP/EOF 连接错误，检查主机名解析，必要时给目标 JVM 配置 `-Djava.rmi.server.hostname=127.0.0.1`。工具将诊断标为 DEGRADED 并保留错误，不把未知 CPU 画成零。业务 Trace 采集及还原结论保持单独呈现。诊断收尾在控制流程中最多等待 5 秒；超时标记 finalizationTimedOut=true、DEGRADED，后台继续清理，不能据此保证 JFR 已关闭。

## 验证

```bash
./gradlew :monitor-core:test --tests 'com.agentmonitor.core.diagnostics.*'
./gradlew :monitor-app:diagnosticsPreview
./gradlew check :monitor-app:shadowJar :monitor-cli:installDist :monitor-cli:shadowJar
```

集成测试创建独立 JVM，覆盖 JMX、JFR、GC、异常、ThreadPoolExecutor/CompletableFuture、连接等待、拒绝策略、未完成根调用、触发与停止还原。成功会话保存在 `monitor-core/build/diagnostic-verification/`，`latest.txt` 指向最新证据。预览基于该真实证据验证图表、在途调用、线程表和筛选，并生成宽窄窗口截图，不连接用户 JVM。
