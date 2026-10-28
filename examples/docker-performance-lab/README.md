# Colima / Docker Java 性能排查验收项目

独立的 Spring Boot 3.5 / Java 21 测试项目，与产品模块分开构建。启动三个 Java 服务（gateway → inventory → pricing）、PostgreSQL、Redis 和 RabbitMQ，通过真实接口验证 Agent Monitor。不会把测试依赖加入 Agent、CLI 或桌面包。

## 运行

在工程根目录执行：

```bash
python3 examples/docker-performance-lab/run.py --context colima
```

需要 Python 3.9+、本机 JDK 17+、Docker Compose 以及已运行的 Colima。默认基于已缓存的 `gradle:8.14.3-jdk21` 开发镜像构建；它较大，但只属于可选验收环境，不是产品运行包。

每次使用唯一 Compose project、独立数据库卷和随机 localhost 端口，限制容器 CPU/内存。CLI 在目标容器里附加 PID 1，不从 macOS 直接附加 Linux 容器 JVM，也不暴露 JMX 端口。

业务包使用 `dev.perflab.work`，避开 Agent 自身保留的 `com.agentmonitor.*` 命名空间；验收会断言业务热点实际存在，避免只采到 HTTP 入口却误认为业务增强成功。

脚本显式解析 `--context` 的本机 Unix socket，使用本次运行专属的匿名 Docker 配置；不会切换全局 context、修改 Colima DNS、读取镜像仓库登录凭据、操作已有项目或执行全局 prune。首次需要预先拉取公开基础镜像：

```bash
docker --context colima pull gradle:8.14.3-jdk21
docker --context colima pull postgres:17-alpine
docker --context colima pull redis:7.4-alpine
docker --context colima pull rabbitmq:4.1-alpine
```

本机 Colima 的仓库 DNS 若不可用，可在宿主机获取公开官方镜像后导入；无需修改现有 VM 配置：

```bash
python3 examples/docker-performance-lab/fetch-public-image.py \
  --image rabbitmq:4.1-alpine --arch arm64 \
  --output examples/docker-performance-lab/build/images/rabbitmq.tar
docker --context colima load -i examples/docker-performance-lab/build/images/rabbitmq.tar
```

下载助手校验注册表清单、压缩层和解压层的 SHA-256，保存平台与摘要；Intel 主机使用 `--arch amd64`。如果本机 Docker 配置绑定了不可用的 Desktop 登录助手，可对公开镜像使用临时匿名客户端配置，不能修改或输出现有登录信息。

构建成功后可用 `--skip-build` 复用本项目的 `agent-monitor-lab:local` 镜像；源码或 CLI 更新后应重新构建。`--capture-seconds` 默认 60，可设为 40–300。默认自动清理本次项目和卷，保留镜像缓存及全部结果；`--keep` 可留下测试环境供手动检查。

要让 AI 按工程内 skill 自行监控和分析，而非执行固定功能断言，可先只准备环境：

```bash
python3 examples/docker-performance-lab/run.py --context colima --skip-build --prepare-only --keep
# 将路径替换为上一步输出的实际运行目录
python3 examples/docker-performance-lab/skill-capture.py --run /absolute/path/to/run
```

返回 `PREPARED` 不代表性能测试通过。该运行目录的 `control.json` 保存本项目容器 ID、Docker/Compose 命令、端口和两项测试环境变量。`skill-capture.py` 按 [skill 的容器流程](../../skills/java-performance/references/containers.md) 复制 skill、检查身份、预览范围，并为三个 JVM 各采集 90 秒、每 30 秒归档，产生三轮已记账的真实请求；输出 `analysis/skill/workflow.json`，失败也保留已启动 job。它**不生成 AI 结论、不验收、不 ack、不自动移除容器**；AI 随后逐窗取证分析，保存响应和验收结果，再确认消费。重复运行会拒绝，应从已有 job 恢复。所有任务结束并确认还原后，用 control 中的 compose 命令及环境执行 `down --volumes --remove-orphans`，只清理该 project。`--prepare-only` 强制显式指定 `--keep`，避免环境在采集前被自动移除。

采集失败、还原未确认或没有归档窗口时，助手保留各 job 的 status/collect，返回非零退出码并标记 `ATTENTION_REQUIRED`；不能将其当成正常待分析任务重新启动。

