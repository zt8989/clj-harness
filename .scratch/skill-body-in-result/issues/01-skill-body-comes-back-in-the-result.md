# 01 — 技能正文从 `skill` 的工具结果直接回来，不再注入一条 `<skill>` 消息

**What to build:** 模型调用 `skill` 之后，**这次调用的工具结果就是那份 SKILL.md 的正文本身**，末尾再跟一行说这个技能住在哪个目录。
不再是今天这样：工具只答一句「已加载进本会话」的确认句，正文由每轮 LLM 调用前的派生注入补成一条 `<skill name="X">…</skill>` 的
`user` 消息。于是「模型加载过一次技能」在对话里留下的东西就是一次**普通的工具调用**——前端只有一张 `skill` 工具卡（正文在卡里，
点开就是），不再多出第三张注入卡；jsonl 里正文落在那条 tool 结果上；模型侧看到的是 `assistant(tool_call) → tool(正文)`，
与 `read`、`bash` 一模一样，中间不夹别的东西。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 今天是什么样

- `skill` 工具只回一句确认（`harness.cap.skills/loaded-summary`，前缀 `loaded-prefix` = `[skill-loaded]`），外加一行技能目录。
- `harness.cap.skills/derived-injections` 拿这句确认当**判据**（`load-confirmations` 按 `tool_call_id` 配对，
  `loaded-prefix` 是工具与派生函数共用的一份常量），把正文作为 `<skill name="X">` 的 user 消息补在历史的**末尾**。
  末尾不是排版选择：OpenAI 形状的厂商要求带 `tool_calls` 的 assistant 后面**紧跟**每条 `tool_call_id` 的 tool 消息，
  正文插在中间就 400，所以只能放最后。
- 那条补出来的消息由 run 自己报成 `:context/injected`（`harness.kernel.loop` 的 `with-skills`），边把它变成 `CUSTOM` 帧，
  前端画成会话栏里可折叠的一行**注入卡**（`ui/src/lib/injections.ts`）。
- 人的 `/name` 走**同一条**派生路（`slash-request`）——那条路没有工具结果可以携带正文。

## 这一票之后

1. **工具路径不再产生任何 `<skill>` 消息、`message` 行、`:context/injected` 事件与注入卡。** 正文只在 tool 结果里，
   同一回合以及此后每一轮都如此。
2. **那条被厂商规矩逼出来的「补在末尾」对这条路不再适用**——正文就在它自己的 tool 结果里。对 `/name` 那半仍然适用。
3. **正文完整回来，不截断**（既有纪律：被裁过的技能是坏指令）。读不到时照旧**指名报错**（`:error` 为真），
   不是把一句半截话当正文交出去。
4. **目录那一行必须还在。** 「这个技能在哪个目录」正是正文里写「读 `references/x.md`」时要用的东西，
   也是围栏放行技能根的由来（`.scratch/skills-and-instructions` 决策 12）。
5. 正文从此住在**客户端也持有的那条 tool 结果**里：刷新、重建走既有那条路，不需要 `data` part 再把字节还原回模型读过的那条消息。

## 边界（这一票不干什么）

- **人的 `/name` 照旧。** 它没有工具结果可携带正文，仍由 `derived-injections` 补一条 `<skill>` 消息、仍画一张卡。
  所以派生那一半是**收缩**而不是删除——但收缩要收干净，不许留下只有测试在调的死代码。
- 不改技能根与围栏、不加工具参数、不做 `$ARGUMENTS`、不动清单块（`catalog-text`）的内容与顺序。
- 不改 AG-UI 帧形状、不新增 jsonl 行种类、不动 CORS。注入这套机制本身留着：开场块与作业结束的通知还在用它。

## 一条要当场定下来的张力

「**同名只加载一次**」这条性质今天跨来源成立：`loaded-names` 扫历史里的 `<skill name>` 消息，于是
「工具先加载、人再 `/name`」只得到一份正文。正文搬进 tool 结果后，派生那一半**看不见**它，于是这个组合会得到第二份。
两条出路，实现时**选一条，并把选择写进代码与文档**（这是 `loaded-prefix` 那份共用常量当初存在的唯一理由）：

- **接受第二份**：人（或模型）明确又开口要了一次，就再给一次。代价是重复占上下文，换来的是一条更少的判据。
- **让派生那一半认得「正文已在某条 tool 结果里」**：保住既有的跨来源性质，代价是判据要写死在一处，不许两处各猜一遍。

