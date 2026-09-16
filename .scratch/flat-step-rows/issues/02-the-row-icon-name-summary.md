# 02 — 步骤行的形状：类型图标 + 名字 · 摘要 + 行尾状态

**What to build:** 一行读得出「这一步在做什么」。行变成
`[类型图标] 名字 · 摘要 ………… [状态标记][耗时]`：图标说这是哪一类调用，名字是工具名逐字，
`·` 后面是从这次调用的参数里**投影**出来的一行字，状态从 `·` 后面让位、挪到行尾。

从用户视角：`[文件图标] read · /Users/…/CONTEXT.md`、`[终端图标] bash · pnpm why minimatch`——
不用点开就看得见读的是哪个文件、跑的是哪条命令；`Done` 这种每行都有的词不再占着那句话的位置。

**Blocked by:** 01（行得先是平铺的，这一票改的才是「一行长什么样」）

**Status:** ready-for-agent

## 验收

- [ ] **类型图标表**：一个 `Record<string, ElementType>` 按工具名给图标（`read` / `write` / `edit` /
      `replace` / `insert` / `undo_last_replace` / `anchor_grep` / `bash` / `eval` / `skill` /
      `session-configure` 都有），落到兜底的那一个**不暗示任何具体工具**。类型写 `ElementType`
      （`lucide-react@1.46` 不导出 `LucideIcon`，04 的复议已经查过）。图标只许出现在行首，
      **不再有图标兼职状态**。
- [ ] **摘要投影表**：逐条按 `spec.md` 的「摘要投影表」实现，那一表是**闭表**——
      行上那半句只许从它来；表里没有的工具走兜底（第一个字符串参数的值），一个都没有就不写摘要。
      `replace` / `insert` 的摘要是**锚点**（它们按锚点寻址、参数里没有 path），不是 path。
- [ ] **半截 JSON 不显示**：`argsText` 解析失败（流式途中还没闭合）时行上**只有名字**，
      没有 `·`、没有半截参数、没有 `{}`。真机在流式途中截一帧证明，截图
      `.scratch/flat-step-rows/evidence/t02-02-partial-args-no-summary.png`。
- [ ] **状态挪到行尾**：`CALL_STATES` 那张「词 + 图标」的表拆开——**词不再画在行上**，
      行尾是一个图标（跑着的转圈 / 对勾 / 叉 / 感叹号）+ 已有的耗时段（`shrink-0`，不参与省略）。
      `data-slot` 名字照旧（`tool-call-trigger` / `tool-call-trigger-icon` /
      `tool-call-trigger-label` / `tool-call-trigger-duration` …），真机走查是照着它们量的。
- [ ] **状态不许变得看不见**（这一条是本票最容易做丢的东西，逐条真机各跑一次）：
      失败仍是 destructive 色的叉；被否决（`cancelled`）仍是划掉；待批准仍是感叹号，
      且**审批卡照旧整幅宽地挂在抽屉外面**——它的位置、宽度、流程一个字节不动。
      截图 `t02-03-five-states.png`。
- [ ] **单行省略**：行是 `w-full`，摘要 `flex-1 min-w-0 truncate`，行尾两件 `shrink-0`；
      超长摘要（给 `bash` 一条 300 字的命令）在一行内以 `…` 收尾，行的 `getBoundingClientRect().height`
      仍是单行高度、**行数不涨**。截图 `t02-04-long-summary-one-line.png`。
- [ ] 摘要的 `getBoundingClientRect()` 与 `getComputedStyle` 只量这一处：
      一个回合里 3 个工具行的文字逐字等于脚本里的参数投影（例如 `read` 的行文字含脚本给的 `path`），
      逐条对位而不是「看着差不多」。
- [ ] 名字仍是**逐字**的工具名，不做美化、不起别名（`CONTEXT.md` 的「工具名的写法」那条）。
- [ ] `git diff --stat` 里本票只动 `ui/src/components/message-parts.tsx` 一份；
      `ui/src/components/assistant-ui/elements/` 零改动。
- [ ] `cd ui && npm run typecheck` 0 error；`cd ui && npm run build` 全绿；
      `cd ui && npm test` **11/11 passed**（`EXPECTED_CASES` 一个数都不改）。
