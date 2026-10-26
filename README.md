# Agent Monitor

一个面向本机 Java 进程的桌面性能监控工具。它通过 Java Attach API 注入 Agent，对指定业务包的方法、Servlet/HTTP 边界和 JDBC 调用做运行时观测；停止时会请求还原字节码，并生成适合人工和 AI 分析的会话报告。

> 适合本地开发、联调和问题复现。请先在测试环境验证配置，再用于重要服务。

## 5 分钟上手

### 前置条件

- 启动本工具的机器安装 **JDK 17+**，不能只安装 JRE（需要 `jdk.attach`）。
- 被监控进程是本机可 attach 的 Java 进程，建议使用 Java 17+。
- macOS/Linux 上首次 attach 可能需要与目标进程相同的用户权限。

### 构建与启动

开发环境可直接执行 `./start.sh` 启动；如需先关闭已有 Agent Monitor 实例再启动，执行 `./restart.sh`。两个脚本通过根目录的 `agent-monitor.pid` 管理本项目启动的独立进程组：`start.sh` 会拒绝重复启动，并在 Ctrl-C / 终止信号时停止整个受管进程组；`restart.sh` 发现 PID 文件后总会先尝试停止该进程组，确认停止成功才重启。不会停止被监控的目标 JVM。

```bash
./gradlew test
./gradlew :monitor-app:run
```

构建可分发包：

```bash
./gradlew :monitor-app:shadowJar
```

主产物位于 `monitor-app/build/libs/monitor-app-all.jar`。开发时优先使用 Gradle 的 `run` 任务，它会自动带上 JavaFX 和 Attach 所需参数。

如需本机分发目录（macOS/Linux）：

```bash
./gradlew :monitor-app:installDist
./monitor-app/build/install/monitor-app/bin/monitor-app
```

Windows 使用 `monitor-app/build/install/monitor-app/bin/monitor-app.bat`。不建议直接用 `java -jar` 启动 fat JAR，因为 JavaFX/Attach 的运行参数应由应用启动脚本统一提供。

macOS 如需在 Finder 和 Dock 中显示正式应用图标，请构建 `.app` bundle：

```bash
./package-mac.sh
```

产物位于项目根目录的 `app/Agent Monitor.app`。`package-mac.sh` 内部调用
`packageMacApp`，使用 `icon.icns` 生成原生 Finder/Dock 图标；开发时 `./start.sh` 也会使用同一图标。

Windows 请在 Windows 的 Git Bash 中执行：

```bash
./package-win.sh
```

产物位于项目根目录的 `app/Agent Monitor/Agent Monitor.exe`，使用 `icon.ico` 生成资源管理器和任务栏图标。Windows 安装包必须在 Windows 环境中打包，不能从 macOS 交叉生成。

### 使用流程

1. 启动桌面应用，选择目标 JVM。
2. 等待包/类树索引完成，选择需要监控的业务包，例如 `com.example.order`。
3. 按需排除 DTO、Entity、配置类及 getter/setter，并检查右侧 YAML 预览。
4. 确认 YAML 中 `output.exporters` 包含 `netty`，再点击开始；等待状态变为“监控中”。
5. 复现请求或业务场景。
6. 点击停止，等待“字节码已还原”的确认。
7. 打开本次会话目录中的 `reports/performance-report.json` 分析热点和慢链路，并先检查 `captureQuality` 是否存在丢弃或目标端拒绝。

如果停止操作未能确认字节码已还原，**不要立即再次注入**；重启目标服务后再继续，避免残留增强影响判断。

| 停止结果 | 下一步 |
| --- | --- |
| 已确认还原 | 可以开始下一次监控；分析本次 `performance-report.json`。 |
| Collector 未连接或 STOP 超时 | 查看 App 日志和 `agent-logs/agent-monitor.log`；不要假设目标已恢复。 |
| `RESET_FAILED` / 未确认 | 为避免残留 transformer 影响目标进程，重启目标服务后再监控。 |

## 能力边界

