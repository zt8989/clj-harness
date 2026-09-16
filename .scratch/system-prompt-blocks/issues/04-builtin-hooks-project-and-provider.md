# 04 — 内建 hook：工程目录与会话档

**What to build:** 内核再注册两条 hook（加点 = 加一行）：

- `<project>`：「本会话绑定在哪个目录」从一句叫模型去 eval 的散文，变成 system 消息里**说出来的事实**；
  绑定中途变了，下一次 run 的话就跟着变——它永远不会说一件已经不成立的事。
- `<provider>`：哪个 vendor、哪个 model、思考档开到哪一档（有才说），**永不含 api-key**。

**Blocked by:** 02（`SystemPrompt` 点与追加）

**Status:** ready-for-agent

## 验收

- [ ] `<project>` 绑定时给出**绝对路径**（这个会话被绑定时的那个拼法），并说清：相对路径按它解析、
      bash 以它为工作目录、绝对路径不被重定向
- [ ] 绑定时说清围栏：解析到项目目录与**配置家目录**之外的 read/write/edit 路径会**先停泊等人点头**，
      而读自己的配置是明确允许的
- [ ] 项目开了 `:approval {:strict true}` 时那句话跟着变（那种项目下连项目内的路径也要点头）
      ——不许留一句不成立的规矩
- [ ] 未绑定时不说围栏、不提项目：明说没有绑定，并说清相对路径此时按进程的工作目录
- [ ] `project/bind!` 之后下一次 run 的文本就变（换目录、解绑各一条断言）；没动过则逐字节相同
- [ ] `<provider>` 给出生效的 vendor / model / 思考档，数据来自既有的三档解析，不新增真相源；
      **用断言把真实 api-key 的字符串在 system 文本里搜一遍，任何深度、任何拼法都不许出现**
- [ ] 会话中途 `session-configure` 改档之后，下一次 run 的文本就变；该 thread 答不出 provider 时**不出这一块**
      （不是错误、不是空块）
- [ ] 两条 hook 与工具那条同形：`source :built-in`、`:run`、在 `effective-hooks` 里看得见、关得掉
- [ ] `prompt.md` 里项目那一段与 provider 那一段退场；**secrets 纪律那一节一字不动**
      （禁令、禁读 `scripted-pins` / `session-overrides`、禁 var-quote、`session-configure` 那条审批路径，全部留在冻结开头）
- [ ] 客户端一个字节都收不到这两块；jsonl 的 `message` 行里是拼好的全文
- [ ] `clojure -M:test -m harness.test-runner` 全绿（基线 `main` @ `0ef17a9`，344 / 1933）
