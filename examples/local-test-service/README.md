# 本机 Java 测试服务

这个服务只依赖 JDK 17+，适合在桌面 App 中验证目标切换、范围选择和方法采集。监听随机 `127.0.0.1` 端口，不连接外部服务。

从仓库根目录启动：

```bash
mkdir -p build/local-test-service/classes
javac --add-modules jdk.httpserver -d build/local-test-service/classes examples/local-test-service/TestHttpService.java
java -Djava.rmi.server.hostname=127.0.0.1 --add-modules jdk.httpserver -cp build/local-test-service/classes demo.monitor.TestHttpService
```

启动后输出 `READY pid=<PID> port=<PORT>`。在 Agent Monitor 中选择该 PID，在「采集配置」纳入 `demo.monitor` 包，再点击「开始监控」。已生成的示例 YAML 可用 `monitor config init --package demo.monitor` 重建。

`java.rmi.server.hostname` 固定本机 JMX/RMI 地址，避免目标 JVM 把管理连接指向局域网地址，导致「JVM 概览」中的指标采样出现 JRMP/EOF 错误。

另一个终端可访问 `http://127.0.0.1:<PORT>/health`、`/work`、`/slow`；后两者会调用 `demo.monitor.TestHttpService$BusinessWork` 的方法。按 Ctrl-C 停止服务。停止监控并确认还原后再停止目标 JVM。