| 能力 | 说明 |
| --- | --- |
| 业务方法观测 | 按包名筛选，记录调用树、耗时、异常和可选参数/返回值。 |
| HTTP / Servlet | 识别 `javax.servlet` 与 `jakarta.servlet` 的 Servlet 边界。 |
| JDBC | 观测标准 `Connection` / `Statement` 调用，辅助定位 SQL 相关耗时。 |
| 实时查看 | App 通过本机 Netty Collector 接收 Span。 |
| Netty 断线恢复 | 已成功启动后与同一 Collector 断开时，Agent 在后台退避重连，并重新发送 `HELLO → READY`。 |
| 离线留档 | 可同时写入轮转后的 JSON Span 文件和最终分析报告。 |
| 安全停止 | STOP 指令在字节码还原、Span 导出队列排空并写回最终回执后才确认停止。 |

当前不目标于替代完整 APM：不提供跨进程链路追踪、集中式服务端、长期指标存储或生产级采样治理。

## 架构与类加载隔离

```mermaid
flowchart LR
  APP["Agent Monitor App\nJavaFX + Attach API"] --> ENTRY["Thin Agent Entry\nSystem Loader"]
  ENTRY --> BRIDGE["Bootstrap Bridge\nJDK-only ABI"]
  ENTRY --> CORE["Isolated Agent Core\nchild-first loader"]
  CORE --> BB["Byte Buddy / Netty\nprivate dependencies"]
  APP --> REPORTS["Session reports\nJSON + README"]
```

核心约束：

- 传给 `VirtualMachine.loadAgent()` 的外层 JAR 很薄，只含入口和嵌套运行时包。
- Bootstrap 只加载无第三方依赖的 `BootstrapBridge`；Byte Buddy、Netty 和 Agent core 不会进入 Bootstrap/System Loader。
- 被增强的业务字节码只调用 Bridge，Bridge 以 `Object` token 路由到当前 core，避免业务 ClassLoader 依赖 Agent 实现类。
- 每次 attach 都通过 Bridge 串行化：旧 transformer 成功还原后才允许新 core 生效。
- Agent 与内嵌 runtime 使用内容寻址的临时文件，不会覆盖仍被其他目标 JVM 持有的 JAR。

这套结构参考了 Arthas 的“薄入口 + bootstrap spy/bridge + 独立 core ClassLoader”模式。

> 升级提示：Bootstrap 类不能被同一 JVM 替换。已经注入旧版 bridge 的目标 JVM，升级到不兼容 bridge ABI 后需要重启一次目标 JVM。

## 模块说明

| 模块 | 职责 | 主要入口 |
| --- | --- | --- |
| `monitor-app` | JavaFX UI、JVM 列表、Attach、Collector、会话与报告 | `com.agentmonitor.app.MainApp` |
| `monitor-agent` | thin entry、bootstrap bridge、隔离 core、Byte Buddy Advice、Exporter | `AgentBootstrap` / `AgentMain` |
| `monitor-model` | App 与 Agent 共用的协议、Span、配置值对象 | `com.agentmonitor.model.*` |

实际 Agent 产物层级：

| 产物 | 加载位置 | 内容 |
| --- | --- | --- |
| `monitor-agent.jar` | System Loader | 仅 `AgentBootstrap` 与两个嵌套 JAR。 |
| `agent/monitor-bootstrap-bridge.jar` | Bootstrap Loader | JDK-only Bridge 和生命周期 ticket。 |
| `agent/monitor-agent-core.jar` | 私有 child-first loader | Agent core、Byte Buddy、Netty 和 Exporter。 |

## 配置

可从 [sample-agent.yml](monitor-agent/src/main/resources/sample-agent.yml) 导入基础配置。

最常用项：

