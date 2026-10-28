# Agent Monitor

一个面向本机 Java 进程的性能监控工具，提供桌面 App 和无界面 CLI。它通过 Java Attach API 注入 Agent，对指定业务包的方法、Servlet/HTTP 边界和 JDBC 调用做运行时观测；停止时会请求还原字节码，并生成适合人工和 AI 分析的会话报告。

> 适合本地开发、联调和问题复现。请先在测试环境验证配置，再用于重要服务。

文档入口：[完整导航](docs/README.md) · [AI 协作指引](AGENTS.md) · [AI 分析工作流](docs/ai-workflow.md) · [文件维护与清理](docs/workspace-maintenance.md)

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

### CLI：无界面采集与离线分析

配置初始化/校验、范围预览、会话目录和原生 AI 输出验收，详见 [CLI 工作流](docs/cli-workflow.md)。

核心新增自适应开销预算、一次挂载的滚动采集、历史证据索引、诊断时间线与严格基线比较，契约、限制与命令见 [核心采集与证据](docs/core-capture-and-evidence.md)。

独立 Servlet 接口统计、按症状排查、出站依赖采集及跨服务关联，覆盖范围、实际口径与使用方式见 [HTTP 统计与跨服务关联](docs/http-metrics-and-distributed-tracing.md)。

真实容器验收项目见 [Colima / Docker Java 性能实验室](examples/docker-performance-lab/README.md)：三个 Spring Boot 服务与 PostgreSQL、Redis、RabbitMQ，支持一键采集、跨服务/统计/JFR/还原验收和结果留存。

工程内的 [java-performance skill](skills/java-performance/SKILL.md) 包含 CLI 检测/安装、后台分段采集、增量结果读取与 AI 验收流程；`.agents/skills/java-performance` 链接到同一目录，供 Codex 在此工程发现。Skill 不内置 JAR，安装缓存及采集数据位于工程外，可直接复制整个 skill 目录独立使用。长任务的 worker 不依赖对话常驻；AI 自动回访需宿主调度能力。运行 `python3 skills/java-performance/scripts/perf.py --help` 查看入口；验证脚本为 `python3 scripts/verify-performance-skill.py`（仅采集自建子 JVM）。

CLI 使用同一套 core 服务，不加载 JavaFX。先构建分发目录：

```bash
./gradlew :monitor-cli:installDist
./monitor-cli/build/install/monitor/bin/monitor --help
./monitor-cli/build/install/monitor/bin/monitor jvm list --json
./monitor-cli/build/install/monitor/bin/monitor capture --pid <PID> --config monitor.yml --duration 60s --json
./monitor-cli/build/install/monitor/bin/monitor analyze --session <会话目录> --json
```

Windows 使用同目录的 `monitor.bat`。`capture` 必须提供明确的 PID 和合法 YAML 范围；
时长支持 `ms`、`s`、`m`，范围为 500ms–60m。`--output <目录>` 可覆盖本次会话根目录。
采集进程在前台保留 Collector；先等待 READY，再开始计时。正常结束或 SIGTERM / Ctrl-C 时请求停止并确认字节码还原。
强制终止（如 SIGKILL）无法执行清理；同一个 JVM 若留有未确认还原的会话标记，需要重启目标后再采集。
App 与 CLI 共用按 PID 和进程启动时间区分的本地会话锁，避免重复挂载。

标准输出只包含最终 JSON（`--help` 除外），进度写入标准错误；通过启动脚本直接调用可避免 Gradle 日志混入 JSON。
退出码：`0` 成功、`2` 参数/配置错误、`3` 执行失败、`4` 可能已挂载但还原未确认、`5` 已还原但采集不完整。
`analyze` 的 `5` 也表示离线原始数据不完整或缺少质量证据。退出码 `0` 不代表发现了慢调用，应检查 `analysisReady`、采集数和阈值。
`analyze` 读取原始 Span 重算报告并输出 JSON，保留 Trace ID，不覆盖原报告或其他会话文件，也不会连接目标 JVM。

### 桌面工作台

启动与切换目标时，进程选择直接显示在主窗口。左侧导航在同一窗口切换「采集配置」「调用链」「JVM 概览」「诊断分析」「会话与报告」和「高级配置」。前五页支持 `⌘ / Ctrl + 1–5`；切页保留搜索、选择和未应用的 YAML 草稿。顶部始终显示目标进程、采集状态、运行时长和开始/停止操作，底部显示连接状态与最近反馈。窗口支持缩小至 1080 × 720。

