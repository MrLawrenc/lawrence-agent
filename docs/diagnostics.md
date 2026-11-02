# Java 诊断工作台

本轮扩展围绕已有本机 Attach、Span 和会话体系实现。桌面工作台左侧的「诊断分析」在同一窗口展示证据；分类使用页内侧栏，刷新、采集及会话操作在顶部，命令结果也在页内查看，CLI 提供 `diag`、`profile`、`compare`。命令见 [CLI 工作流](cli-workflow.md)；原生滚动采集、证据索引及默认严格比较见 [核心采集与证据](core-capture-and-evidence.md)。

桌面端的「分析证据」复用 `SymptomDiagnosis`、`RequestStatistics`、`TimelineService` 和 `DistributedTrace`；不另外推断根因或服务总体分位数。时间线查询会为当前证据创建/更新索引；运行中的源文件继续变化时，哈希校验仍可能拒绝读取，应停止采集后刷新。跨服务关联逐个选择会话，仅展示精确父子标识关系，缺失父节点与覆盖范围同时保留。

查看历史会话时，当前 PID 的线程快照、环境采集、直方图、堆转储、JFR 录制与 async-profiler 被禁用；导入离线 JFR、会话比较及历史分析仍可用。所有历史入口同步工作台上下文。诊断表格提供数值全局排序、后台索引/搜索、500 行分页、结构化摘要与原始证据。单条详情显示最多 128KiB 文本，完整原值保留在会话文件。

`SessionEvidenceReader` 是 core 的有状态只读读取器，由单个顺序消费者持有：JSON 文件最多 64MiB，按身份、大小和修改时间缓存；指标每次最多读取 8MiB、每行最多 4MiB、保留最近 240 个有效样本。未闭合末行等待后续追加，坏行独立跳过，替换/截断重建窗口。读取问题逐项披露，缓存不被当作本轮新采集结果。会话时间基点来自 `session.json`，缺失时明确标记为样本窗口相对时间。

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

## 滚动工作台、实验与个人视图

采集配置页头「运行选项」可开启滚动归档，设置窗口（1–60 分钟）、总时长（1–10080 分钟）和磁盘软预算。App 复用 `MonitoringRuntime.enableRolling`，达到时长或 core 报告预算触限后温和停止；不删除已有证据，也不把窗口当作独立完整采集。报告页的滚动窗口表展示时间范围、根调用数、磁盘与 JFR 检查点；可以查看窗口并返回主会话。移动到其他机器的窗口按验证后的会话 ID 重新定位，不盲目信任清单里的绝对路径。

回放支持数组 JSON / gzip 与 `rolling-spans.jsonl` / gzip。滚动记录需匹配窗口 `sessionId`，按 Span 父子身份重建；缺少的参数、栈和窗口导出 ACK 不补造。离线滚动回放每行最多缓冲 4Mi 字符；遇到超长行时保留此前合法记录，并明确说明该文件余部未读取。报告、诊断 manifest 与所有携带身份的样本均核对会话；身份异常时拒绝对应数据，不回退到不可信缓存。

调用链先对全部匹配根排序再分页，搜索和定位使用相同的完整类名 / 方法 / 线程规则。组合条件支持最小耗时、异常、线程与接口；调用链按根调用条件筛选，诊断表按当前行的可用耗时字段筛选。保存 / 恢复视图包含搜索文字和条件；列宽、列顺序、排序与证据详情分隔位置单独记忆。

「瀑布图」显示选中调用及子调用的实际时间；关联证据中的多轨时间线展示 Span、指标、线程和 JFR 事件。支持缩放、平移和拖拽框选；多轨时间线的视窗同步筛选下方证据表，重置后恢复全部查询行。点击瀑布条目按树路径定位具体调用（包括同名重复调用），调用详情与图表同时保留。关联时间线与证据表双向高亮，选中行异步核验原文件哈希并读取 SQL、异常等原值；有 Trace ID 的证据可定位回调用链。缺少时间戳的记录明确跳过，最多绘制 5000 条 / 100 轨；完整查询结果保留在表格。时间重叠、时钟偏移和毫秒分辨率都不能证明因果或关键路径。

「基线实验」引导填写 workloadId、datasetId、loadProfile、warmupId、buildId；保存后才进入明确目标的采集入口。可载入历史基线声明并绑定基线 / 变更后两次会话，个人绑定路径可恢复。变更后采集要求基线满足 core 的单会话质量条件、前四项实验声明一致且 buildId 已更新；停止确认还原后自动核验并绑定结果。相同逻辑会话不能同时用于 A/B 比较，绑定失败或不可比原因会保留展示。严格比较可直接使用绑定会话，结果支持导出 JSON；仍需单独核对采集配置和实际负载。比较阻断原因保留原代码并补充中文解释，可直接返回实验页补齐条件后重新采集。