```yaml
version: 2

scope:
  includePackages:
    - com.example.application
  # 只监控某个类时，可将 includePackages 改为 []，并配置完整 JVM 类名（内部类用 $）。
  includeClasses: []
  excludeConditions:
    - cls:*DTO
    - cls:*Entity
  excludeMethods:
    - get*
    - set*

sampling:
  # 根调用的立即采样比例，范围 0-100；100 表示全部实时导出。
  ratePercent: 10
  # 仅未立即采中的调用：根调用达到该耗时或任一 Span 异常时仍完整保留。
  tailCaptureThresholdMs: 50
  # 尾采样暂存上限；仅影响未命中 ratePercent 的链路。
  tailMaxBufferedSpans: 512
  tailMaxBufferedSizeMb: 1
  # promote（默认）：上限触发后转实时导出；drop：丢弃整条超限链路。
  tailOverflowPolicy: promote

output:
  exporters: [netty, file]
  capture:
    # 默认开启有界快照；内容截断会带结构化标记，也可单独关闭。
    arguments: true
    returnValue: true
    sqlParameters: true

dependencies:
  jdbc: true
  http: true
```

建议：

- 从一个足够具体的业务包开始，避免把框架、实体和通用工具类纳入。
- `scope.includeClasses` 是精确类白名单：可在 `includePackages: []` 时单独使用，也可与包白名单叠加；仅支持完整 JVM 类名，不支持 `*`，内部类使用 `$`。`includePackages` 与 `includeClasses` 至少需要配置一项。
- `sampling.ratePercent` 以一条根调用为单位决定是否立即记录，业务、HTTP、JDBC 子调用继承同一决定，因此调用树不会残缺。定位单条复杂链路时使用 `ratePercent: 100`，可完全绕开尾采样缓冲。
- 未立即采中的调用会在根调用结束时判断：达到 `tailCaptureThresholdMs` 或任一 Span 抛异常，就完整导出整条链路。它们受 `tailMaxBufferedSpans` 与 `tailMaxBufferedSizeMb` 限制；达到上限时由 `tailOverflowPolicy` 决定转实时导出（默认 `promote`）还是明确丢弃（`drop`）。
- 默认对入参、返回值与 SQL 绑定参数做有界快照；需要减少输出时，可单独关闭 `arguments`、`returnValue` 或 `sqlParameters`。
- 本地排查保留 `netty + file`；file exporter 便于离线复盘。
- Netty 重连只恢复到同一个 Collector 地址和端口。断线窗口采用至多一次投递：Span 不会缓存重放，可能缺少该窗口的数据，但不会因重试产生重复 Span。
- JDBC 和 HTTP 默认开启，也可按配置单独关闭。

## 会话产物与 AI 分析

每次采集创建独立会话目录，默认在：

```text
~/Downloads/agent-monitor-captures/<timestamp>/session-<uuid>/
├── README.md
├── config.yml
├── session.json
├── ai-analysis-prompt.txt
├── spans/
│   ├── README.md
│   ├── manifest.json
│   └── spans-*.json
├── reports/
│   ├── README.md
│   ├── slow-spans.json
│   └── performance-report.json
└── agent-logs/
    ├── README.md
    └── agent-monitor.log
```

停止且字节码还原已确认后，界面会弹出本次会话入口：可直接打开会话目录、打开
`reports/performance-report.json`，或复制 AI 分析提示词。若 Agent 已还原字节码但尾部输出
drain 超时，仍可打开这些文件；界面会明确提示报告可能缺少最后一小段数据。

关闭 App 后，可点击界面的“打开历史会话”，选择完整会话目录或其中的 `spans/` 目录，离线重建并
查看调用链。该操作只读取 `spans/spans-*.json`（以及 `.json.gz`）文件，不会连接目标 JVM、启动
Collector 或重新挂载 Agent。

### 可选的本地会话保留

默认**不会自动删除**任何会话。若希望限制本机磁盘占用，可在 `output` 下显式设置：

```yaml
output:
  retention:
    maxSessions: 10
```

`0`（默认）表示关闭清理。开启后，仅在一次成功停止后清理超出数量的旧会话；它只会处理名称和
目录结构均符合本工具生成规则、且已写入最终 `performance-report.json` 的会话，永不删除当前会话、
未完成会话、符号链接或手工目录。

