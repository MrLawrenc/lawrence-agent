# 长时间采集与回访

## 任务机制

`start` 先核对可访问的 JVM 与 OS 进程启动身份、验证调用链配置，再把配置副本和固定 CLI 命令写入新任务目录。独立 Python worker 不依赖发起它的 shell/工具会话，CLI 仍在 worker 的前台运行以保留原生命周期。系统重启、退出登录、休眠、强制终止等不保证任务继续；心跳过期只报告异常，不自动重启采集。

调用链模式使用 `capture --rolling`，一次挂载后由 core 持续接收并归档（默认 5 分钟一窗，单窗 500ms–60m，总期限最多 7 天、最多 10000 窗口），READY 后计时，最终停止时统一还原。完整调用归入接收完整树的窗口，跨窗不拆成虚构链路；每窗报告是 partial，不能复制全会话 exporter ACK。诊断-only 模式继续使用串行独立窗口，总期限含启动/关闭成本，有覆盖间隙。JFR 每次检查点只保留末尾 120 秒/32MiB；检查点可能重叠，不能重复累计或称为完整五分钟录制。

```text
<JOB>/
  job.json                 # 状态、目标启动身份、心跳、当前/已完成窗口
  config.yml               # 启动时的配置副本（如提供）
  events.jsonl             # 稀疏的开始/结束/停止事件
  worker.log
  native/                  # 调用链模式：唯一 CLI 和主会话；含 core 原生 windows
  windows/000001/
    stdout.json            # 诊断模式的 CLI 结果；原生调用链结果在 native/ 下
    stderr.log
    result.json            # 最终质量、还原确认与会话路径
    captures/<time>/session-<id>/...
  consumers/ai.json        # 显式 ack 才更新的已处理游标
  analysis/                # AI 后续创建的分析和验收结果
```

默认运行数据位于 `~/.local/state/agent-monitor/jobs`，不写进 skill；用 `--work-dir` 更换。其他窗口不会被原配置的 retention 删除。“完成窗口”只代表 worker 已处理其结果，必须继续检查 accepted、quality；原生窗口 restored=null，结束后统一查看 job.restored 和 masterQuality。失败窗口也保留证据。元数据摘要失败时，collect 仍返回该窗及 summaryError，不能把缺少摘要当作没有采集结果。

默认磁盘预算 2GiB，约每 2 秒检查目录占用，空间不足 128MiB 或触及预算即请求温和结束。它是软额度，单次突发写入可能越过阈值，不是硬配额；不会自动删除旧窗口。可显式 `--max-bytes` 调整，不自动无限扩容。

## 查看、停止、恢复读取

```bash
python3 "$S/scripts/perf.py" status --job <JOB>
python3 "$S/scripts/perf.py" collect --job <JOB> --consumer ai --limit 5
python3 "$S/scripts/perf.py" ack --job <JOB> --consumer ai --cursor <nextCursor>
python3 "$S/scripts/perf.py" stop --job <JOB>
```

`collect` 从 consumer 上次确认后继续；未确认批次会重复返回。先保存/验收分析，再 ack。不能只凭路径或状态猜测性能结论；从头复查可换 consumer 或用 `--after 0`。

同一任务不会自动重启或续采：新对话先读保存的 job 路径，确认状态。目标退出或 PID 启动身份变化时停止，不能把复用该 PID 的进程当同一目标。CLI 原有 JVM 租约仍生效，同一目标的 App/CLI 冲突会报告失败，不能删除租约规避。

停止只向 worker 正在持有的 CLI Popen 子进程发 SIGTERM，利用 CLI 原有 shutdown hook 还原；不发信号给业务 PID，也不从持久 PID 文件盲目 kill。等待收尾最多 30 秒，超时标为还原未确认，不强杀。SIGTERM 下 CLI 可能没有最终 stdout，只有 TraceServer 已写出的 COMPLETED+captureComplete=true 报告可补充确认；INCOMPLETE 不能据此猜测已还原。

终态包括 completed、degraded、stopped、quota_reached、target_changed、failed、restore_unconfirmed、interrupted。degraded 允许分析已核实的局部证据；restore_unconfirmed 优先确认目标状态，不再续采。

## AI 自动回访

worker 只采集和记录状态，不调用 AI、不上传数据、不发通知。启动后立即交付 job 路径；不要让模型空等数小时，也不要承诺 skill 能自行唤醒模型。

用户明确要求“采集完分析/稍后通知/持续排查”时，使用当前宿主提供的正式调度/自动化工具安排回访；支持当前线程心跳时优先使用。回访任务内容包括：

- skill、job 和分析输出的绝对路径，以及目标应用与业务问题。
- 运行 status/collect，处理新窗口后保存 AI 输出、运行 ai verify、再 ack；每次限制批量，hasMore 时继续后续批次。
- 运行正常且没有新结果时保持安静；只报告有意义发现、采集异常、所缺信息或结束。
- 终态且窗口全部处理后停用回访；失败验收的发现不能作为确认结论发出。

没有调度工具时，明确说明“后台采集会继续；AI 在下次对话读取此 job”，提供 status/collect 命令。不要用系统 cron、常驻 AI 轮询、伪造自动化指令或未经授权的外部通知代替。
