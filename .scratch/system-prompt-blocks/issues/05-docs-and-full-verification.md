# 05 — 收口：现状文档、跨特征对照、全量验证

**What to build:** 仓库里关于「hook 声明从哪来、一条声明跑什么、system prompt 是谁拼的、冻结的到底是什么」
的每一处说法与代码一致；本特征对既有 spec 的那几处修正落到实处；全量测试、UI 套件、构建全绿，
基线写进本 spec 的「已验证到什么程度」。这一票是扇入点：01–04 都落地之后，现状描述才真的过时到可以一次改对。

**Blocked by:** 03, 04

**Status:** ready-for-agent

## 验收

- [ ] `docs/architecture/hooks.md`（现状描述）：声明的**三个来源**（内建 / 文件 / 会话）与来源档位定的先后；
      一条声明跑什么（`:command` 或 `:run`，文件拒 `:run`）；`SystemPrompt` 点——退出 0 的 stdout 即内容、
      匹配的声明**全部追加**、退出 2 拒绝这次 run、`hook/SystemPrompt` 审计行、无声明即 no-op；
      以及它与 `InstructionsLoaded` 的分工（谁管 system、谁管 user 侧）
- [ ] `docs/architecture/overview.md`：**铁律 2 重写**为「system 消息只有一条，它的**开头**冻结，hook 追加其后」；
      今天那句「per-run 的 context、指令文件、技能清单一律不进 system 消息」按新事实收窄
      （指令与技能仍在 user 侧不变）；请求路径图里拼装那一步改成「组装 system 文本（冻结开头 + 各 hook 的追加）+ 开场块」
- [ ] `docs/architecture/kernel.md`：prompt 载体那一节写清「冻结的是**开头**」，追加与顺序由谁决定
- [ ] `docs/architecture.md`：模块地图新增 `harness.system-prompt` 一行（system 消息的组装 + 注册那三条内建 hook，
      以及它为什么不并进 `preamble`——require 环），`preamble` 那行改成「user 侧开场块」；
      **撤掉「在办」里本特征那一行**；快照点改钉到落地后的 `main` 提交
- [ ] `README.md`：测试基线那行报实际值（今天写的是 `325 / 1827`）；把「工具永远是那几个」这类**现状描述**改成
      从工具表派生（历史叙述留着）
- [ ] `.scratch/general-harness/spec.md` 的 26 点清单：**加一笔带日期的记录**（点表多了一行、为什么），
      不改写原来的行
- [ ] `.scratch/hook-engine/spec.md`：同样加一笔（第 27 个点与它的追加语义；来源从两层变三层）
- [ ] `.scratch/mcp/issues/06`：那一票里「`prompt.md` 的工具枚举改成不枚举」一条，若不是已经落地就**追加一条
      带日期的复议**指向本特征（枚举由构造正确，比不枚举更强）；已落地则不动历史
- [ ] 全仓搜一遍把工具写死的说法（`src/` 注释、`test/`、`README.md`、`docs/`），逐处判断是历史记录还是现状描述
- [ ] **真机验收**（有 api-key 的机器，人）：新会话里模型说得出手里有哪些工具、当前工程目录是哪一个；
      在 hooks.edn 里声明一条 `:system-prompt` 命令，它的文字出现在 system 消息里；`session-disable!` 之后不再出现。
      **真实模型那部分是人判断的，没做就写「未做」，不假装它过了**
- [ ] 前端侧：整场会话里这些追加的文本**没有任何痕迹**（不产生帧、不出现在 UI）
- [ ] `clojure -M:test -m harness.test-runner` 全绿；`cd ui && npm test` 全绿；`cd ui && npm run build` 全绿；
      新增/改写的断言数写进本 spec 的「已验证到什么程度」（基线 `main` @ `0ef17a9`，344 / 1933）