采集配置以包树和类列表为主，刷新、导入、保存集中在页头。「清空范围」后可撤销；继续修改范围、规则或导入配置后，旧撤销记录失效。长包规则以省略号显示，悬停可查看完整内容。点击「编辑排除规则」会切换到完整编辑区域，「返回包与类」恢复浏览；有未应用的规则时禁止相冲突的勾选或启动。JVM 指标集中在概览页，底部保留内存、线程和 GC 快照。

在采集配置、调用链或诊断表格中，`⌘ / Ctrl + F` 聚焦当前页搜索；搜索框的清除按钮或 `Esc` 清除过滤条件，不改变采集范围。进程选择页支持同样的搜索操作。

完整 YAML 在「高级配置」页编辑，明确提示未应用修改与应用结果；没有修改时禁用应用按钮。校验失败时保留草稿并显示原因，应用后同步范围选择；运行中的修改在下次采集生效。未修改的编辑器会在返回时载入最新配置；若保留草稿期间在其他页面改变了配置，应用会提示冲突，需先保留草稿、再用「恢复当前配置」载入最新设置。

调用链页中，双击方法、按 Enter 或点「调用详情」打开同页侧栏；切换调用即可查看线程、Trace/Span 标识、参数、SQL 与异常。概览按请求和执行上下文展示；HTTP 入口不读取请求体，若下层业务方法记录了入参，「参数」页会标明来源并展示该快照。无参方法、未记录快照和开销降级分别给出提示；详情随完整快照更新，对应调用被过滤或清空时收起。「占根调用」是当前耗时相对本地根调用的比例，包含子调用，不是 CPU 占用。

停止并确认还原后，自动进入「会话与报告」，可打开最终报告、会话目录或复制 AI 分析提示词。左下角「历史会话」加载离线调用链，「运行日志」打开日志查看器。诊断分析使用独立的分类导航，所有证据类别都在工作台内打开。

### 使用流程

范围选择支持“纳入大包、排除小包”：浏览并选中父包后点击“纳入整包”，再选中子包点击“排除此包”。顶部持续显示已纳入包和已排除子包，高级配置同步；“恢复跟随父包”移除当前包自己的规则，保留独立类和更深子包规则。包级规则对之后加载的新类也生效，排除优先。

1. 启动桌面应用，选择目标 JVM。
2. 等待包/类树索引完成，选择需要监控的业务包，例如 `com.example.order`。
3. 按需排除 DTO、Entity、配置类及 getter/setter，并在高级配置页检查 YAML。
4. 确认 YAML 中 `output.exporters` 包含 `otlp`（默认）或兼容的 `netty`，再点击开始；等待状态变为“监控中”。
5. 复现请求或业务场景。
6. 点击停止，等待“字节码已还原”的确认。
7. 打开本次会话目录中的 `reports/performance-report.json` 分析热点和慢链路，并先检查 `captureQuality` 是否存在丢弃或目标端拒绝。

如果停止操作未能确认字节码已还原，**不要立即再次注入**；重启目标服务后再继续，避免残留增强影响判断。

| 停止结果 | 下一步 |
| --- | --- |
| 已确认还原 | 可以开始下一次监控；分析本次 `performance-report.json`。 |
| Collector 未连接或 STOP 超时 | 查看 App 日志和 `agent-logs/agent-monitor.log`；不要假设目标已恢复。 |
| `RESET_FAILED` / 未确认 | 为避免残留 transformer 影响目标进程，重启目标服务后再监控。 |

## JVM 诊断中心

工作台「诊断分析」页包括：指标时间线、未完成调用、线程与锁、SQL / 连接等待、异常、异步任务、线程池 / 连接池、内存与 GC、类来源及证据质量。支持历史会话、优化前后对比、JFR 录制/导入、对象直方图/堆转储与指定本机 async-profiler。

App / CLI 监控默认将带时间戳的 JVM 指标与连续线程快照写入会话 `diagnostics/`，与 Span 采集质量分别呈现。JFR、异常 throw 事件和条件触发通过 `diagnostics` YAML 开启。CLI 增加 `diag`、`profile`、`compare`；[完整功能、配置、边界与验证](docs/diagnostics.md)。

## 能力边界

