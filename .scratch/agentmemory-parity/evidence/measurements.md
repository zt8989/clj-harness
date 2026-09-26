# 三处原始测量（2026-09-20）

票面上每一句带数字的话都能在这里重跑出来。三节：桥，库里那条观察，`/enrich` 的空/非空对比。

## 一、桥到底把什么交给了 agentmemory 的脚本

假 plugin 目录 + 探针脚本（`probe-post-tool-use.mjs`），走的是**真的桥**（下面这条已原样跑过）：

```sh
mkdir -p /tmp/amp/scripts
cp .scratch/agentmemory-parity/evidence/probe-post-tool-use.mjs /tmp/amp/scripts/post-tool-use.mjs
printf '%s' '{"hook":"PostToolUse","thread_id":"th-1","project_dir":"/tmp/proj","tool_name":"write","tool_input":"{:path \"src/x.clj\", :content \"hi\"}","result":"wrote 2 lines"}' \
  | AGENTMEMORY_PLUGIN_DIR=/tmp/amp node scripts/hooks/agentmemory.mjs post-tool-use
```

```
PROBE keys = ["hook_event_name","session_id","cwd","tool_name","tool_input","tool_response"]
PROBE hook_event_name = PostToolUse
PROBE session_id = th-1
PROBE cwd = /tmp/proj
PROBE CLAUDE_PROJECT_DIR = /tmp/proj
PROBE tool_name = write
PROBE typeof tool_input = string
PROBE tool_input = "{:path \"src/x.clj\", :content \"hi\"}"
PROBE typeof tool_response = string
PROBE agentmemory would extract files = []
```

**六个键的映射是对的**（这是桥的全部工作）；`tool_input` 是**字符串**；`files` 于是为空。最后一行
就是服务端 `extractFiles$1`（要求 `typeof input === "object"`）在那份输入上的结果。

注：这条命令喂的是**手写的** payload，所以 `tool_response` 有值。真机上它是空的——见票 01：
`:post-tool-use` 的 emit 根本不含 `result`（`src/harness/kernel/tools.clj:894`）。

## 二、库里那条观察长什么样

`bridge-selftest` 是走真桥写进去的一条：

```sh
curl -s "http://localhost:3111/agentmemory/observations?sessionId=bridge-selftest&limit=5"
```

```json
{"id":"obs_mu9qvlwq_1a67d52b05a4","sessionId":"bridge-selftest","files":[],
 "narrative":"{:path \"src/x.clj\", :content \"hi\"}","type":"file_write","title":"write"}
```

三条读法：

- **`files: []`** —— 结构化文件归属是空的（票 02 修的就是它）。
- **`narrative` 是打印形式的原文** —— 数据在，只是没结构。
- **`type: "file_write"`、`title: "write"`** —— 工具名一路传到类型判定，这部分是通的。

而且同一会话的摘要是 `"filesModified": ["src/x.clj"]`：**那个路径是模型从 `narrative` 文本里读出来的**，
不是从 `files` 里取的。所以「文件归属丢了」这句话要说得准确：丢的是**结构和按文件召回**，
不是「模型再也看不见路径」。

## 三、`/enrich`：文件列表为空时它什么也不给

```sh
# 空 files：直接拒
curl -s -X POST http://localhost:3111/agentmemory/enrich -H 'Content-Type: application/json' \
  -d '{"sessionId":"probe","files":[],"terms":[],"toolName":"Edit"}'
# {"error":"sessionId (string) and files (string[]) are required"}

# 一个真文件：1375 字符的上下文块
curl -s -X POST http://localhost:3111/agentmemory/enrich -H 'Content-Type: application/json' \
  -d '{"sessionId":"probe","files":["/Users/zhouteng/Documents/workspace/clj-harness/src/harness/kernel/hooks/dispatch.clj"],"terms":[],"toolName":"Edit"}'
```

```
keys: ['context', 'truncated']
context chars: 1375
<agentmemory-file-context>
## /Users/zhouteng/Documents/workspace/clj-harness/src/harness/kernel/hooks/dispatch.clj
- [file_read] read: {"file_path":"…/dispatch.clj"} | <path>…</path>
```

这一段 `[file_read]` 观察是**另一个宿主**（DSH 那条已接好的线）写进去的——同一个服务器、同一个文件，
它拿得到文件上下文，clj-harness 这条路拿不到，差别就在 `tool_input` 是对象还是字符串。

（`pre-tool-use.mjs` 在 `files.length === 0` 时**提前 return**，所以真机上连这个请求都不会发出去；
这里手工发一次，是为了把「本来能拿到什么」量出来。）
