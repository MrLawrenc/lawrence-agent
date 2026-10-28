# CLI 工作流

本机入口为 `monitor-cli/build/install/monitor/bin/monitor`；Windows 为同目录 `monitor.bat`。需要 JDK 17+。`--help` 输出帮助，其他命令统一输出单个 JSON；进度和错误写 stderr。CLI 不加载 JavaFX。

```bash
./gradlew :monitor-cli:installDist
MONITOR=./monitor-cli/build/install/monitor/bin/monitor
"$MONITOR" --help
"$MONITOR" jvm list --json
```

## 配置创建与校验

```bash
"$MONITOR" config init --output monitor.yml --package com.example.orders --json
"$MONITOR" config validate --config monitor.yml --json
```

- `init` 拒绝覆盖已有文件。`--package`、`--class` 接受逗号分隔的值，可叠加使用；类名使用完整 JVM 名称，内部类的 `$` 需按 shell 规则引用。
- 不提供范围时生成空范围草稿，创建成功退出 0，但 `validForCapture=false`；必须补范围后才允许采集，不会自动把示例业务包当作真实目标。
- `validate` 只检查 YAML、配置类型和约束，不连接目标。成功 0，配置错误 2；合法配置不等于选中了正确的业务代码。
- 新增 diagnostics 配置原样进入校验和采集；capture 的 `--output` 覆盖仅改变目录，不重置诊断开关。

## 范围预览

```bash
"$MONITOR" scope preview --pid <PID> --config monitor.yml --selected-only --limit 150 --json
"$MONITOR" scope preview --pid <PID> --config monitor.yml --package com.example.orders --search OrderService --offset 0 --limit 50 --json
"$MONITOR" scope preview --pid <PID> --config monitor.yml --refresh --json
```

预览使用与 App 相同的规则模型及索引查询；不会启动性能采集、请求堆直方图或触发 GC。

- 默认优先读取按 PID 和进程启动时间隔离的缓存；无缓存或 `--refresh` 时检查目标 JVM 的 classpath 方法索引。读取运行时属性可能使用本机 Attach API，不安装性能 transformer。
- `indexSource` 说明数据来源；`observedAt` 是本次查询时间，不是缓存生成时间。缓存可能漏掉新加载的类。
- `indexedClasses` 是候选索引总量，`globalSelectedClasses` 是全局规则匹配量；`packageClasses/packageSelectedClasses` 属于当前包（含子包），`matchedClasses/matchedSelectedClasses` 属于本次过滤结果。
- 方法字段为 `knownSelectedMethodNames`、`selectedClassesWithMethodIndex`、`selectedMethodIndexClassCoverageComplete`。索引按方法名去重，不能据此精确计算重载或实际增强方法数；未覆盖的类不会假装有零个方法。
- 动态生成类和自定义加载器中的类可能不在 classpath 索引中。只有挂载后的 `agent-logs/enhancement-result.json` 能证明实际增强结果。
- `limit` 为 1–500，默认 150；`offset` 非负。结果有界，计数仍表示完整候选匹配集合。

## 采集与离线重算

```bash
"$MONITOR" capture --pid <PID> --config monitor.yml --duration 60s --output <CAPTURE_ROOT> --json
"$MONITOR" analyze --session <SESSION> --json
```

等待 READY 后计时，采集进程在前台持有生命周期；停止后确认还原。App 与 CLI 对同一 PID/启动时间互斥。离线重算只读，不改原报告。正常结束、SIGTERM 还原与真实子 JVM 采集已有独立集成回归；诊断采集扩展由共享 core 负责。capture 最终 JSON 的 `diagnostics` 单独返回诊断质量，退出码仍由还原结果及 Trace 完整性决定；诊断降级不会冒充 Trace 丢失，也不能被 Trace 成功掩盖。

## 会话浏览

```bash
"$MONITOR" session list --root <CAPTURE_ROOT> --limit 20 --json
"$MONITOR" session show --session <SESSION> --json
```

- 默认根目录为 `~/Downloads/agent-monitor-captures`，`limit` 为 1–200。目录扫描深度最多 2、最多访问 20000 项；`scanTruncated` 明确说明是否只扫描到部分目录。
- 仅列出有效 `session.json` 标记的会话，按真实 `startedAt` 排序，不依赖文件名或文件修改时间。
- `show` 返回元数据和最多 128 项产物路径/大小，不回放、删除或修改会话。
- 没有最终报告时状态为 `NOT_FINALIZED`，完成性字段为 null；报告损坏、重复字段、拼接第二个 JSON 或身份不匹配时为 `UNREADABLE_REPORT`，不会继续显示采集成功。
- 诊断-only 会话可能没有 Trace 报告：有可信诊断 manifest 时显示 `NO_TRACE_REPORT` 和独立 `diagnosticSummary`。Span 完整性与诊断质量分别判断。