倾向前者：人的 `/name` 是一次新的开口，而「少一处判据」在这个仓一贯值钱。

## 说法与文档必须一起改

留着旧说法的注释是这个仓最贵的债——下一个读者会照着它实现。

- **`skill` 工具自己的描述**：今天写「The full text is added to the conversation and stays available for the rest of the session」，
  要改成正文**作为这次调用的结果**回来。
- **清单块那句**（`catalog-text`）：「its full text then joins this conversation, and it stays available for the rest of the session」同理。
- **`CONTEXT.md` 的「注入」条目**：今天把「技能正文（`skill` 工具或人的 `/name` 触发）」列为注入的一种用户，并说「每一份注入在屏幕上都有一张卡」。
  改成：`skill` 工具的正文**不是注入**（它是工具结果，没有卡）；只有人的 `/name` 才是注入。
- **几处 docstring**（`harness.kernel.session` 的「a skill body … is here as its CARD」、`loop` 的 `with-skills`、
  `cap.project/before-llm` 的「loaded skill bodies folded back in」、`edge/ag_ui` 的 `injected-frame` 例子）一并对齐。
- **`.scratch/skills-and-instructions/spec.md`** 的决策 9 / 10 描述的正是旧形状：在该处留一句指向本票的更正
  （那份 spec 是这个特性的常驻记录，不能让读者照着一份已经作废的机制实现）。

## 验收

- [ ] `skill` 成功时，工具结果 = **完整正文** + 一行技能目录（目录就是该技能自己的目录，绝对路径）。
- [ ] 用一份超过 8000 字符的 SKILL.md 断言正文**没有被截断**（没有 `...[truncated]`）。
- [ ] 同一回合发给 provider 的历史里，skill 那一段只有 `assistant(tool_call)` 与它的 tool 结果；
      **没有** `<skill name=…>` 的 user 消息。此后每一轮同样没有。
- [ ] 工具加载技能**不再触发** `:context/injected`（在 loop 层断言：一段加载技能的 run 里注入事件为空；
      同一条断言里，开场块与 `/name` 的注入照旧触发）。
- [ ] 未知名字 / 坏技能照旧**指名拒绝**、`:error` 为真（沿用既有用例），拒绝的话里带上能用的名字；路径拼不出来这条纪律不变。
- [ ] **`/name` 那半一条不改地绿**（`skills_test` 与 `http_test` 里 slash 的用例原样通过），
      并新增一条钉住上面那个选择：先工具加载、再 `/name` 同名，得到**第二份**正文 —— 人又开口要了一次，
- [ ] 既有断言旧形状的用例改成断言新形状而不是删掉：`skills_test` 的
      `a-loaded-body-is-spliced-in-right-after-its-tool-result`、`a-load-never-splits-the-results-of-one-model-call`、
      `a-skill-loaded-beside-another-call-does-not-break-the-next-request`、`derivation-is-idempotent-and-loads-once-per-name`、
      `only-a-real-load-is-injected`、`a-body-is-read-fresh-and-a-vanished-skill-says-so`、`both-load-paths-reach-a-skill-that-asks-not-to-be-model-invoked`；
      `loop_test` 的 `a-loaded-skill-body-is-in-the-very-next-request`；`http_test` 的
      `an-opening-block-reaches-the-model-and-the-client-can-see-it`（含「模型拿到的是正文、客户端拿到的是卡」那一半）。
- [ ] UI：一段加载技能的真实会话里，会话栏只有一张 `skill` 工具卡，点开是全文；注入卡只为开场块、
      `/name` 与作业通知出现。`ui/test/suites/injections.ts` 那套算术本身不用改（它测的是模块，例子是合成的），
      但任何**断言「模型调 `skill` 会多出一张卡」**的用例都要改成断言工具卡。
- [ ] 工具表里只有 `skill` 的描述变了，其它工具的名字 / 描述 / 参数 schema 一字未动。
- [ ] 离线全量 `clojure -M:test -m harness.test-runner` 全绿（退出码是信号，别只看最后一行）。
- [ ] 动过 `ui/` 就跑 `cd ui && npm test` 与 `npm run typecheck`；**动过 `ui/src/` 还要**
      `node scripts/dev.mjs --scripted` 起服务、**自己开浏览器**走一趟：加载一个技能，看正文真的在工具卡里、真的没有注入卡。
