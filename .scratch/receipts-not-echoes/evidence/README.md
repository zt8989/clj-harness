# 走查证据：回执、`job` 回来、通知只报状态

跑法（真后端 + 真前端 + 隔离家，端口由脚本自己挑）：

```bash
node scripts/dev.mjs --scripted .scratch/receipts-not-echoes/evidence/receipts.json --ui-port 5211
```

`receipts.json` 三轮：`job {sleep 1; echo JOB-SAYS-SO}` → `todo_write` + `write` → 收尾一句。
作业在这中间结束，所以**下一轮的第一次调用**（走查里手打的第二句）之前，通知被摆进历史。

## 走查当天看见的（2026-09-22）

### 三张工具卡：答案都是回执，没有回声

对话那一栏，展开工具卡读到的**结果**逐字如下：

```
job · sleep 1; echo JOB-SAYS-SO
参数 {"command":"sleep 1; echo JOB-SAYS-SO"}
结果：job j1 started; its record is …/jobs/<会话>/j1-20260922T205620953-4129.log

todo_write · 1/2 完成
参数 {"todos":[{"content":"起一条作业","status":"completed"},{"content":"读回执","status":"in_progress"}]}
结果：2 items stored for this session (1 in progress, 1 completed).

write · walkthrough-note.txt
参数 {"path":"walkthrough-note.txt","content":"line one\nline two\n"}
结果：wrote 18 chars to …/.worktrees/receipts-not-echoes/walkthrough-note.txt

      The file's anchors have been released: read it to get the anchors for what is there now.
```

- **`todo_write` 的答案里没有清单**：只有条数与状态。清单本身还在两处——工具卡上那句
  `1/2 完成` 与展开后的**参数**（读调用参数，不读答案），以及库里的行。
- **`write` 的答案里没有内容、没有锚点行**：只有「写了多少、写到哪」加一句去 `read`。
  走查里那次 `write` 的入参还在参数格里躺着，所以什么也没丢。
- **`job` 在表里**：卡片标题就是 `job`，答案是 id + 记录路径。（`bash` 那次没在这条脚本里跑到，
  它的参数表由 `harness.kernel.tools-test` 的两处清单用例钉着。）

### 通知：注入在「对话」那一栏是一张卡，「轨迹」那一栏是一格

第二轮的卡片（折叠行写 **`注入的上下文 · job-ended · 133 B`**），点开的字节逐字：

```
<job-ended id="j1">[exit 0]</job-ended>
<command>sleep 1; echo JOB-SAYS-SO</command>
Read what it said with job_output {"job": "j1"}.
```

**133 字节**（`<command>` 那一行是落地当天补的：`j1` 认不出是哪个作业），**没有路径、没有正文**
（记录里那句 `JOB-SAYS-SO` 是命令自己打出来的，不在通知里）。

「轨迹」那一栏第 2 轮 3 个条目：`用户` → `上下文 <job-ended id="j1">[exit 0]</job-ended>` → `助手`：
它落在**提问之后、那次模型调用之前**，客户端从不持有它。

同一份字节在那一场的记录里（`projects/.unbound/<会话>.jsonl`）：

```json
{"source": "job", "type": "message",
 "payload": {"role": "user",
             "content": "<job-ended id=\"j1\">[exit 0]</job-ended>\n<command>sleep 1; echo JOB-SAYS-SO</command>\nRead what it said with job_output {\"job\": \"j1\"}."}}
```

紧跟一行 `CUSTOM / injected-context` 的帧——卡片画的就是它。

### 走查留下的东西

- 那次 `write` 在**工作树根**写下的 `walkthrough-note.txt`（会话未绑定项目，相对路径按进程 cwd 解析），
  走查后已删。
- `ui/src` 一个字节没改：新动词 `job` 与它的两个兄弟一样，图标退到扳手
  （`TOOL_ICONS` 里本来就没有 `job_output` / `job_kill`）。