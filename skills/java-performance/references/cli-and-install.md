# CLI 检测与独立安装

`S` 为本 skill 的绝对目录。本目录没有 JAR、JDK、桌面 App 或运行数据，可以直接复制到别的工程或技能目录。

## 当前工程内

```bash
python3 "$S/scripts/perf.py" ensure
```

检测次序：显式 `--cli` / `MONITOR_CLI` → 校验已有托管缓存 → PATH 中兼容的 `monitor` → 当前 skill 所在工程的 `monitor-cli-all.jar`。缺少兼容产物时执行 `:monitor-cli:shadowJar`，可能需要联网拉取构建依赖。已有兼容产物不会自动重建；缓存缺少 rolling/evidence/requests/diagnose/distributed 命令时从所在工程升级。需要最新源码时先自行构建。`--no-build` 禁止自动构建。

安装把 CLI fat JAR（内含 Agent）复制到用户目录 `~/.local/share/agent-monitor/runtimes/<SHA256>.jar`，创建 `bin/monitor` 与 `install.json`；不修改 PATH、shell 配置、系统目录或桌面 App。`MONITOR_SKILL_HOME` / `--install-root` 可换位置。脚本返回可直接调用的命令，不要求 PATH 已配置；托管安装的 worker 固定使用所选内容哈希版本，后续安装不替换正在运行的 JAR。显式外部可执行文件/PATH 中的 CLI 则沿用其安装方的更新策略。

JDK 通过 JAVA_HOME 或 PATH 查找，要求 17+。缺少 JDK 时明确报告所需条件，不能声称 CLI 已可用。

## 本目录单独复制后

已安装 CLI：

```bash
python3 "$S/scripts/perf.py" ensure --cli /absolute/path/to/monitor
```

本地完整 JAR 或源码工程：

```bash
python3 "$S/scripts/perf.py" ensure --cli /absolute/path/to/monitor-cli-all.jar
python3 "$S/scripts/perf.py" ensure --source-root /absolute/path/to/lawrence-agent
```

发布后可用受信的 HTTPS 地址与固定 SHA-256 安装：

```bash
python3 "$S/scripts/perf.py" ensure --url <HTTPS_RELEASE_JAR_URL> --sha256 <SHA256>
```

远程包最多 128MiB，先校验摘要再执行；重定向也必须为 HTTPS。此项目尚未在 skill 内配置公共发布地址，缺少来源时报告缺失，不能拼一个“latest”地址或自动执行网络安装脚本。Skill 不是系统软件包管理器。

## CLI 操作

通过 `run --` 透传 CLI 参数，JSON 输出和退出码保持原样：

```bash
python3 "$S/scripts/perf.py" run -- config init --output /absolute/path/monitor.yml --package com.example.orders --json
python3 "$S/scripts/perf.py" run -- config validate --config /absolute/path/monitor.yml --json
python3 "$S/scripts/perf.py" run -- scope preview --pid <PID> --config /absolute/path/monitor.yml --selected-only --json
python3 "$S/scripts/perf.py" run -- capture --pid <PID> --config /absolute/path/monitor.yml --duration 60s --json
```

创建配置拒绝覆盖。空范围草稿不能采集；默认配置可能采集参数、返回值、SQL 参数，按任务需要选择，不默默扩大用户授权的范围。JVM 必须在本机且允许当前用户 Attach。远程机器需在目标所在主机运行 CLI，复制 skill 不会赋予远程进程访问能力。

退出码 0 成功；2 参数/配置；3 执行失败；4 还原未确认；5 Trace 或诊断数据不完整（分别看实际命令）；6 AI 验收失败。capture 的退出码按还原/Trace 判定，独立 `diagnostics` 字段可能仍降级。

Windows 可通过 `ensure` 安装 JAR，并前台使用 CLI；目前后台 worker 的信号与进程身份验证仅实现 macOS/Linux，不应承诺 Windows 后台还原保障。
