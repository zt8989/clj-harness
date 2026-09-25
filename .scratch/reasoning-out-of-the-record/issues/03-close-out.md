# 03 — 收口：那份契约要改名，旧日志要在真文件上验一遍

**做什么**

把「记录不再写推理帧」这件事写进它该在的地方，并在**真的旧日志**上验一次——不是夹具，是家里那份 65 MB 的。

**为什么单开一票**：这一票改的都不是行为，而**契约的文字**。这个仓里最有价值的东西就是那些句子——
`runner` 说「记录与线上逐帧相同」、`jsonl-two-kinds` 说「记录持有它能被重建出来的那套词汇」、
`home-and-storage` 说记录是「只追加的记录」。它们今天开始**不再是全部为真**，谁不改，谁就会照旧契约读代码。

**要落的东西**

- **一条新 ADR（`docs/adr/0006-…`）**：记录里的推理来自**模型那一行**，不来自推理帧；线上不受影响；
  `runner` 那句与 `jsonl-two-kinds` 那句由它取代。写清「谁推翻谁」——本决定**不推翻** 0003 的窗口代数、
  0004 的下行、0005 的「会话拥有记录流」，它只改**记录持有哪一族帧**。
- `harness.edge.http/runner` 与子 agent sink 的 docstring（01 已改代码，这里把话说圆）。
- `docs/architecture/edge.md`（帧那一族）、`docs/architecture/home-and-storage.md`（记录里有什么）、
  `docs/architecture/overview.md`（「已执行的对话记录」那张表）。
- `harness.edge.replay` 那句「seed + every recorded frame, reasoning and tool calls included」
  （`rebuild-post` 的 docstring）——今天仍然是**折出来的东西**的描述，仍然为真，但它没说清推理从哪儿来。
- **实测数字进 `evidence/`**：两场各量一次文件大小与整份解析的时间。
  - 今天的形状 `ed334c9c`（39.93 MB）：推理五族 32.59 MB，去掉后应约 **7.3 MB**。
  - 旧日志 `fa35f356`（65.03 MB）：推理五族 42.89 MB，去掉后应约 **22 MB**（那里面还有 16.6 MB 是
    **改动前** `model/start` 抄的工具表——2026-09-24 已经修掉了，别把它算进本特征的账）。
- **旧日志在真文件上兼容**：拿家里那份 65 MB 的旧日志跑一遍 `rebuild` / `sofar` / `pages` /
  `trajectory`，断言推理仍出现在折出来的会话里（旧日志靠帧），而且与 `trajectory` 读到的
  `reasoning_content` 说的是同一段话。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] ADR 0006 落地，并写明它**不推翻**哪些、**取代**哪两句原话（引原文，不是转述）。
- [ ] 上面列的每一处文档都改到；`rg` 一遍「逐帧相同」「它能被重建出来的那套词汇」确认没有漏下的副本。
- [ ] 家里那份真日志：四条读路由（`rebuild` / `sofar` / `page` / `trajectory`）都跑通，推理都在。
- [ ] `evidence/` 里有改前改后的文件字节数与解析耗时。
- [ ] 全量门：`clojure -M:test -m harness.test-runner`；`cd ui && npm test` / `npm run typecheck` / `npm run build`
      （这一族按说一个 `ui/src` 文件都不改，跑它是为了证明这一点）。
- [ ] 落地之后，把本目录三张票面删掉、把「做了什么」写回 `spec.md`——票面是**还没做的事**。
