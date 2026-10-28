# 文件维护与清理

ignored、untracked、日期较早或构建目录中的文件不一定无效。先查看 `git status --short`，确认用途和引用，再按类别处理。保留已有未提交代码、配置和文档。

| 文件类别 | 处理方式 |
| --- | --- |
| `.DS_Store`、Python 字节码缓存 | 可再生，使用下方清理脚本 |
| `.gradle/`、模块 `build/classes`、`build/tmp` | Gradle 状态和中间产物；重构后需重新构建，运行构建时不要手工删除 |
| `build/install`、`build/libs`、内嵌 Agent | 工具或 skill 可能正在使用；改源码后重建分发，不能只凭旧 JAR 验证新行为 |
| `build/performance`、`verification`、`skill-verification` 等 | 含真实测量、会话与验收证据；确认归档/不再需要后才定向清理 |
| 会话、JFR、heap dump、日志、后台 job 游标 | 按用户需求及配置保留；不做自动全盘清理，不修改原证据 |
| `agent-monitor.pid`、会话锁/租约 | 先确认所属进程与还原状态；不能只凭 PID 文件较旧判断失效 |
| 历史文档和测试 fixture | 保留可复查内容，标注历史范围；删除前查引用、构建和测试用途 |
| `.agents/skills/java-performance` | 有效相对符号链接，不是重复数据；只维护 `skills/java-performance` |

## 日常清理

```bash
# 默认仅预览路径、数量和大小
python3 scripts/clean-workspace.py
# 确认预览后执行；任务已授权清理时可直接执行
python3 scripts/clean-workspace.py --apply
```

脚本仅处理仓库源码目录中的 `.DS_Store`、`__pycache__` 和 `.pyc/.pyo`，跳过符号链接、`.git`、`.gradle`、所有 `build` 目录与隐藏目录。它不删除会话、日志、PID、锁、JAR、构建证据或仓库外缓存。Python 测试会再次生成字节码缓存，复现后重新清理即可。

`./gradlew clean` 会删除模块 build 目录，也包括其中的实测证据和分发包，不作为日常清理默认动作。确需使用时先归档所需证据，并停止依赖这些分发文件的任务。不要运行 `git clean -fdx` 清理整个工作区。

## 文档有效性

能力变化时检查 README、对应 docs、skill 引用与会话提示词是否一致。测试数量、性能数字和本机路径必须标注所属验证记录；清理后路径缺失就说明需要重现，不补造报告。提示词和 schema 源统一维护在 monitor-core，历史会话保持其生成时契约。