桌面 App 目前直接连接本机 JVM。需要在 App 实时观察此测试项目时，可给准备命令加 `--publish-dependencies`，只将本次 PostgreSQL、Redis、RabbitMQ 发布到随机 localhost 端口，然后在宿主机运行同一测试入口服务。通过该运行目录 `control.json` 的 compose 命令查询 `port postgres 5432`、`port redis 6379`、`port rabbitmq 5672`；本机入口配置 `SPRING_DATASOURCE_URL`、`LAB_REDIS_HOST/PORT`、`LAB_RABBIT_HOST/PORT` 和已发布下游的 `LAB_DOWNSTREAM`，不要复用其他项目的依赖。JDBC 本机地址优先用 `localhost`。App 选择该本机 PID，业务包为 `dev.perflab.work`；不能在 macOS App 中直接选择容器 PID 1。

### 有业务数据的本机演示

本机入口还提供 `dev.perflab.commerce` 订单流程，适合在 App 的「调用链」中观察业务类，而不只是 `/cpu`、`/redis` 等技术探针。启动时创建独立的 `demo_products`、`demo_orders` 表，播种三种商品；`POST /shop/orders` 扣减 PostgreSQL 库存、保存订单、向 RabbitMQ 发布 `demo.order.created` 事件并将订单放入 Redis 缓存。`GET /shop/catalog`、`GET /shop/orders/{id}`、`GET /shop/orders` 分别读取商品、订单详情和最近订单。演示数据为虚构客户与商品，不代表真实业务流量。

推荐采集包为 `dev.perflab`，包含 `CommerceController`、`CommerceService`、`LabController` 和 `LabWork`；无需把 `com`、`org` 等第三方库整包纳入。若要让每次演示请求都出现在调用链，可将 `sampling.ratePercent` 暂时设为 `100`。开始监控后运行：

```bash
python3 examples/docker-performance-lab/commerce-load.py --base http://127.0.0.1:<本机入口端口> --duration 180
```

## 验收内容

| 场景 | 实际校验 |
|---|---|
| 三服务与重复调用 | A→B 两次、B→C，按 Trace/Span 父子身份关联四条跨服务边 |
| Spring MVC 路由 | `/items/1` 等合并为 `/items/{id}`，不手工伪造框架路由属性 |
| 慢 SQL | 真实 PostgreSQL `pg_sleep` 与查询；检查 SQL 证据 |
| 连接池等待 | Hikari 两个连接被占用，第三次获取等待，独立采集等待 |
| Redis | 真实 Jedis SET/GET/DEL/PING，核对操作结果及 Redis Span |
| RabbitMQ | 真实 broker 发布、confirm 与 basicGet，核对消息体保持及发布 Span |
| Apache HttpClient 5 | 复用原请求、保留配置，逐次传播新上下文，退出清理自有头 |
| Spring 异步接口 | 等待 Callable 完成；完整请求统计不能只有 dispatch 时间 |
| POST | 二进制请求体完整回显 |
| 错误计数 | 一次真实 503，独立入口失败数应为 1 |
| CPU / 内存 | 固定迭代 CPU 工作和有上限的 4MiB 堆增长，提供真实诊断样本 |
| 生命周期 | 自然 STOP、所有 JVM 仍存活、再次附加，采样为 0 仍计数 |
| JFR / 长采集 | 三个 JVM 诊断与 JFR，gateway 一次挂载按 10 秒归档 |
| AI 证据 | 索引、查询、原值核验、原生 AI 引用验收 |

RabbitMQ 的 broker 确认和消息读取是测试程序检查的业务事实；Agent 当前只生成发布边界 Span，未实现消费端分布式关联。报告继续保留该边界。

## 结果

`build/latest.txt` 指向本次运行目录，包含：

- `summary.json`：断言结果、JVM 还原、采集质量、镜像/CLI 身份及限制。
- `captures/`：原始会话、性能报告、JFR、接口直方图、滚动窗口。
- `analysis/`：跨服务链、规则诊断、可核验的 AI 输出、证据索引查询与小负载数据。
- `logs/`：构建、Compose、CLI 和业务服务日志；失败也保留。

CPU 接口做小规模 A/B/A（采集前 / 采集中 / 停止后）观察，每段预热后固定 36 次、并发 1。只报告原始时延分布；共享 Colima、配额、JIT、JFR 和调度均会影响结果，不将单次差值宣称为 Agent 稳态开销或优化收益。这不是生产容量、长期稳定性或所有 JDK/客户端版本的兼容性证明。

功能验收采用 100% Trace 采样、JFR 开启及 500ms/s 的部分 hook 预算，确保边界证据被保留；不是产品默认 10% 头采样、50ms/s hook 预算。实际配置与质量必须随性能结果一起看。

后续更值得增加：代表性业务负载下的多轮性能回归、JDK 17/21/25 矩阵，以及未支持客户端和消息消费端的专项测试。
