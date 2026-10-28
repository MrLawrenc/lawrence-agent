# Docker / Colima 容器采集

适用于本地 Docker 引擎中已明确授权的目标容器。macOS 无法直接 Attach Linux JVM；即使容器显示 PID 1，也不能在宿主机用 `start --pid 1`。不默认切换 Docker context、重启引擎、扩大容器权限或安装容器系统软件。

## 选择执行位置

显式使用用户给定的 context，例如 `docker --context colima`。先 inspect 得到完整容器 ID、运行状态和 `State.StartedAt`，再通过 exec 核对 Python 3.9+、JDK 17+、同用户 Attach 权限，以及 `jvm list/identity`。镜像内有 JRE 而无 Attach，或没有 Python 时，说明缺失条件；可以在已授权的开发测试镜像内补充，不能把宿主 PID 当作替代。仅有 JDK 时可在容器内用前台 CLI 有界采集，但没有 skill 后台续读保障。

将本 skill 目录复制到目标容器的临时目录；CLI 单独提供已验证的 JAR（或其已有可执行文件）。不把整个工程、桌面包或历史采集数据复制进去。不暴露远程 JMX。操作该容器时使用完整 ID，重建后新 ID 需要重新核对。

下面的路径和 ID 需用实际 inspect、复制结果替换，示例假设已有 `/opt/monitor/monitor-cli.jar`、`/config/service.yml` 和可写持久挂载 `/captures`：

```bash
docker --context colima cp "$S" "$CONTAINER_ID:/tmp/java-performance"
docker --context colima exec "$CONTAINER_ID" python3 /tmp/java-performance/scripts/perf.py \
  ensure --cli /opt/monitor/monitor-cli.jar --install-root /tmp/monitor-runtime
docker --context colima exec "$CONTAINER_ID" python3 /tmp/java-performance/scripts/perf.py \
  run --cli /opt/monitor/monitor-cli.jar --install-root /tmp/monitor-runtime -- jvm list --json
docker --context colima exec "$CONTAINER_ID" python3 /tmp/java-performance/scripts/perf.py \
  run --cli /opt/monitor/monitor-cli.jar --install-root /tmp/monitor-runtime -- scope preview --pid "$JVM_PID" --config /config/service.yml --json
docker --context colima exec "$CONTAINER_ID" python3 /tmp/java-performance/scripts/perf.py \
  start --cli /opt/monitor/monitor-cli.jar --install-root /tmp/monitor-runtime --pid "$JVM_PID" --mode capture \
  --config /config/service.yml --duration 2m --window 30s --work-dir /captures/jobs
```

`ensure` 检查兼容性，不能证明 JAR 对应当前源码；修改 Java 后显式重建并校验实际使用文件的 SHA-256。`start` 返回的 `jobDirectory` 是容器内路径。持续时间从实际采集启动算起，预检和 Attach 另占时间。

安装位置要在 ensure/run/start 中保持一致；也可每次 exec 都传 `-e MONITOR_SKILL_HOME=/tmp/monitor-runtime`。仅在 ensure 中指定安装目录，不会改变后续进程的默认目录；HOME 只读时尤其不能漏传。长期使用需把安装目录和输出放在合适的可写挂载中。

## 读取、分析和停止

继续在同一容器内执行 `perf.py status/collect/stop/ack --job "$JOB"`；离开 exec 后 worker 仍运行，但容器停止会使其停止。跨服务每个 JVM 一个独立 job，不共用 PID、游标或还原结论。大多数采集不会立刻产生 Trace，等待 CLI 的 READY 后再驱动测试流量。

为每个任务保存 context、容器完整 ID/StartedAt、JVM identity、CLI SHA、jobDirectory、宿主挂载根和容器挂载根。只对 inspect 确认的挂载前缀做路径映射；例如容器 `/captures/jobs/X` 对应宿主 `/actual/run/captures/jobs/X`。原始 job、会话和 manifest 保留容器路径，不能批量改写成宿主路径来让验收通过。`collect` 输出中的绝对路径也属于容器，不能直接作为宿主命令参数。

推荐在容器内通过 `perf.py run -- evidence … / ai verify …` 取证和验收；AI 在宿主读取挂载文件，分析响应写到对应 job 的 `analysis/`，再把容器路径传给 verifier。也可停止后复制原始会话到宿主，以真实宿主 session 参数离线读取，身份和引用保持不变。滚动窗口始终按 partial 分析，还原以 master 最终确认判断。

每窗完成分析并保存验收结果后才 ack。最终核对 master 还原、采集质量、JVM 仍存活，随后清理**本次创建**的测试项目；目标不是自建测试环境时不停止其容器。容器已消失或重启，保留证据并记录中断，不删除租约、强杀目标或把旧 job 当成新 JVM 继续。
