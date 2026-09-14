# 08 — hook dispatch：spawn、退出码、超时、审计行

**What to build:** 一次 hook 触发就是一次 dispatch：按 matcher 选中声明 → spawn 命令 → payload 走 stdin →
读退出码与 stdout/stderr → 得出结论 → 落审计行。退出码语义：**0 放行；2 阻断**（stderr 作为理由回喂模型）；
**其他非零**按点定义的失败语义（门禁型 fail-open 或 fail-closed，信息型静默记录）。超时与命令不存在/崩溃
**都不炸 run**。每次触发落一行 `hook/<point>` 审计行。

没有任何声明时 dispatch 是 no-op：帧序列与审计线与"没有这个能力"时**逐字节相同**——默认全放行，
这是这一票最容易写坏的回归面。

**Blocked by:** 07

**Status:** ready-for-agent

- [ ] payload 是 stdin 上的一份 JSON，字段名 snake_case，逐点补齐该点的事实（工具类的点带 `tool_name` /
      `tool_input`，会话类的点带 `thread_id` / `project_dir`）；UTF-8 边界显式
- [ ] 命令经钉住的 Git Bash spawn（沿用 bash 工具在 Windows 上的经验：`System32\bash.exe` 是 WSL 启动器，
      从 JVM 调用会静默空输出）
- [ ] 退出码三条各有用例：0 放行、2 阻断（且**阻断理由取 stderr**）、其他非零按点定义的失败语义
- [ ] 超时（声明里的 `timeout`）与命令不存在/崩溃都不炸 run，各自的失败语义有用例
- [ ] stdout 上的高级决策（JSON）能被读出；读不懂时**不静默吞掉**——按点定义降级，并有测试锚定降级行为
- [ ] 每次触发落一行 `hook/<point>` 审计行：点名字、命中了几条声明、结论；run 内触发带 runId，
      run 外（如重建）为 null
- [ ] 无声明时整个 dispatch 是 no-op：一轮 run 的帧序列与审计线不因这个能力存在而改变
- [ ] 全绿，新增断言
