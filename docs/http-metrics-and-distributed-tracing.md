# 接口统计、症状排查与跨服务关联

## 已有 HTTP 能力与本次增量

原有 `javax.servlet` / `jakarta.servlet` 服务入口采集继续使用：请求方法、路径、状态码、异常、原始查询字符串、业务调用树。不读取请求体，不增加第二套 Servlet 插桩。

本次新增独立入口统计、出站客户端边界、W3C Trace Context 传播、跨会话精确关联和按症状排查。Agent / core / CLI 的隔离不变，新增测试依赖不打入运行包。

## 独立接口统计

```bash
monitor requests --session /absolute/capture/session --json
monitor evidence index --session /absolute/capture/session
monitor evidence query --session /absolute/capture/session --kind request_metrics --method orders
```

源文件为 `agent-logs/request-metrics.json`；最终性能报告也带 `requestMetrics`。不经过 Trace 头采样、尾采样或导出队列，采样率为 0 时仍计数。`dependencies.http=false` 时关闭。

- 按 HTTP 方法和框架路由聚合。Spring bestMatchingPattern 优先于 Servlet mapping，避免 DispatcherServlet `/` 遮蔽业务路由；没有模板则归为 `<unmapped>`，不使用含业务 ID 的原始 URL 作为统计标签。
- 最多 256 个正常标签及一个 `<other>`，溢出请求计数单独披露；每个标签使用固定 200 桶。
- 记录开始/完成/在途请求数、已观测失败、未知状态、窗口平均完成速率及 P95/P99 直方图上界。桶从 0.1ms 开始，约 10% 宽度；最后一桶开放。不能把上界当精确分位数。
- Servlet 服务端错误口径为异常或 HTTP 5xx。未知状态不是成功，`unknownStatus>0` 时 `errorRate=null`，`observedErrorFraction` 仅为已观测失败占比。
- Async Servlet 使用 AsyncListener 等到 complete；listener 无法安装时保留 pending，不把 dispatch 返回算成请求完成。服务端 Span 标注 `servlet_dispatch_only_async`，该 Span 仍只代表同步 dispatch 时间。
- 只覆盖开始于本次插桩窗口、经过已插桩入口的请求。STOP 时仍在途会令统计 `captureComplete=false`；这与 Trace 完整性是两套独立口径。不代表所有主机、所有实例、未覆盖框架或采集前请求。
- 索引中的 `request_metrics` 是整个统计窗口的聚合证据，可按时间查找，但不是一条请求事件。不得跨窗口平均 P95/P99。

## 出站依赖覆盖

| 依赖 | 当前边界 | 耗时含义 |
|---|---|---|
| JDBC | 保留已有 Connection/Statement 采集 | SQL 执行；取连接等待单列 |
| JDK HttpClient | HttpClientFacade.send / sendAsync | 客户端 API 完成；请求体完成时机取决于 BodyHandler |
| Apache HttpClient classic 4/5 | CloseableHttpClient 实现的 doExecute | 响应返回，后续读取/消费响应体不在此边界内 |
| Redis / Jedis | Connection.executeCommand | 同步命令往返，不覆盖所有旧 API、Pipeline 或 Lettuce |
| RabbitMQ Java Client | Channel 实现的 basicPublish | 客户端发布调用，不代表 broker confirm 或消费完成 |

配置独立开关：

```yaml
dependencies:
  jdbc: true
  http: true
  redis: true
  messaging: true
```

依赖统计位于报告 `diagnosticAggregates.dependencies`，仍是采样调用证据，不能用于服务整体吞吐/错误率。HTTP 不采集 body、认证头或全部头；Redis 不采集 key/value，RabbitMQ 不采集消息正文。DNS、TLS、连接、重试等阶段尚未分别测量，不生成虚构拆分。

异步 HTTP 保留原 Future/响应/异常语义，返回后立即解除提交线程的上下文，完成回调结束独立本地段并保留父 Span 关系。并行耗时不能累加成请求关键路径。Agent 自身 OTLP 请求显式抑制采集，防止上报自我放大。

## 跨服务关联

```bash
# 分别对 A / B / C 的 JVM 运行 capture，使用不同 serviceName。
monitor distributed --sessions '/captures/A,/captures/B,/captures/C' \
  --trace 0123456789abcdef0123456789abcdef \
  --output /analysis/distributed.json --json
```