## AI 输出验收

```bash
"$MONITOR" ai verify --session <SESSION> --response <AI_RESPONSE.json> --json
```

响应文件应为 AI 输出 JSON 代码块的内容，不能含 Markdown 围栏。优先使用会话内 `reports/ai-analysis-output.schema.json`；旧会话没有该文件时回退到 CLI 内置契约，也可显式 `--schema`。

原生 Java 验收器无 Python 依赖，校验输出结构、跨文件 sessionId、队列/接收对账、完整性、来源路径、JSON Pointer、实际值、最近方法/Trace 上下文以及已知单位。浮点用 BigDecimal 精确比较；流式读取证据，不把整个大文件读成树。支持 JSON、JSONL 及 gzip；JSONL 必须提供从 1 开始的 `line` 和行内 Pointer，可附 evidenceId 验证索引与源哈希。原生滚动采集、证据查询、时间线及严格比较见 [核心采集与证据](core-capture-and-evidence.md)。

没有根调用但 manifest 身份匹配且有有效 metrics/thread 样本时，允许 **partial** 诊断发现；每项必须包含真实 diagnostics JSON 引用，仍不能把缺少完整 Trace 报告的会话标成 usable。无有效 Trace 或诊断样本时 findings 必须为空。CPU load 为 0–1 的 ratio，null 表示未知。

限制：响应/schema/单个选中值最多 4MiB，源 JSON（gzip 按解压后计）最多 128MiB，最多 128 层嵌套，每项 finding 最多 64 个引用。越限明确拒绝。验收通过只证明契约、质量门槛和引用一致，**不证明根因、因果关系或优化收益**。

Python 兼容工具 `scripts/verify-ai-analysis.py` 同步保留质量、诊断-only、ratio、小数与引用规则；它仍需 Python 3.9+，推荐自动化直接调用原生 CLI。

## 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 命令执行成功；不代表一定发现了慢调用 |
| 2 | 参数或配置错误 |
| 3 | 执行失败 |
| 4 | 性能 Agent 可能已挂载，还原未确认 |
| 5 | Trace/滚动归档/离线数据或诊断证据不完整、达到限额，或严格比较条件不足 |
| 6 | AI 响应未通过契约或证据验收 |

`diag/profile/compare` 由共享 `DiagnosticCommands` 实现，本 CLI 统一负责分发、JSON 输出和退出码；其能力与实测记录由诊断实现任务维护。某些诊断操作会产生额外开销，按帮助中的真实操作含义选择。

## 验证

以下为 2026-10-27 的阶段验收记录，测试数量及本机路径不代表当前构建。配置、缓存预览、只读会话、原生 AI 及 API 边界测试与全量真实 JVM 测试分开执行，避免争抢资源。实际分发包验证过：

- 空草稿创建 0、草稿校验 2、明确类范围创建/校验 0。
- IDEA 进程缓存索引 141468 个候选，精确规则匹配 SearchEverywhereAction 1 类。
- 会话 list/show 只读；真实 IDEA 报告的 3 条引用通过原生 AI 验收，篡改值退出 6。
- 新增 Java 单测覆盖精确小数、gzip/大源文件、最近方法、缺质量/空样本、损坏/拼接/重复报告和诊断-only 边界；Python 兼容验收 14 项通过，含重复 JSON 键拒绝。

最终共享回归与构建全部通过：Agent 60、core 54、CLI 6、model 6，共 126 项 Java 测试零失败；包括默认主机名下的真实 CLI 采集、SIGTERM 还原、诊断收尾超时及报告摘要一致性。App 与 CLI 分发包均已更新。

最新 CLI 安装包再次执行配置创建/校验、会话 list/show、IDEA 范围预览、真实 AI 引用通过和篡改拒绝，9 个检查全部符合预期。实测 JSON 在 `monitor-cli/build/verification/workflow-final-imx91yfb/`，不进入 Git。诊断验证范围见 [诊断文档](diagnostics.md)，当前回归入口见 [AI 协作指引](../AGENTS.md)。
