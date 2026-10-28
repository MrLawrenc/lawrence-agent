# AI 开发工作流

先按 [仓库协作指引](../AGENTS.md) 保留当前工作区，再按下表定位修改范围。表内类名是搜索入口，不是要求每次修改全部文件；路径和测试以当前源码为准。

## 开始前检查

先用现有上下文明确目标、约束和完成条件：本次解决哪个行为、影响哪些入口、什么结果可验收。能从源码和已有约定确定的实现细节自行处理；只有目标 JVM、业务范围或不可推断的需求缺失时才询问用户。已授权的常规修改、修复与验证直接推进。

```bash
# 从仓库根目录执行，先确认已有工作；不会修改文件
pwd
git status --short
git diff --stat
git diff --name-only
# 仅当涉及 Java/Python 构建或脚本时检查对应运行时
java -version
python3 --version
```

`git diff` 不展示未跟踪文件内容，不能因此忽略新模块；结合 status 和定向 `rg --files` 查看。状态中已有删除项时，先确认是否为模块迁移，不按旧包路径补回同名类。不要在任务开始时默认 clean、升级依赖、重新安装工具或重建所有分发。

进入修改前记录四项即可：目标行为、已有改动边界、计划修改位置、验证方式。简单任务在对话中说明即可，不要求每次新建计划文件。需要运行工具时，先说明本轮动作；工作较长时更新已确认的发现和下一步。

## 按任务定位代码和测试

| 修改任务 | 实现入口 | 优先回归 |
| --- | --- | --- |
| 配置、默认值、范围匹配 | model 的 MonitoringConfig / ScopeSelection；core 的 MonitoringConfigYaml；agent 的 AgentConfig | MonitoringConfigYamlTest、ScopeSelectionTest、AgentConfigSamplingTest |
| Attach、互斥、停止还原 | core 的 MonitoringRuntime / TargetJvmLease / JvmService；agent 生命周期与 bootstrap bridge | MonitoringRuntimeStopTest、TargetJvmLeaseTest、BootstrapBridgeTest；生命周期变化再跑 MonitoringRuntimeIntegrationTest |
| 采样、预算、导出与接收 | agent 的 TraceSamplingState / OverheadController / exporter；core 的 OtlpHttpReceiver / TracePipeline / TraceServer | TraceSamplingTest、OverheadBudgetTest、OtlpHttpSpanExporterTest、OtlpHttpReceiverTest、TraceServerCompletenessTest |
| 报告、离线回放 | core 的 PerformanceReportCollector / SessionAnalyzer / OfflineTraceReplayLoader | PerformanceReportCollectorTest、SessionAnalyzerTest、OfflineTraceReplayLoaderTest |
| AI 提示词、schema、引用验收 | core 的 SessionAnalysisPrompt / AiAnalysisValidator；scripts/verify-ai-analysis.py | SessionAnalysisPromptTest、AiAnalysisValidatorTest、test_ai_analysis_validation.py |
| 索引、时间线、滚动归档 | core 的 EvidenceStore / TimelineService / RollingCaptureArchive | EvidenceStoreTest、RollingArchiveTest；跨窗与收尾变化再跑 RollingIntegrationTest |
| 诊断、请求统计、跨服务 | core 的 diagnostics；agent 的 RequestMetrics / DistributedContext / ClientSpanSupport | EvidenceAnalysisTest、PerformanceWorkflowTest、HttpObservabilityTest；跨服务变化再跑 HttpDistributedIntegrationTest |
| CLI 参数、JSON 与退出码 | MonitorCli / WorkflowCommands / EvidenceCommands / PerformanceCommands；core 的 DiagnosticCommands | MonitorCliTest、WorkflowCommandsTest、PerformanceCommandsTest，及对应 core 行为测试 |
| JavaFX 交互 | MainController、ui 下真实控件；scripts/ui-preview 的示例适配器 | uiPreview --args=--verify；诊断界面按需使用 diagnosticsPreview |
| skill 安装、后台任务、增量消费 | skills/java-performance/scripts/perf.py / runtime.py | test_performance_skill.py；分发/生命周期变化再跑 verify-performance-skill.py |

定向 Java 测试示例：

```bash
./gradlew :monitor-core:test --tests 'com.agentmonitor.core.ai.AiAnalysisValidatorTest'
./gradlew :monitor-agent:test --tests 'com.agentmonitor.agent.interceptor.TraceSamplingTest'
python3 -m unittest discover -s scripts/tests -p 'test_ai_analysis_validation.py' -v
```

定向测试通过后，跨模块或运行逻辑变更执行 `./gradlew check`，其中包含架构守卫和 core 公共 API 编译检查。纯文档修改检查链接与命令来源即可。UI 改动还要验证实际渲染、空状态、窄窗口和异步回调，不能只凭编译判断交互正确。真实 Attach、JFR、性能与多服务测试优先使用仓库自建 fixture，避免同时运行造成测量干扰。

## 跨入口同步