沿用标准 [W3C Trace Context](https://www.w3.org/TR/trace-context/) 的 traceparent/tracestate。非法或全零 ID、重复 traceparent 不接入；tracestate 按条数/长度校验。入站遵循上游 sampled 标志；上游未采样时不会因本地异常强制导出该段，独立接口统计仍工作。本地尾采样晚晋升不能追溯让下游补录。

JDK 请求通过保留原 body publisher 的不可变副本注入。Apache classic 保留原 request 身份、请求级配置和取消接口，仅同步调用期间添加原本缺失的追踪头，退出时按自有值移除。调用者已有 traceparent 时保留，并记录 `agent.propagation.status=caller_owned` 和可解析的 carrier 身份；不能保证它与本工具当前 Trace 一致。没有提供全套 OpenTelemetry SDK 上下文互通，也不传播 baggage。

本地完整段与分布式 parent 分开识别：下游保留远端 parent，同时通过 `agent.local.root_span_id` 做本地完成计数、在线组装和回放。同一 traceId 多次访问同一服务不会覆盖或作为重复完成链路丢弃。异步 HTTP 段也独立完成。

最多关联 32 个明确提供的会话、一条 Trace、10000 个 Span。校验会话/Resource/原始 manifest 的已声明身份，拒绝会话外符号链接，按 traceId/spanId/parentSpanId 关联，保留服务、实例、会话、缺失父节点和本地质量。不会放开单会话 Collector 的身份检查；可继续分别转发至同一个标准 OTLP 后端。

`crossServiceComplete=false` 是刻意保留的边界：仅提供这些会话，无法证明所有下游都被采到。不存在父节点、采样和时钟偏差应披露，不根据时间相近猜测父子关系，不声称获得完整分布式关键路径。当前未覆盖 OkHttp、Reactor Netty、Dubbo 或消息消费端的自动传播。

## 按症状排查

```bash
monitor diagnose --symptom cpu --pid 12345 --duration 30s --output /captures
monitor diagnose --symptom latency --pid 12345 --config /config/monitor.yml --duration 30s
monitor diagnose --symptom errors --session /absolute/capture/session --json
```

支持 cpu、latency、memory、errors。提供配置时复用 capture 并采集 Trace/Servlet 统计；没有配置时收集 JVM/JFR 诊断，不猜测业务包范围。不会自动做 heap dump 或 live histogram。长任务继续使用原生 rolling / skill 后台 worker。

输出确定性采集步骤、候选发现、置信度、原文件/JSON Pointer/数值/单位、缺失证据与后续测量；`rootCauseProven=false`。例如高 CPU 是单点进程负载线索，堆占用增加不能证明泄漏，超过 100ms 的依赖只是待结合业务预算验证的候选。

AI 提示词升级为 v4，说明两套统计口径、异步边界、传播覆盖与跨服务缺口。schema 的 populationStatisticsAllowed 仍为 false，禁止推断未覆盖服务总体；独立入口文件的已记录窗口统计可以直接引用。

## 验证

真实测试使用本任务创建的三个独立 JVM、Tomcat Servlet 容器、JDK HTTP 客户端及 Apache 客户端；覆盖 A→B→C、同一链路重复访问 B、下游本地采样率 0 遵循上游、异步服务端完成/异步客户端段、POST body、框架路由聚合、原请求复用、请求级超时与取消、上报通道自采集排除。结果摘要：`monitor-core/build/http-distributed-verification/result.json`。

单元/回归另外覆盖统计桶落盘和总数、采样独立性、上游未采样错误不晋升、非法/重复追踪头、身份缺失/错配、文件越界、统计窗口索引和原值核验。生产高负载开销和所有客户端版本兼容性仍需各目标环境复测，不能由上述功能测试推断。

Jedis 使用真实 5.2 客户端连接本地 RESP 测试端点；RabbitMQ 使用真实 5.23 客户端与内存 frame sink 验证发布边界和消息体保持，未连接真实 broker，未验证发布确认/消费。测试同时保留三服务的原始会话，`monitor-core/build/http-distributed-verification/latest.txt` 指向最新一组可复查证据。

AI 验收支持零 Trace 样本但存在有效独立请求统计的 partial 发现；必须引用原始请求统计文件，不能升级为完整调用链分析。新增统计单位分别校验为 count、ratio、milliseconds、per_second；原生与 Python 验收保持一致。