| 能力 | 说明 |
| --- | --- |
| 业务方法观测 | 按包名筛选，记录调用树、耗时、异常和可选参数/返回值。 |
| HTTP / Servlet | 识别 `javax.servlet` 与 `jakarta.servlet` 的 Servlet 边界。 |
| JDBC | 观测标准 `Connection` / `Statement` 调用，辅助定位 SQL 相关耗时。 |
| 实时查看 | App 通过本机 OTLP/HTTP Collector 接收 Span。 |
| Netty 断线恢复 | 已成功启动后与同一 Collector 断开时，Agent 在后台退避重连，并重新发送 `HELLO → READY`。 |
| 离线留档 | 可同时写入轮转后的 JSON Span 文件和最终分析报告。 |
| 安全停止 | STOP 指令在字节码还原、Span 导出队列排空并写回最终回执后才确认停止。 |

当前支持指定 HTTP 客户端的上下文传播和多会话跨服务关联，覆盖范围见 [HTTP 统计与跨服务关联](docs/http-metrics-and-distributed-tracing.md)。不提供集中式服务端、长期指标存储或生产级采样治理。

## 架构与类加载隔离

```mermaid
flowchart LR
  APP["monitor-app / JavaFX"] --> HOST["monitor-core / Attach、Collector、报告"]
  CLI["monitor-cli / 前台命令"] --> HOST
  HOST --> MODEL["monitor-model / JDK-only 协议与数据"]
  AGENT["monitor-agent / 目标 JVM 内"] --> MODEL
  HOST -. "Attach + loopback 协议" .-> AGENT
  AGENT --> ENTRY["Thin Entry / System Loader"]
  ENTRY --> BRIDGE["Bootstrap Bridge / JDK-only ABI"]
  ENTRY --> PAYLOAD["Agent Core / child-first loader"]
  PAYLOAD --> LIB["Byte Buddy、Netty / 私有依赖"]
```

三个执行入口是 Agent、App、CLI；共享纯 Java 的 model/core。App 只维护界面状态，core 不依赖 JavaFX 或 Agent 实现。
其中 `TraceServer` 承担本地 Collector 职责：接收、校验并组装 Span，再交给分析服务；Attach、会话控制与报告分析分别独立。
默认数据面为标准 OTLP/HTTP protobuf，生命周期控制使用独立的本机 TCP 通道。内置 Collector 按 receiver → processor → exporter 组织，可转发至官方 OpenTelemetry Collector。
Agent JAR 仅作为 App/CLI 分发资源存在；core JAR 不携带该资源，Agent 实现类不会出现在控制端普通 classpath。
`MonitoringRuntime` 统一会话所有权、Collector 启动、挂载、READY 与停止还原；实时预览以 traceId 更新独立不可变快照，收到 STOP 交付屏障后才冻结最终 `TraceNode`，由桌面端转换成 JavaFX `CallNode`。


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
| `monitor-app` | JavaFX UI、交互、纯 Java 数据到 observable 状态的适配 | `com.agentmonitor.app.MainApp` |
| `monitor-core` | Attach、OTLP Collector、会话、报告和回放 | `MonitoringRuntime` |
| `monitor-cli` | 前台采集、JVM 列表、只读分析和 JSON 输出 | `MonitorCli` |
| `monitor-agent` | thin entry、bootstrap bridge、隔离 core、Byte Buddy Advice、Exporter | `AgentBootstrap` / `AgentMain` |
| `monitor-model` | App 与 Agent 共用的协议、Span、配置值对象 | `com.agentmonitor.model.*` |

实际 Agent 产物层级：

| 产物 | 加载位置 | 内容 |
| --- | --- | --- |
| `monitor-agent.jar` | System Loader | 仅 `AgentBootstrap` 与两个嵌套 JAR。 |
| `agent/monitor-bootstrap-bridge.jar` | Bootstrap Loader | JDK-only Bridge 和生命周期 ticket。 |
| `agent/monitor-agent-core.jar` | 私有 child-first loader | Agent core、Byte Buddy、Netty 和 Exporter。 |

## OpenTelemetry Collector 接入

