# 12 — 客户端自己抬起的句子

**What to build:** 从用户视角：切成中文后，界面**自己**说出来的那几句失败与拒绝也是中文——
服务连不上时的兜底、附件不合法的那两句（「这个模型不收图片；它声明的是 … —— 换模型，或只带它声明的
东西」「这张图 3 MB，上限是 2 MB」）、以及各条取数路径的兜底句。

这一票存在的理由：这些句子是**本侧自己抬起来的**，与服务端给的那句要分开——服务端的话原样穿过
（spec 决策 3），本侧自己的话必须跟着界面走，否则中文界面上会冒出英文的失败话。

**Blocked by:** 01, 02 — 那两句带尺寸的量词是 02 定的。

**Status:** ready-for-agent

## 验收

- [ ] `lib/attachment-rules.ts` 的两句拒绝（模型不收图片、尺寸超限）与 `(unnamed)` 进目录
      （`errors` namespace）；它仍是**运行时零 import 的纯模块**，收一个 `t`。
- [ ] 各条取数路径的兜底句进目录：`projects` / `threads` / `settings` / `providers` / `composer`
      / `mcp-panel` 里那句「X 失败：HTTP N」与只有 `HTTP N` 时的退化形态。带数字的走插值。
- [ ] **判据写清楚，别让下一个人猜**：服务端给了 `error` 字段就原样用它，本侧只在**没有**服务端
      句子时说自己这句。这条在 `docs/architecture/client.md` 里与 spec 决策 3 并排放。
- [ ] `lib/run-state.ts` 那两条拒绝句归 **03**（它们由侧栏显示），**不在本票范围**。
- [ ] 套件里 `attachments` 那两条断言（今天钉英文的 `change the model` 与 `3 MB` / `2 MB`）改成
      两种语言各一条；`EXPECTED_CASES` 一起改。
- [ ] 真机：把后端停掉，看一次兜底句（这是本侧自己的句子最容易被看见的方式）；中文下一次贴一个
      超限的图，读拒绝句。截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
