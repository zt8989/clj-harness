# 一场会话的 system message，从头到尾是什么样

这份文件是把**一场会话实际被喂进去的东西**按顺序写出来，到此为止——**冻结的那一段就是 `prompt.md` 本身**，
在这里复制一份只会多出一份会走样的副本，所以只指路、不重抄。

真正的组装在每一次 run 上发生（`harness.cap.system-prompt/assemble`）：

| 段 | 谁写的 | 变不变 |
| --- | --- | --- |
| 身份、一组工具纪律 | `prompt.md`（冻结，进程首次读入后不再读） | 不变——它说的是每场会话都成立的事 |
| `<provider>` | SystemPrompt 点的一条内建 hook（见 `.scratch/system-prompt-blocks/issues/04`） | 每次 run 派生 |
| `<project>` | 同上（`harness.cap.system-prompt/project-block`） | 每次 run 派生 |
| `<env>` | 同上（`env-block`，事实来自 `harness.infra.env/lines`） | 每次 run 派生 |
| `<instructions>` / `<skills>` | 开场块（`harness.cap.preamble`），会话出生那一轮注入 | 只进一次，此后是历史 |

顺序为什么这么排：**升降的东西排在后段**。冻结开头是前缀缓存的锚，一条随 run 变动的句子混进去，
每次变动都要付一次冷前缀的价钱；反过来，把事实冻在开头，代价是那句会开始说谎。
工具纪律只写参考提示词里的那一组（`read` / `write` / `replace` / `insert` / `glob` / `grep` /
`bash` / `job` / `job_output` / `job_kill` / `web_search` / `web_fetch`）；其余工具
（`undo_last_replace` / `todo_write` / `skill` / `eval` 与 MCP 进来的那些）
不在这里写——它们的信息由工具表里各自的 description 承担。

---

## 冻结段

`prompt.md`，逐字。读它，别读这里的转述。

## hook 追加段（每次 run 派生）

<provider>
vendor: <生效的 vendor> -- model: <生效的 model id> -- reasoning effort: <有档才说>
</provider>

<project>
bound to: /Users/zhouteng/Documents/workspace/clj-harness
Relative paths in the file tools resolve against it, and bash runs with it as its working directory. Absolute paths are never redirected.
A read/write/edit path that resolves outside every free path below parks for human approval before it runs:
  - /Users/zhouteng/Documents/workspace/clj-harness -- this project
  - /Users/zhouteng/.clj-harness -- this harness's configuration home; reading your own configuration there is allowed
  - /private/var/folders/9_/vz1tw99s6bn2cd7gpsxc97nc0000gn/T -- the machine's temporary directory; scratch that is meant to be thrown away
  - /private/tmp -- the machine's temporary directory; scratch that is meant to be thrown away
  - /Users/zhouteng/.agents/skills -- where this session's skills live
  - /Users/zhouteng/Documents/workspace/clj-harness/.agents/skills -- where this session's skills live
</project>

<env>
platform: macos (Mac OS X 15.7.3, aarch64)
shell: bash (at /opt/local/bin/bash); commands run through `/opt/local/bin/bash -lc`
available: rg, fd, jq, git
not found: (nothing from the list)
</env>

上面三块是**样例**：`<project>` 的围栏清单是 gate 自己的 `harness.cap.project/fence`，项目开
`:approval {:strict true}` 时项目目录会从里面退场（配置家、技能根与本机临时目录不退场），未绑定时
整块换成一句「没有绑定」。样例里的路径只对写这份文件时的那场会话成立。

## 开场段（会话出生那一轮）

<instructions path="/Users/zhouteng/Documents/workspace/clj-harness/AGENTS.md">
…AGENTS.md 的全文，逐字注入（测试期 `~/.clj-harness` 只读、README 只四节等都在里面）…
</instructions>

<skills>
…本会话可见的 skill 索引（名字 + 一句话描述），正文按需用 `skill` 工具读…
</skills>