支持的标准数据面是 **Traces / OTLP over HTTP / binary protobuf**，接收 gzip；Trace ID 为 16 字节、Span ID 为 8 字节。未实现 OTLP/gRPC、Metrics、Logs，异步任务使用来源 Trace/Span 的标准 link 关联独立根链路；支持 Servlet 入站及 JDK HttpClient / Apache classic 出站的 W3C 上下文传播，不覆盖完整 OTel SDK 的全部跨线程和跨服务场景。架构对应 [OpenTelemetry Collector 的 receiver、processor、exporter 管线](https://opentelemetry.io/docs/collector/architecture/)，内置实现仍由本项目维护。

```mermaid
flowchart LR
  AGENT[目标 JVM Agent] -->|OTLP/HTTP traces| RECEIVE[内置 OTLP receiver]
  RECEIVE --> VALIDATE[session 校验、预算、去重、链路组装]
  VALIDATE --> REPORT[App / CLI 报告]
  VALIDATE -->|可选 OTLP/HTTP| COLLECTOR[官方 OTel Collector]
  COLLECTOR --> BACKEND[观测后端]
```

本机 receiver 地址和会话标识自动写入 Agent 配置与 `collector.json`。如需转发，启动官方 Collector，配置参见 [otelcol-local.yaml](docs/otelcol-local.yaml)，再在采集 YAML 加入：

```yaml
output:
  exporters: [otlp, file]
  otlpForwardEndpoint: http://127.0.0.1:4318/v1/traces
```

内置 receiver 只转发通过会话校验、格式校验和处理器接受的 Span，保留其原始 protobuf 的事件、链接和类型。App 的分析投影保留异常事件、链接及 dropped 计数；属性值转为文本，投影不是原始 OTLP 的无损归档。需要完整标准遥测留档时配置外部 Collector 后端。

每请求最多 4MiB，最多 4 个连接、2 个处理线程和 2 个排队任务；组装预算为 4096 条链路 / 32MiB，达到上限时明确拒绝并降低采集质量。单 Span 的身份字段和证据也有限额。收到根 Span 后可以预览，迟到子 Span 持续合并，到 STOP 屏障后才形成最终报告。运行中的慢链路快照带 `provisional: true`。

Agent 的网络请求有总截止时间和有界响应；明确的 429/502/503/504，以及结果未知的传输 I/O/超时，最多额外重试一次。不重试部分成功、非法 protobuf 或中断；日志记录根异常类型。重试可能重复交付相同 payload，本地 Collector 按 Trace/Span 身份幂等去重，不能保证外部后端恰好一次。单批两次请求总上界约 4.25 秒；STOP 导出 drain 预算 5 秒，Collector 停止回执等待 12 秒，失败仍明确标记不完整。部分拒绝按整个受影响批次保守记入失败目的地，计数不等于对端精确拒绝的 Span 数。Collector 的接收成功只代表该接收端确认，不证明最终后端持久化；具体范围记录在 `collector.json`。

官方二进制互通回归（需要本机 `otelcol`）：

```bash
./gradlew :monitor-core:collectorInterop -PotelcolBinary=/absolute/path/to/otelcol
```

该测试使用真实子 JVM，经过内置 receiver → 官方 Collector 的 receiver/batch/otlphttp → 测试接收端，校验采集、报告与停止还原。

## 范围选择器

包目录用于浏览，右侧搜索覆盖全部已发现的类。搜索、切换目录、刷新索引都不修改规则。可以直接纳入单个类，也可以勾选包并包含子包；每页最多 150 类，“纳入本页”只作用于当前页。取消上层包下的子包会生成包排除规则，后续加载的类也保持排除。

纳入包使用字面包前缀，独立类使用精确 JVM 类名；排除支持 `pkg:` 和 `cls:` 通配条件，并始终优先。命中通配排除的类不会出现虚假的选中状态。编辑排除规则后要应用或还原草稿，再修改勾选或开始采集。空范围保留为空并禁止启动，不再补回示例包；所有生效改动统一同步高级配置。

统计按归属分开显示：顶部是“全局待采集”，包名旁是“本包范围（含子包）/纳入采集”，底部是“当前列表/搜索结果”，目录下是目标 JVM 索引总量。切包时不会把上一包的统计挂到新包名下。运行中的实际增强结果独立于下一轮规则预览。CPU/内存增量不再按类数估算，参见[性能与 AI 验收记录](docs/performance-and-ai-validation.md)。

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
  exporters: [otlp, file]
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
- 本地排查保留 `otlp + file`；file exporter 便于离线复盘。
- Netty 重连只恢复到同一个 Collector 地址和端口。断线窗口采用至多一次投递：Span 不会缓存重放，可能缺少该窗口的数据，但不会因重试产生重复 Span。
- JDBC 和 HTTP 默认开启，也可按配置单独关闭。

## 会话产物与 AI 分析

每次采集创建独立会话目录，默认在：

```text
~/Downloads/agent-monitor-captures/<timestamp>/session-<uuid>/
├── README.md
├── config.yml
├── session.json
├── resource.json
├── collector.json        # OTLP 地址、session、限额和确认范围
├── ai-analysis-prompt.txt
├── spans/
│   ├── README.md
│   ├── manifest.json
│   └── spans-*.json
├── reports/
│   ├── README.md
│   ├── slow-spans.json
│   ├── performance-report.json
│   └── ai-analysis-output.schema.json
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
| JVM 时序指标 | 默认持久化 CPU、内存、线程、类加载、GC 与池证据，报告附独立 diagnosticSummary；GC 暂停归因还需 JFR 事件、时间对齐和对照验证。 |

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

使用会话内的 `ai-analysis-prompt.txt` 或界面“复制 AI 分析提示词”；二者统一由 core 的 `SessionAnalysisPrompt` 生成（当前 v4），避免手写简化版与导出内容不同步。新会话同时生成 `reports/ai-analysis-output.schema.json`。

模板要求先检查采集质量，再给有具体证据的发现、根因假设、缺少的信息和对照验证步骤，最后输出固定 JSON。不会要求 AI 凑满热点、臆测 GC/锁原因或给出未经验证的优化收益。对于只包含入口的窄范围采集，self time 不能直接解释为该方法自身 CPU 计算。

优先使用原生 CLI 只读验收器核对 AI 的严格 JSON 响应；完整流程见 [AI 分析工作流](docs/ai-workflow.md)：

```bash
./monitor-cli/build/install/monitor/bin/monitor ai verify --session <会话目录> --response <AI输出.json> --json
# Python 3.9+ 兼容入口
python3 scripts/verify-ai-analysis.py --session <会话目录> --response <AI输出.json>
python3 -m unittest discover -s scripts/tests -v
```

它校验本项目输出契约、采集质量、会话身份、真实文件/JSON Pointer、数值、单位和对应方法/Trace ID。通过不代表根因正确或收益已证实，仍需源码与同负载复测。旧会话的提示词文件不会自动改写；本次提供了[真实数据示例与测量边界](docs/performance-and-ai-validation.md)。

## 开发导航

| 想修改什么 | 优先查看 |
| --- | --- |
| 注入、重挂、停止、类加载隔离 | `monitor-agent/src/bootstrap/`、`AgentMain.java` |
| 增强哪些类/方法 | `AgentMain.java`、`interceptor/` |
| Span 导出与 STOP 协议 | `exporter/`、`monitor-model/.../protocol/` |
| Attach 与 Agent 文件提取 | `monitor-core/.../service/JvmService.java` |
| 收集、停止确认与 UI 状态 | `monitor-core/.../service/MonitoringRuntime.java`、`TraceServer.java`；界面状态在 `MainController.java` |
| 会话报告与 AI 文档 | `monitor-core/.../report/` |

### 对真实 JVM 做独立采集验证

使用与桌面 App 相同的服务验证挂载、报告生成与历史回放，并保留完整结果：

```bash
./gradlew :monitor-cli:desktopSmoke --args='<PID> com.example.ExactClass 45'
```

在限定时间内触发目标方法；可在输出的会话目录创建 `stop.request` 提前停止。
结果保存在 `monitor-cli/build/desktop-smoke/`，检查 `desktop-smoke-result.json` 的 `verified`，
再读取最终报告和原始 Span。此入口关闭入参、返回值和依赖采集，不替代界面逐项交互检查。

除桌面 App 的完整会话流程外，也可独立验证 Agent 的 Attach、采集、STOP 回执和原始文件：

```bash
./gradlew :monitor-cli:attachSmoke --args='<PID> com.example.ExactClass 30'
```

必须显式提供本机目标 PID 和一个完整类名；持续时间为 1–120 秒。该入口使用真实 Attach API 和
本机协议接收器，不使用 UI 示例数据。它关闭参数、返回值、SQL 参数及 HTTP/JDBC 采集，
自动请求停止并核对还原回执；结果位于 `monitor-cli/build/live-smoke/<时间>-pid-<PID>/`。

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
# 模块边界、全部测试、真实子 JVM 采集/中断还原、App 与 CLI 构建
./gradlew check :monitor-app:shadowJar :monitor-cli:installDist :monitor-cli:shadowJar

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
7. model 只使用 JDK；core/CLI 不得引入 JavaFX，Agent 不得依赖 core/App/CLI。
8. UI 的 observable 属性只存在于 App；core 通过冻结后的纯 Java 调用树发布结果。
9. App/CLI 必须通过 MonitoringRuntime 管理会话，停止结果和导出质量是不同结论；未确认还原时不能自动重试挂载。