### 采集完整性与 AI 分析边界

STOP 先关闭新调用入口，最多等待已有调用退出 500ms，再还原字节码并排空导出队列。
跨越 STOP 边界的根调用可能缺少后续子调用，即使根调用及时退出，也会保守标记采集不完整。
Collector 中未闭合的链路会计入丢弃数；导出过程中出现的队列丢弃或目标端拒绝也会标记采集不完整。
重复停止或窗口清理不会覆盖已经确定的结果。
`DRAIN_FAILED` 也包含这些采集不完整情况；它与字节码是否已还原是两个独立结论。

原始文件的 `spans/manifest.json` 中，`status: completed` 和 `completed.marker` 仅表示文件已关闭。
AI 还应检查 `captureComplete`、`interruptedRootCalls` 和 `pendingInvocationsAtStop`，再结合最终报告的
状态与 `captureQuality` 判断完整性。清单中的 `outputQuality` 保存上游导出计数；
即使文件已关闭，只要有丢弃、拒绝或未上报质量信息，`captureComplete` 也不会为 true。
旧会话没有这些字段时，不应默认完整。

每个 Span 的 `attributes` 提供以下证据（值为字符串）：

| 字段 | 含义 |
| --- | --- |
| `agent.capture.truncated` | 入参或返回值快照存在截断、深度限制或采集失败。 |
| `agent.payload.truncated` | 为满足统一传输预算，字段、属性或事件被裁剪。 |
| `agent.sampling.reason` | `head`、`tail_slow`、`tail_error` 或 `tail_overflow`，表示保留原因。 |
| `agent.sampling.head_rate_percent` | 本次根调用使用的立即采样比例。 |
| `agent.observer.excluded.nanos` | 已识别并从耗时中排除的监控开销；不等于全部观测开销。 |
| `db.operation.batch.size` | JDBC 实际累积的批次数，与参数样本数量无关。 |
| `agent.db.batch.parameter_samples` | 保留的批参数样本数，最多 20，取最后若干批。 |
| `agent.db.batch.parameters_truncated` | 批参数样本没有覆盖全部批次。 |

入参快照完成后才开始业务计时，父调用会排除已识别的嵌套监控处理开销。
采集不会主动调用任意业务对象的 `toString()`；对象改用有深度、字段数和总长度限制的快照。
单个字符串最多保留 2048 个 UTF-16 代码单元，入参或返回值快照总预算为 16384；
输出再按每字段 8192、整体 65536 个代码单元及属性/事件数量限制，确保经过 JSON 转义和 UTF-8 编码后
低于 Collector 的 1MiB 帧上限。被裁剪的嵌套 JSON 会替换为合法的截断对象，而不是半段 JSON。

部分普通请求加上慢请求/异常兜底保留，是有偏样本。AI 可以据此定位具体慢链路，但不能直接计算
总体平均耗时、P95 或错误率。需要统计一次受控复现时，可设 `sampling.ratePercent: 100`，并核实完整性。
当前上下文仅在同步线程内传递，异步线程上的方法不能据此拼成完整的请求关键路径。

### 给 AI 的最小上下文

当前采集与输出覆盖如下；所有调用次数和耗时统计都针对实际保留的样本。

| 内容 | 输出位置与范围 |
| --- | --- |
| 方法热点 | `performance-report.json` 的 `hotspots`：观测调用数、错误数、累计总耗时、累计 self time、最小/最大耗时。 |
| 慢执行链与异常链 | `slowTraces`：Trace ID、根方法耗时、错误标记及瓶颈方法的同步调用路径；按根方法保留有限个较慢样本。完整调用树可按 Trace ID 从原始 Span 重建。 |
| 同步热点路径 | `synchronousHotPaths`：每条保留链路中 self time 最大的瓶颈路径；不是跨线程或分布式关键路径。 |
| SQL / HTTP 证据 | 原始 Span 的种类、耗时和属性：SQL 指纹/摘要、批次、Servlet 请求方法/路径/状态码等。入参、返回值、SQL 参数受 capture 配置和截断限制。 |
| 异常证据 | 原始 Span 的错误状态、异常类型、异常堆栈及事件；报告保留观测错误数和链路错误标记。 |
| 数据质量 | 报告的 `status`、`analysisReady`、`captureQuality`、`analysisLimits`，以及原始文件清单的 `captureComplete`。 |
| JVM 实时指标 | 界面提供堆/非堆内存、线程、类加载、GC 次数和累计耗时；当前未持久化到会话报告，不能据此让离线 AI 判断某次慢调用是否由 GC 导致。 |

