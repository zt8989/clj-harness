# 03 — 常驻的那一场会话：窗口会跟着「更早」长，而且是两份表示

**Status:** ready-for-agent
**Blocked by:** None

**要做的**：第一步先把数拿出来——造一场**几百条消息**的真会话，量：① 打开它（一条 50 条的尾页）
之后页面常驻多少；② 连点几次「更早」把窗口撑到 150/200/250 条之后又是多少；③ 关掉这条会话、
换到另一条之后，前一条占的内存有没有被放掉。**量完再决定**：给 prepend 一个界（「窗口最多 N 条，
再往前只留游标」），还是做了票 02 的渲染级虚拟化之后干脆只留游标、条目按需取。

**为什么（现状，读码 + 上一轮实测）**：

- 窗口由服务端给（`harness.kernel.session/page-size = 50`，"开一场会话花的是一份界，不是它的长度"），
  但**每点一次「更早」就 prepend 50 条**（`lib/window.ts` 的 `merged(… "front")` +
  `?beforeSeq=`），所以页面手里的这场对话会随阅读历史一起长。
- 同一场对话在页面里有**两份表示**：窗口的 entries（记录条目）与 runtime 的 messages
  （`lib/thread-messages.ts` 把前者变成后者）。长会话里这是常驻的大头候选。
- 数字我手上只有一条长消息的那次：一场 4,275 帧的流跑完、强制 GC 后留下的是**那场对话本身**
  （几 MB，18 KB 的回答加它的 message / markdown / React 对象）；帧不留（+~100 DOM 节点）。几百条的
  真会话没量过——**这就是本票第一步的由来**，别拿几 MB 的噪声当结论。

**要守的既有规矩**：

- `docs/rules/panel-data.md`：先拉一次存量、之后由推送走；窗口的游标与 generation 语义
  （`lib/window.ts` 的头）不要为了省内存改写；
- 折叠/虚拟化是票 02 的地盘，这里不要顺手改渲染；
- 服务端只动**有没有**这个界，不要动 `page-size` 那 50 的判据（它有它的出处：
  `harness.kernel.session/page-size` 的 docstring）；
- 量页面内存要用浏览器（`docs/rules/testing.md`：机器门看不见布局与 DOM），走查按 AGENTS.md。

**验收**：

- 上面三个数写进 `spec.md`（仪器：`.scratch/conversation-render-cost/spec.md` 里那套
  CDP `Performance.getMetrics` + `Runtime.getHeapUsage`）；
- 结论落到一个具体动作：**要么**给 prepend 一个写明数字的界（并有用例钉住），**要么**写明"不设界"
  的理由（虚拟化之后只留游标）——两条都行，但必须写下来；
- 若动了代码：`npm run typecheck` / `npm run build` / `npm test` 绿，且按惯例更新
  `test/ui.test.ts` 的 `EXPECTED_CASES` 与那份账；动过 `ui/src/` 就再跑一次
  `node scripts/dev.mjs --scripted` 开浏览器走一趟（点「更早」若干次）。
