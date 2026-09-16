# 03 — 内建 hook：工具清单

**What to build:** 内核注册自己的第一条 hook：它在 `SystemPrompt` 点追加 `<tools>` 块。从模型的角度，
它读到的工具集**永远是真的**——会话注册了一个工具、关掉了一个工具、以后 MCP 桥进来一个外部工具，
下一次 run 的 system 消息里就是那样。`prompt.md` 里那句会过时的枚举（`Tools: read, write, edit, bash, eval.`）退场。

这也是「一条声明跑什么」的第二种形态第一次真的派上用场：这条 hook 是进程内的函数，不是命令。

**Blocked by:** 02（`SystemPrompt` 点与追加）

**Status:** ready-for-agent

## 验收

- [ ] 内建的那条作为**一条 hook 注册**（`source :built-in`，`:run` 一个进程内函数），不是别的一套机制：
      它在 `effective-hooks` 里看得见、有关得掉/打不开的开关、触发时落同样的审计行
- [ ] 块报三件事，三件都是活的：
      - **可用**的是哪些（该 thread 的有效工具表，含会话注册的、以后 MCP 桥进来的）
      - **本会话关掉了哪些**（另起一行；关闭不是隐藏——模型能 `session-enable!` 打开，所以它必须看得见）
      - **哪些来自外部程序**（`mcp__<server>__<tool>` 这类不是本内核实现的，失败的样子也不同）
- [ ] **不复制每工具的描述**：描述已经在 wire 的 `:tools` 数组里，这里是白花 token；这一块只报集合
- [ ] `prompt.md` 里工具那两行枚举删掉；**这一块自己带标签**（引擎不为它做包装）
- [ ] 事实不变时，连续两次 run 的 system 文本**逐字节相同**（prefill 继续命中的前提，用断言钉住）；
      会话关掉一个工具之后，下一次 run 的文本就变
- [ ] 会话 `session-disable!` 掉这条内建 hook 之后它不再追加，再打开就回来（决策 7 的「规矩不能被关掉」指的是
      冻结开头那一半——它不在 hook 手里）
- [ ] 客户端**一个字节都收不到**这块文本（无帧）；jsonl 的 `message` 行里 system 消息是拼好的全文，逐字
- [ ] `clojure -M:test -m harness.test-runner` 全绿（基线 `main` @ `0ef17a9`，344 / 1933）