配置流为：YAML / App 编辑 → MonitoringConfig → JvmService 生成 Agent properties → AgentConfig。Agent 不直接解释 YAML。新增字段时核对默认值、约束、YAML 读写、App 导入/保存、CLI 校验/覆盖和目标端解析；还要检查 SessionComparison 的可比性规则是否包含该字段。不要只改示例 YAML 或 UI 默认值。

新增命令或变更结果字段时，核对所属 Commands 类、`monitor --help`、参数拒绝、JSON 类型、stderr 和退出码。退出码 4 的还原不确定、5 的质量/可比性不足、6 的 AI 验收失败不能互相替代。然后同步相关 docs；skill 依赖该能力时同步能力检测与引用。

修改 AI 契约时，一起核对：

- SessionAnalysisPrompt、core resources 中的 ai-analysis-output.schema.json，以及导出与桌面复制入口。
- Java AiAnalysisValidator 和 Python verify-ai-analysis.py 对相同输入的接受/拒绝规则，包括精确数字、布尔类型、单位、JSONL 行号、gzip、会话身份和路径边界。
- 相应 Java/Python 回归及 skill 分析说明。至少用真实有效引用和被篡改引用验证接受与拒绝两个方向，不以“能输出 JSON”作为完成标准。

提示词 VERSION、输出 schemaVersion、配置 version 和 Bridge ABI 属于不同契约，按各自兼容性修改，不为数字一致而同时升级。旧会话保持生成时的提示词/schema；兼容性变更需说明缺字段或旧格式如何处理。

## 确认运行的是本次代码

Gradle 编译输出、CLI installDist、CLI shadow JAR、skill 托管缓存是不同产物。源码更新后，已有“命令兼容”的 skill 缓存不会自动重建；只执行 ensure 不能证明已用上新源码。

```bash
# 验证 CLI 源码改动后，先更新实际要调用的分发
./gradlew :monitor-cli:installDist :monitor-cli:shadowJar
./monitor-cli/build/install/monitor/bin/monitor --help
```

若通过 skill 验证，按其 [安装说明](../skills/java-performance/references/cli-and-install.md) 显式选择新生成的 JAR/CLI，并核对返回的实际命令和安装元数据。已有 worker 固定使用选定版本，不替换运行中的 JAR。App 内嵌 Agent 变更还需重建 App 分发；Bridge ABI 不兼容时，已挂载旧 Bridge 的目标 JVM 需重启后验证，不能声称重挂载已替换 bootstrap 类。

## 中断恢复与交付

恢复工作先读 `git status`、已改文件和实际测试产物；已有改动、旧报告或上一条完成消息都不能证明本次测试通过。长任务先读取已保存的 jobDirectory、status、当前窗口及还原状态，再决定继续分析或停止；不重新启动相同任务，不删除租约来绕过异常。

构建依赖无法下载、JDK/Attach 不可用或沙箱拒绝进程信息时，记录实际错误与受影响检查。可继续做不依赖该条件的验证；不要把跳过标成通过，不为绕过环境限制改产品逻辑或测试预期。

交付注明修改范围、实际验证命令与结果、未覆盖的平台/外部工具、分析产物和必要的后续动作。没有执行的性能测试不能支撑收益数字；生成物在 build 中且未入 Git 时明确其本机属性。

## 完成检查

结束前检查本次实际修改，而非只读测试摘要：

- 功能满足用户要求，错误与空数据路径有明确行为；没有顺手扩大到无关重构。
- 按本次变化核对 App/CLI、配置、帮助、输出契约和相关文档；无需改动的入口不为形式一致而改写。
- 已执行的检查能对应本次代码；失败、未运行、受环境限制和历史通过分别说明。纯文档任务无需执行全量 Attach 测试。
- `git diff --check` 无空白错误，新增文档链接和示例中的类名、参数真实存在；新文件与删除项都已纳入检查。
- 原始采集证据、正在使用的分发、锁与用户未提交改动保留完整。任务未要求提交/发布时，不把本地修改描述为已经交付到远端。

## 交接记录模板

仅在工作需要中断、跨会话恢复或用户要求交接时填写。可放在对话中；已有任务记录则更新原位置，不为每次运行堆积新文档。占位值必须替换成实际信息；未知填“未知/待验证”，不填写猜测路径、PID 或通过状态。

```text
目标与约束：本次要达成的行为、用户指定范围、完成条件
工作区：仓库绝对路径、分支/HEAD（若已核对）、需要保留的已有改动
本次修改：实际文件及用途；尚未完成的部分
验证：实际执行命令、结果、产物路径；未执行或失败的原因
运行产物：当前实际 CLI/JAR 路径或安装元数据；是否已随源码重建
活动任务：无，或真实 jobDirectory / sessionDirectory / PID 与启动身份
采集状态：最近一次核对的时间、worker/窗口状态、还原确认结果
消费状态：consumer、已保存分析的位置、最近实际 ack 游标（没有则注明）
下一步：一个可执行动作、所需输入及验收方式
```

接手方重新核对会变化的状态，不能仅凭交接记录判断进程仍存活或字节码已还原。开发任务未涉及采集时，将活动任务与采集字段写“不适用”。不要为完成模板而启动采集、重新 ack、改会话状态或导出环境密钥。
