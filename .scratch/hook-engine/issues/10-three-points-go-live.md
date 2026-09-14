# 10 — SessionStart / PostToolUse / Stop 三个点接线

**What to build:** 三个**非门禁**点真的会触发：会话的第一步起 SessionStart（payload 区分新建与恢复）、
每次工具**成功**执行后 PostToolUse、每轮 run 正常收尾时 Stop。命令真跑、payload 真带该点的事实、
审计行真落。这一票把"引擎里有数据但从不触发"变成"能看见它动"——它是接线范式的第一票，门禁（10）与审批
（11）都照它的样子接。

三个点都是信息型的：**它们的结论不影响 run 的去向**，且不能拖慢 run（到 `timeout` 就放下继续）。

**Blocked by:** 08

**Status:** ready-for-agent

- [ ] SessionStart 在一个 thread 的第一个 run 起触发一次，payload 的 source 区分**新建**与**恢复**
      （日志重建后续跑的那条路径给 resume）；同一 thread 后续的 run 不再触发
- [ ] PostToolUse 在工具**成功**后触发，payload 带 `tool_name` 与 `tool_input`；工具失败走的是另一个点
      （`PostToolUseFailure`），本票不接线——在落地说明里写明这个缺口是有意的
- [ ] Stop 在 run 正常收尾时触发；run 因错误终止时不触发（那是 `StopFailure`，同为未接线的点）
- [ ] 三个点的触发都**不改 run 的帧序列**：一轮 run 的 AG-UI 帧与没有这个能力时逐字节相同
- [ ] 信息型点的失败语义写死并被用例覆盖：命令超时/崩溃/不存在时，run 照常走完
- [ ] 端到端：一个声明了 Stop 命令的会话跑一轮，命令的副作用（写文件/落 stdout）与 `hook/stop` 审计行
      都能看到；同一个会话没有声明时两样都不出现
- [ ] 全绿，新增断言
