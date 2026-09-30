# 01 — 正在流的那条消息，别每个 flush 重建整条子树

**Status:** ready-for-agent
**Blocked by:** None

**要做的**：把「正在流的那条消息」从消息列表里拎出来（或者等价地：让它在流式期间只更新一个文本
节点，而不是每个 flush 走一遍整条消息的子树——message view、parts、markdown 外壳），完成后再插回
列表。第一步不是改代码，是先量一次 `defer` 到底有没有生效：

1. 用 `.scratch/conversation-render-cost/spec.md` 的脚本与仪器，量一遍**现状**：流式期间
   `ScriptDuration`（今天 19–132 ms/秒）。
2. 把脚本的 content 从 18k 字改成 36k 字，再量一遍：**若 `ScriptDuration` 线性翻倍，就是每帧在重
   解析 markdown**（`elements/markdown-text.tsx` 的 `defer` 没落到这条路上），先修那个；若不翻倍，
   就按上面的形状把流式消息单独渲染。
3. 改完再量一次同一脚本，把前后两个数写进 `spec.md` 的读数表。

**为什么（2026-09-30 实测，真浏览器）**：一场 4,285 帧 / 45 秒（≈95 帧/秒）的流，主线程
**task 80–350 ms/秒，其中 JS 19–132 ms/秒**，而样式 0–5 ms/秒、布局 0–5 ms/秒——所以这 100 ms 上下
是 JS 里花的，不是排版。对照：画好之后空闲 0.6 ms/秒。合批（`lib/coalesce.ts`）已经把「每 token 一次
渲染」压成「每动画帧一次」，剩下这一刀就是「每帧仍然重建的是整条消息，而不是它那段文本」。

**要守的既有规矩**：

- `lib/coalesce.ts`：一动画帧一次交付，别绕开它；
- `elements/markdown-text.tsx`：`defer` 与 `unstable_memoizeMarkdownComponents` 是**上游拷贝**里的
  既有手法，改动要按那个文件里 `deliberate edit` 的标记方式注明（文件与上游不再逐字节可比）；
- `docs/rules/panel-data.md`：这条路径是推送，不许为了这件事改成定时器问；
- 动过 `ui/src/` 就要跑一次 `node scripts/dev.mjs --scripted` 并开浏览器走一趟（AGENTS.md）。

**验收**：

- 同一脚本下的前后读数（流式期间的 `ScriptDuration`、`TaskUsage`）写进 `spec.md`；
- `ui` 用例能钉住的那部分钉住（例如「流式中的消息与已完成的消息走的是两条路径」），并按仓库惯例
  更新 `test/ui.test.ts` 的 `EXPECTED_CASES` 与它上面那份账；
- `npm run typecheck` / `npm run build` / `npm test` 绿；
- 浏览器走查：发一句话，答案逐字出来、样式与今天一致（`defer` 那条路径若被改动，尤其要看）。
