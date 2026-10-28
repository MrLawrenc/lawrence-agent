# 范围展示、性能和 AI 分析验收

> 历史验收记录：以下数字、版本和路径属于文中所述测试，不代表当前工作区已重新验证。当前使用入口见 [文档导航](README.md)。

日期：2026-10-27。运行环境：macOS arm64，JDK 21.0.12.1。以下数据属于本机受控合成负载，不能代替用户业务的容量或生产开销结论。

本文基准记录来自加入原生 rolling/自适应预算之前，保留为历史测量，不能用于声称新功能的生产开销或速度提升。当前核心契约和最新功能验收见 [核心采集与证据](core-capture-and-evidence.md)。

## 界面归属

- 顶部“全局待采集”是所有已发现类按规则匹配的总量，不等于实际增强成功数。
- 右侧“当前包”始终展示完整包名，并明确包含子包；“本包范围/其中纳入采集”不受搜索条件影响。
- 列表底部只展示“当前列表/其中纳入采集”和当前搜索词；左侧独立展示目标 JVM 索引规模。
- 2250 / 35 / 1 / 141307 的合成场景实测通过：全局纳入 2250、当前包 35、搜索命中 1、目标索引 141307。
- 切包或搜索等待时清除旧列表并禁用本页操作；实际增强结果不会被下一轮规则预览覆盖。YAML 应用后同时刷新主摘要和开始按钮。
- 删除缺少实测依据的 CPU/内存百分比和风险分档；JVM 指标为进程总量，不代表监控增量。

## 索引查询

15 次预热、40 次计时，使用同样的合成索引（141307 类，其中全局选中 2250，浏览包内 35）。优化前记录单独保存在 scope-index-before.json；当前可复现任务还同时运行使用当前规则匹配器的旧全表扫描查询作为参考。两次运行存在调度差异，不据此承诺固定加速比例。

| 操作 | 优化前中位数 | 优化后缓存规则中位数 | 优化前每次分配 | 优化后每次分配 |
| --- | ---: | ---: | ---: | ---: |
| 浏览 35 类的包 | 6.968 ms | 0.020 ms | 59,686,000 B | 1,192 B |
| 全局文本搜索 | 13.624 ms | 3.333 ms | 52,895,544 B | 23,120 B |

规则投影按完整 scope 缓存；包查询使用排序索引的前缀区间；大小写归一化预先完成。新规则/新索引会重新计算，规则改变时仍有全局工作量。当前冷构建索引并计算规则的中位数为 49.762 ms，放在后台执行，不能按缓存查询的数字描述首次加载。

JavaFX 的真实控件使用同规模 fixture 完成加载、构树、切包和两次搜索，观察到最大 pulse 间隔 33.651 ms。该值包含 OS 调度，仅记录本次交互，不证明持续 60 FPS。未把真实 JVM 类枚举/Attach 算入查询微基准。

## 真实 Agent 对照

使用真实子 JVM，预加载 2250 个不同业务类，加一个批次根类；实际增强 2251 类、失败 0。每批调用 2250 个方法，每个方法进行 2048 轮运算。JVM 参数为 -Xms128m -Xmx768m -Xbatch；同步编译用于减少后台 JIT 噪声，与用户 JVM 设置可能不同。采样 100%，参数/返回值捕获开启，OTLP+file；HTTP/JDBC 关闭。

A/B/A 连续阶段：未挂载预热 10 批、已挂载预热 3 批、还原后预热 10 批，每阶段保留 3 批原始测量。每批之间等待本机接收完成，此等待不计入批次业务计时，所以这不是输出饱和或业务服务吞吐测试。

| 阶段 | 批次 wall time 中位数 | 工作线程 CPU 中位数 | 进程 CPU 中位数 | 阶段末 RSS |
| --- | ---: | ---: | ---: | ---: |
| 未挂载 | 34.488 ms | 34.380 ms | 34.393 ms | 70.2 MiB |
| 已挂载 | 44.608 ms | 40.772 ms | 73.089 ms | 261.6 MiB |
| 确认还原后 | 33.529 ms | 33.489 ms | 33.512 ms | 267.5 MiB |

挂载到 READY 9223.5 ms；停止并确认还原 267.2 ms。6 批共入队/接收 13506 条 Span，队列丢弃、投递丢弃、目的端拒绝、未接收、Collector 丢弃、pending 全为 0。

采集有可见的 CPU、耗时与内存成本。仅 3 个测量批次不能估算稳定 P95；连续阶段仍受 JIT、GC、调度和负载影响。RSS 包含已加载 Agent、遥测及 JVM 保留空间，停止后未立即回落不能单凭此表判定泄漏。这些结果不外推成用户业务的固定 CPU/内存增量。

首轮测试曾因分析投影同时保存顶层字段及等价 OTLP 属性，触及 32MiB 组装预算并明确拒收。修复为仅保留一次等价信息后，13506 条 Span 完整接收；原始 accepted protobuf 转发仍保留全部字段。首轮失败记录保留在 agent-scale/result-before.json。

## AI 提示词与输出验收

导出的 ai-analysis-prompt.txt 和界面复制使用同一个 v2 模板，并附 reports/ai-analysis-output.schema.json。包括质量门槛、采样/截断边界、精确 JSON Pointer/Trace ID/方法/单位、事实与假设、源码缺失说明、最多五项发现、单项修改与同负载 A/B/A 复测计划；不强制凑出性能缺陷，也不承诺虚构的百分比收益。

脚本 scripts/verify-ai-analysis.py 会交叉核对 session/report 的 ID、入队/接收及丢弃计数、完整性、schema、引用文件范围、Pointer 实际值、最近的方法/Trace 上下文、常见字段单位及嵌套 JSON 布尔/数字类型。包含 JSON/gzip 证据支持。11 个测试覆盖正确响应和虚构/缺失/单位错配/跨会话等拒绝场景。

对先前 IDEA 的 16 条实采 Span 做了一次本地助手分析示例：actionPerformed 观测到 28.694 ms，但只纳入该入口类，不能把未覆盖子调用的 self time 当作纯 CPU 自耗时；requiresProject 虽出现 15 次，累计仅 0.157 ms，不能只凭调用次数优先优化。示例附 3 个经验证的真实引用。

这是一次有证据约束的分析示例与程序化验收，不是跨模型质量评测。校验通过只证明格式、质量门槛和引用一致，不证明根因或收益成立。真实业务仍需源码及受控复测。

## 重现与产物

```bash
./gradlew check :monitor-app:shadowJar :monitor-cli:installDist :monitor-cli:shadowJar
./gradlew :monitor-core:scopeBenchmark :monitor-core:agentBenchmark
./gradlew :monitor-app:uiPreview --args=--verify
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify-ai-analysis.py --session <SESSION> --response <AI_JSON>
```

性能任务不加入日常 check，以免把机器相关计时变成不稳定功能门槛。测试只启动自有子 JVM。

- monitor-core/build/performance/scope-index-before.json、scope-index.json
- monitor-core/build/performance/agent-scale/result.json 与 captures/ 会话
- monitor-app/build/ui-preview/scale-interaction.json、scope-large-index.png
- monitor-core/build/performance/ai-analysis/idea-response.json、validation.json

本轮完整 Java 回归 102 项、Python 验收测试 11 项、UI 交互 35 项通过；官方 Collector 互通复测通过。原生安装包未重新生成。构建目录的测量产物不进入 Git。
