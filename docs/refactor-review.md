# 三入口隔离重构与复核记录

> 历史验收记录：以下数字、版本和路径属于文中所述测试，不代表当前工作区已重新验证。当前使用入口见 [文档导航](README.md)。

日期：2026-10-26。基于 `45dfb5d` 后的重构工作区。

## 最终边界

| 模块 | 责任 | 编译依赖 |
| --- | --- | --- |
| monitor-model | JDK-only 协议、配置、JVM/Trace/报告数据 | JDK |
| monitor-agent | 目标 JVM 内增强、采集和导出 | model、隔离的 Agent 依赖 |
| monitor-core | Attach、Collector、会话所有权、报告、回放 | model、控制端依赖 |
| monitor-app | JavaFX 界面和 observable 状态转换 | core |
| monitor-cli | 前台采集、进程列表、只读分析和 JSON 结果 | core |

Agent 是 App/CLI 的分发资源，不是控制端的普通 Java 实现依赖。core JAR 不携带 Agent 资源。
`TraceServer` 是本地 Collector；Attach 控制和报告分析仍有各自的服务边界。默认数据面为 Traces / OTLP/HTTP protobuf；本机 TCP 仅保留会话控制和兼容数据协议。新增 receiver → processor → exporter 管线，可转发官方 Collector，未实现 Metrics/Logs/gRPC 或完整 SDK。

## 三个子 agent 的 review

分别审查模块边界、core/CLI 逻辑、桌面迁移。发现并修正了以下问题，随后进行了定向复核：

| 发现项 | 修正与验证 |
| --- | --- |
| PID 前导零或正号绕过互斥 | 规范化 PID；增加 PID 别名互斥回归 |
| 未接收的完整根调用未计入质量降级 | 对账 Agent 入队和 Collector 接收数；在线报告和离线分析增加未接收计数 |
| READY/连接失败被错误归为普通错误码 | 根据是否尝试挂载决定还原未确认退出码 4 |
| 阻塞 Attach 持有停止锁 | 外部挂载移出生命周期锁；停止有界等待，晚到的挂载返回不能清除 UNKNOWN 标记 |
| CLI 忽略保留策略，清理异常影响停止 | 保留策略统一进入 runtime；目录遍历和清理失败只记结果，不改变还原结论或阻止释放锁 |
| core 公开 API 暴露 implementation 类型 | JSON codec 内部化；用仅含 core/model 的 classpath 编译 API 消费者 |
| 常规构建未执行结构守卫 | verifyArchitecture 和 verifyPublicApi 接入 check |
| 桌面缺少进程终止清理，关闭可能卡 FX | 增加会话 shutdown hook；停止和 JMX 关闭移入后台；关闭旧计时任务 |
| READY 文案和旧控制器回调污染新界面 | 统一就绪状态；给采集、指标、停止、配置导入和回放回调增加有效性检查 |

本轮 review 提出的项目均已关闭。原生 macOS/Windows 安装包实际生成不在本轮验证范围。

## OTLP 与范围编辑器定向复核

三位 reviewer 又审查了 OTLP 标准语义、采集管线和新版规则交互。以下问题均已修复并验证：

| 发现项 | 修复与回归 |
| --- | --- |
| 失效会话数据可被原样转发 | 只转发当前会话、校验和处理器均接受的原始 protobuf Span |
| 接收队列/连接未限制、写入超时不完整 | 4 连接、2 worker + 2 队列；总请求截止、响应上限、关闭取消；不读取请求体的端点回归 |
| 根 Span 静默后过早定稿 | 静默仅生成不可变预览，保留组装到 STOP 屏障；超过一秒才到达的子 Span 回归 |
| 证据和身份字段未全部计入预算 | 身份/属性/证据限额及保留文本预算；异常事件、链接和 dropped 计数保留在分析投影 |
| NETTY+OTLP 的参数投影被误判冲突 | 语义指纹规范化；三导出端且采集参数/返回值的真实 JVM 回归 |
| 原生裁剪 runtime 缺少 HTTP 模块 | 两平台均补 java.net.http；限制模块客户端探针通过 |
| 超限请求绕过 OTLP 错误体 | 自定义 aggregator oversized handler，413 使用 protobuf Status 和标准 Content-Type |
| 查询先于类索引提交导致空包树 | 独立索引提交版本，异步结果验证版本；延迟扫描 UI 回归 |
| 包排除 glob、尾点号语义不一致 | Agent 和 UI 共享 package-only matcher，规范化字面前缀尾点号 |
| 勾选重置翻页、草稿覆盖勾选结果 | 区分浏览和规则变更；草稿期间阻止冲突的规则操作 |
| 超过 500 条预览后最终计数重复 | 按会话 Trace ID 记数，独立于滚动窗口；600 条两轮快照回归 |

## 验证结果

- `check`、App shadow JAR、CLI 分发及 shadow JAR 构建成功。
- Agent 59 项、core 29 项、CLI 2 项、model 5 项：共 95 项测试，零失败、零跳过。
- 独立官方 Collector 互通测试通过：真实 Agent → 内置 receiver → 官方 otelcol 0.163.2 的 receiver/batch/otlphttp → 后端接收器，接收 3 条 Span，报告完整、还原确认。
- 离线重放保留异常事件，AI 证据不会在离线分析时丢失异常消息。
- 包含真实子 JVM 挂载、停止、重复采集、SIGTERM 还原、阻塞挂载和只读分析回归。
- JavaFX 组件预览 26 项交互检查通过；包含全局类搜索、独立类选择、排除优先、空范围、规则草稿、延迟索引、翻页保持、600 条预览/最终计数及旧会话回调。
- 使用安装后的独立 CLI 采集 IDEA 的 SearchEverywhereAction（默认 OTLP）：16 条 Span，入队/接收对账一致，无丢弃、重复或待发送；字节码还原确认，目标继续存活。
- CLI 离线重算得到 16 条根调用，退出码 0；原始最终报告哈希保持不变。
- 新版桌面 App 启动并成功枚举 JVM。

本地实测结果位于 `monitor-cli/build/verification/idea-otlp-result.json` 和 `idea-otlp-analysis.json`，由构建目录保存，不进入 Git。
