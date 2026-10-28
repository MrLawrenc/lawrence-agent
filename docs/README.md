# 文档导航

[项目首页](../README.md) 负责安装、快速上手、架构和配置；[AI 协作指引](../AGENTS.md) 负责仓库修改约定。

| 任务 | 阅读入口 |
| --- | --- |
| 让 AI 修改代码、同步契约、选择测试、恢复工作 | [AI 开发工作流](development-workflow.md) |
| 让 AI 分析已有会话、核验引用 | [AI 分析工作流](ai-workflow.md) |
| 安装工具、后台采集、恢复增量分析 | [java-performance skill](../skills/java-performance/SKILL.md) |
| 配置、范围预览、采集、会话、退出码 | [CLI 工作流](cli-workflow.md) |
| 开销预算、rolling、证据索引、时间线、严格比较 | [核心采集与证据](core-capture-and-evidence.md) |
| JVM 指标、线程、JFR、内存与诊断中心 | [诊断能力](diagnostics.md) |
| 接口统计、出站依赖、跨服务关联 | [HTTP 统计与跨服务关联](http-metrics-and-distributed-tracing.md) |
| 转发到官方 OpenTelemetry Collector | [本机 Collector 配置](otelcol-local.yaml) |
| 清理缓存、保留实测数据、管理生成文件 | [文件维护](workspace-maintenance.md) |

## 历史验收记录

[三入口重构复核](refactor-review.md) 和 [性能与 AI 验收记录](performance-and-ai-validation.md) 保留当时的方案、测量及限制。其他能力文档末尾的验收数字和本机构建路径也属于历史记录，不代表当前工作区重新执行过验证。`build/` 产物不随 Git 分发，路径不存在时应重新验证，不能补写或猜测数据。

当前行为以实现、`monitor --help`、配置校验及本次测试为准。分析历史会话时优先使用该会话导出的提示词/schema，不用新模板覆盖旧证据。提示词版本与输出 `schemaVersion` 独立演进。