默认分析阈值为根链路 3000ms、慢 Span 800ms、慢 self time 300ms；默认也保留异常链路。
分析阈值决定哪些已采到的链路进入慢链路报告，`sampling` 决定哪些链路被采集；调低分析阈值不会补回未采到的数据。
没有慢链路样本不代表没有性能问题，应同时检查配置、热点与采集质量。

每次会话自动生成 `ai-analysis-prompt.txt`。让本地代码 AI 分析时，可提供整个会话目录；
使用无法访问本机文件的 AI 时，需要实际上传下列文件，只有提示词中的本地路径不足以提供数据。
报告中的 Trace ID 可用于按需检索原始证据；结合业务源码和受控复现再验证优化结论。

将会话目录（或至少以下文件）提供给 AI，并要求它按顺序读取：

1. 根目录 `README.md`：会话状态与文件阅读顺序。
2. `reports/performance-report.json`：最终热点、慢链路、同步调用路径、错误和 `captureQuality`（Agent 队列/投递丢弃、目标端拒绝、Collector 去重/丢弃）。
3. `reports/slow-spans.json`：运行期间可用的慢方法快照。
4. `spans/manifest.json` 和 `config.yml`：确认原始输出质量、采样范围与本次配置。
5. `agent-logs/agent-monitor.log`：确认哪些类实际被增强，以及是否有 retransformation 失败。
6. 仅在需要证据时读取 `spans/spans-*.json`，它们可能很大且包含敏感参数。

可直接复制这段提示词：

```text
你是 Java 性能分析助手。先阅读会话根目录 README 和 reports/README，
再分析 reports/performance-report.json。请输出：
1. 最值得优先处理的 3 个热点或慢链路；
2. 每项的证据（方法、耗时、同步调用路径、self time）；
3. 可能根因及需要进一步验证的假设；
4. 风险最低的优化建议。
不要把缺失的报告解释为“没有性能问题”；请检查会话状态、`captureQuality` 与 agent 日志。
检查 spans/manifest.json 的 captureComplete 和 Span 的采样、截断属性；缺失或截断的数据不能作为完整证据。
部分普通请求加慢请求兜底是有偏采样，不要直接用它计算总体平均耗时、P95 或错误率。
```

## 开发导航

| 想修改什么 | 优先查看 |
| --- | --- |
| 注入、重挂、停止、类加载隔离 | `monitor-agent/src/bootstrap/`、`AgentMain.java` |
| 增强哪些类/方法 | `AgentMain.java`、`interceptor/` |
| Span 导出与 STOP 协议 | `exporter/`、`monitor-model/.../protocol/` |
| Attach 与 Agent 文件提取 | `monitor-app/.../service/JvmService.java` |
| 收集、停止确认与 UI 状态 | `monitor-app/.../service/TraceServer.java`、`MainController.java` |
| 会话报告与 AI 文档 | `monitor-app/.../report/` |

### 对真实 JVM 做独立采集验证

使用与桌面 App 相同的服务验证挂载、报告生成与历史回放，并保留完整结果：

```bash
./gradlew :monitor-app:desktopSmoke --args='<PID> com.example.ExactClass 45'
```

在限定时间内触发目标方法；可在输出的会话目录创建 `stop.request` 提前停止。
结果保存在 `monitor-app/build/desktop-smoke/`，检查 `desktop-smoke-result.json` 的 `verified`，
再读取最终报告和原始 Span。此入口关闭入参、返回值和依赖采集，不替代界面逐项交互检查。