诊断任务显示当前阶段与实际用时，支持请求取消及失败后重试。JFR 录制等待、事件聚合与索引循环在共享 core 检查点响应取消；只有资源关闭完成后才显示取消完成，关闭失败显示错误。堆转储、JMX 调用和运行中的 asprof 等目标端操作不会被强行中断；界面会说明需等待该步骤结束。关闭工作台时会请求取消并等待本任务清理，已有证据文件保留，CLI stdout 契约不变。

JFR 帧缺少方法元数据时保留未知帧占位，在 `limits.unresolvedFrameOccurrences` 记录出现次数；时间线标明未解析帧，不把占位当作已识别的业务方法。

「界面偏好」提供浅 / 深主题、85%–150% 字号和紧凑 / 舒适密度。个人设置默认保存在 `~/.agent-monitor/desktop-preferences.json`，与采集 YAML 分开；测试通过 `agentmonitor.ui.preferences` 隔离路径。设置写入去抖，正常退出时刷新；读写失败仅反馈界面偏好，不改变采集事实。

## 桌面与长时验证

- `./gradlew :monitor-app:uiPreview --args=--verify`：真实控件的交互、窄窗口、数值排序、深层搜索、组合条件、实验声明、主题大字号及可访问名称检查。
- `.github/workflows/desktop-validation.yml`：macOS / Windows / Linux 的共享 Java 回归、独立 GUI 与真实诊断证据渲染检查；Windows/Linux 请求 1.0 / 1.5 UI scale，macOS 记录原生输出 scale，日志均记录实际输出 / render scale；Linux 使用 Xvfb。工作流存在不代表已在远端运行。
- `python3 scripts/desktop-soak/run.py --duration 3660 --output <新的目录>`：编译并拥有一个独立目标 JVM，复制当前 CLI 分发，绑定 PID / 启动身份，滚动采集并记录 `resources.jsonl`、停止还原与目标存活。先重建 CLI shadow JAR；不会选择已有业务进程。新运行同时记录单调时钟用时、复制分发的 SHA256；macOS 以与采集进程绑定的 caffeinate 防止空闲休眠，结束时释放该辅助进程。发生还原不确定时保留目标与证据。
- `./gradlew :monitor-app:uiSoak -PsoakSession=<该自建会话> -PsoakSeconds=3660`：只读实时证据的 JavaFX 长时检查，输出 `monitor-app/build/ui-soak/<时间>/pulses.jsonl` 与最终 summary，不额外 Attach。

长时任务结束后，运行 `python3 scripts/desktop-soak/summarize.py --capture <run目录> --ui <ui-soak输出目录>` 核验时长、原始计数、报告身份、可分析状态、还原与归档状态，并生成资源与 pulse 统计。只检查采集时可省略 `--ui`，结果明确标为 capture_only，不声明 UI 长时通过。中断或不足一小时不会被当作小时级通过；单调计时与墙钟分别记录，休眠 / 恢复造成的采样空档单独列出，完整性通过不代表连续无间断运行。

自动化可检查控件的可访问名称、焦点与键盘动作；VoiceOver / NVDA / Orca 的实际朗读、操作系统 DPI 与平台字体仍需在对应系统实测，不把语义检查等同于屏幕阅读器认证。长时结果属于指定 JVM、配置、机器和版本，不代表生产开销保证或无内存泄漏证明。

### 2026-11-02 本机验证记录

本机自建 JVM 的持续负载采集运行约 3753 秒，接收 69784 个 Span，生成 13 个滚动窗口。导出队列、交付丢弃和 Collector 拒绝均为零；诊断完成 1803 个指标样本、361 个线程快照，滚动归档无错误。字节码已还原，目标在停止后仍存活。

本次 STOP 边界存在 1 条在途根调用，因此 CLI 正确保留退出 5、`captureComplete=false`、`analysisReady=false`；该小时级记录没有通过严格完整性验收，不能作为完整基线。随后对另一自建 JVM 补跑 20 秒静止收尾，确认退出 0、采集完整且已还原。自建负载脚本现会在 READY 后临近结束时先静止业务，以便将持续负载边界与完整收尾两类验收分开。

只读诊断 UI 同时运行约 3660 秒，留下 732 条资源 / pulse 记录；峰值堆已用约 148 MiB，最大 pulse 间隔约 2229 ms，包含启动和后台调度影响，不是操作延迟分位数，也不据此声明持续流畅或生产开销收益。

本地证据位于 `monitor-cli/build/desktop-soak/run-20261102-c/verification-summary.json`、同目录的 `resources.jsonl` 与采集会话，以及 `monitor-app/build/ui-soak/2026-11-02T06-40-25.540407Z/`；静止收尾证据位于 `monitor-cli/build/desktop-soak/run-20261102-quiescent/`。这些 build 产物不随 Git 分发。

Linux/ARM64 的 Xvfb 1.5 scale 隔离检查通过了部分控件与大数据场景，但共享的 4GiB Colima 出现 OOM / 停滞，最终停止了本任务自己的容器，未完整通过。Windows 和 VoiceOver / NVDA / Orca 的实际朗读未在本机执行；三平台 CI 入口及可访问名称 / 键盘检查已准备，不能把未执行项标成通过。
