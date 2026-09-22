# 02 — 命令侧的 fact 值改成「JSON 装得下就装」

**What to build:** `dispatch.clj:250` 现在把每个 fact 用 `str` 渲染（`(payload-of p thread-id fact str)`），
于是 map 值到命令手上是**打印形式**：`tool_input` = `"{:path \"src/x.clj\", :content \"hi\"}"`。
改成「JSON 装得下就装，装不下退回打印形式」——字符串还是字符串、map 变对象、`nil` 还是 null。

**Status:** ready-for-agent

## 为什么要紧

agentmemory 的服务端从 `tool_input` 里取文件（`extractFiles$1` 要求 `typeof input === "object"`），
所以今天**每条观察的 `files` 都是空的**——按文件召回、文件图、`/agentmemory/file-context` 这一层
接不上；PreToolUse 的 enrich 直接提前 return。实测（`evidence/measurements.md`）：

```
PROBE typeof tool_input = string
{"sessionId":"bridge-selftest","files":[],"narrative":"{:path \"src/x.clj\", \"content\":…
```

数据没丢（原文在 `narrative` 里，模型读得出来，那条会话的摘要就从文本里读出了 `src/x.clj`），
丢的是**结构化归属**。

## 决策

- **改在内核，不在桥里。** 桥里写一个「跟着 `pr-str` 走的第二个 Clojure 方言」能修好这一个消费者，
  而 Claude Code / CodeBuddy 的 `tool_input` 本来就是对象——改内核是 3 行，所有命令侧 hook 一起受益。
- **`jsonable` 是死代码，先决定它的去向。** `dispatch.clj:51` 那个 helper 的注释写的正是这件事
  （「编码器 refuses 的值退回打印形式」），但**没有任何调用点**。注意它现在返回的是**已经编码好的
  字符串**——直接拿去当 `payload-of` 的 `value-fn` 会**双重编码**（外层还有一次
  `json/write-str`）。要么把它改成返回「值本身或打印字符串」，要么删掉、在 `fire` 那里写清楚。
- **`project_dir` 的 null 不许动**：它本来就被两边特别对待（"a project_dir is allowed to be an
  honest null"），`nil` 能被 JSON 编码 → 仍然是 null，不是 `"nil"`。
- **`:run` 侧一个字不改**：它拿的一直是有类型的值（`payload-of … identity`），两边就此**只差
  一步之遥**：命令侧从此也拿得到结构。
- **契约变了，文档与校对脚本要一起变**（这是这一票真正的成本）：
  - `docs/architecture/hooks.md`：「契约」那条「payload 值都是文本」的 bullet，与「命令是在什么
    条件下被问的」里那条（`tool_input` 到手上是打印形式）——改成「JSON 装得下就是值本身，装不下
    才是打印形式」，并给出两种例子；
  - `hooks.edn.example`：payload 那一段同样的话；
  - `dev/scratch_hooks_config.clj`：那条 `"a non-string value arrives as its PRINTED form, not as
    JSON"` 的断言**现在会红**，改成两条（map → 对象；不可编码的值 → 打印形式）。

## 验收

- [ ] `dispatch_test` 补两条：map 值到命令手上是**对象**；不可编码的值（`#{:a}`、惰性序列）
      退回**打印形式**且不炸整个 payload
- [ ] 一个字符串 fact 仍然是字符串（不是被二次引号包起来的东西）
- [ ] 真跑一次写文件的工具 → `/agentmemory/observations` 里那条观察 **`files` 非空**
      （这一条是这票的真正判据，比任何断言都直白）
- [ ] 同一份 payload 的 `:run` 侧**逐字不变**（它本来就拿有类型的值）
- [ ] `clojure -M:dev -m scratch-hooks-config` 与 `-m scratch-mcp-config` 全绿（改过的断言在内）
- [ ] 全量绿

## 落地提示

- `payload-of` 的 `value-fn` 是唯一的差别点（`dispatch.clj:72-88`），调用在 `fire` 的 250–251。
- 别忘 `docs/architecture/hooks.md` 里两处、`hooks.edn.example` 一处、校对脚本一处——
  「文档说的就是引擎做的」是这几份文件的全部价值。