除桌面 App 的完整会话流程外，也可独立验证 Agent 的 Attach、采集、STOP 回执和原始文件：

```bash
./gradlew :monitor-app:attachSmoke --args='<PID> com.example.ExactClass 30'
```

必须显式提供本机目标 PID 和一个完整类名；持续时间为 1–120 秒。该入口使用真实 Attach API 和
本机协议接收器，不使用 UI 示例数据。它关闭参数、返回值、SQL 参数及 HTTP/JDBC 采集，
自动请求停止并核对还原回执；结果位于 `monitor-app/build/live-smoke/<时间>-pid-<PID>/`。

先检查 `smoke-result.json` 的 `restored`、`analysisReady` 和 `captureQuality`；再读取 `spans/manifest.json`
和原始 Span。`analysisReady` 还要求文件与网络的 Span 数量一致、父 Span 均存在。
这仅验证原始采集链路，不会生成桌面 App 的最终性能报告。

测试 IDEA 本身时，可选低频的 `com.intellij.ide.actions.SearchEverywhereAction`，然后打开一次工具栏搜索。
高频的 `IdeEventQueue` 即使只选一个类，也可能产生大量方法调用并触发队列丢弃，不适合作为小量验证目标。

Agent 使用支持新 JDK 的 Byte Buddy；可以显式指定子 JVM，验证与 IDEA 相同的 JBR：

```bash
./gradlew :monitor-agent:test --tests com.agentmonitor.agent.integration.AgentClassLoadingIntegrationTest \
  '-PprobeJava=/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java'
```

### 独立 UI 预览

只查看和调整 JavaFX 界面，可运行：

```bash
./gradlew :monitor-app:uiPreview
```

预览复用真实的进程选择、包/类选择、调用链和 JVM 指标控件，以示例数据代替后端服务。
预览不会枚举、连接或注入真实 JVM；窗口中的示例 YAML 和工作台外壳仅用于布局检查。
`sourceSets.uiPreview` 与正式应用完全隔离，`scripts/ui-preview` 下的适配器不进入应用分发包。

执行组件交互检查并渲染宽、窄窗口截图：

```bash
./gradlew :monitor-app:uiPreview --args=--verify
```

截图位于 `monitor-app/build/ui-preview/`。检查覆盖进程过滤与 Enter 确认、子调用搜索、
无匹配提示、清空后回放、指标等待态，以及采集失败时清除旧指标。
该检查验证独立控件；真实 Attach、STOP 和会话报告仍需通过完整应用验证。

## 验证命令

```bash
# 全部单元测试、Agent 真实子 JVM 隔离测试、App 构建
./gradlew test :monitor-app:shadowJar

# 仅验证 Agent 的真实类加载隔离与重复加载
./gradlew :monitor-agent:test --tests com.agentmonitor.agent.integration.AgentClassLoadingIntegrationTest
```

`AgentClassLoadingIntegrationTest` 会启动真实子 JVM，验证：业务 ClassLoader 只看到 Bootstrap Bridge、看不到 Agent core；重复加载不会出现 `LinkageError`。

## 给 AI 编码助手的约束

修改本项目时，请保持以下不变量：

1. 外层 attach JAR 不能包含 `AgentMain`、Byte Buddy、Netty 或其他 core 依赖。
2. Bootstrap Bridge 只能使用 JDK 类型；不要向它引入 App、Agent core 或第三方类型。
3. Advice 是会被内联到业务字节码的薄壳，只能依赖 Bridge，不应引用 core 业务逻辑。
4. STOP 成功的含义是已确认还原字节码；失败时保留旧 runtime 供重试，不能提前关闭其 class loader。
5. 对 Agent/bridge ABI 的不兼容变更必须提高 `BootstrapBridge.apiVersion()`，使已运行 JVM 快速失败而非链接到错误版本。
6. 新增会话文件时，优先原子写入，并在文件旁提供简短 README，避免 AI 读取半成品或误解文件含义。
