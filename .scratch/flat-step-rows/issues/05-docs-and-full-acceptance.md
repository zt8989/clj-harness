# 05 — 文档与全量验收

**What to build:** 让文档说的是今天的样子，并把这套界面**在真机上从头量一遍**：
文档三处（`docs/architecture/client.md`、`.scratch/assistant-ui/issues/04` 的复议、
本特征的 `spec.md` 状态），验收一条（`spec.md` 的「验收主线」六条）。

从用户视角：下一个读 `client.md` 的人不会再照着一段已经不对的话去理解注入点；
下一个读 04 那票的人看得见「组默认打开」这条已被推翻，而不是两个都自称成立的版本。

**Blocked by:** 01、02、03、04（文档写的是它们落地之后的样子；真机主线要一次跑完四票）

**Status:** ready-for-agent

## 验收

- [ ] `docs/architecture/client.md` 跟上现状，至少这三处：
      ① 「装配」那段与注入点那段的说法——`ToolGroup` 槽位现在是**透传**（工具调用不再分组），
      不是「只为 `defaultOpen` 一个 prop 覆盖」；② 抄进来的清单那行**不删**
      `tool-group.aui.tsx`（未删未改，今天只被 `thread.aui.tsx` 的默认分支用），
      但要说清它不再被哪个槽位用到；③ 样式体系那一节补两句：字号两档（正文 14 / 步骤行 13，
      数值写在使用处的理由）与字体是系统栈、Geist 已删。
- [ ] `.scratch/assistant-ui/issues/04-message-parts.md` **不改旧话**，在文件末尾追加一条
      **带日期的复议**：那一票第 3 节的「默认状态一览」里 `1 tool call（组）→ 打开` 这一行被本特征
      推翻（组头恒为 1、已从页面上删掉），指向本 spec。仓库规矩：`.scratch/` 是历史，
      改动是**追加**＋对旧话划线，不是把旧话改成今天的样子。
- [ ] 全仓搜一遍与本次改动冲突的现状描述（`grep -rn "1 tool call\|tool-group\|默认打开\|text-sm"
      docs/ src/ ui/src/ README.md`），**逐处判断是历史记录还是现状描述**：历史记录不动、
      现状描述跟上；判断结果逐个列进本 spec 的落地记录。
- [ ] `spec.md` 的「状态」一节逐票改成落地记录：落地日期、量到的数字（13 / 14 / 12 / 16px、
      字体栈开头、相邻行间距 12px、两行逐字段相同）、以及 `.scratch/flat-step-rows/evidence/` 的截图清单。
- [ ] **真机主线六条逐条量过并在落地记录里给数字**（脚本化后端 + 假 `CLJ_HARNESS_HOME` +
      `cd ui && npm run dev`；悬置那一路另起一份脚本）：零个 `tool-group-trigger`；行文字与投影表逐字对位；
      13/14px 与字体栈；点开点合；五态各一次（含审批卡在行下面）；两张卡悬置的那一批答得完、
      续得上、不死锁。
- [ ] 命令三连：`cd ui && npm run typecheck` 0 error、`cd ui && npm run build` 全绿、
      `cd ui && npm test` **11/11 passed**（`EXPECTED_CASES` 与四组用例的内容一行未改）。
- [ ] `clojure -M:test -m harness.test-runner` 全绿——本特征**后端一个字都没动**，跑它是为了证明
      没有连带动到别处（基线以落地当次为准，报数带上分支与提交）。
- [ ] `git diff --stat` 确认本特征的**闭表**成立：动的只有
      `ui/src/components/message-parts.tsx`、`ui/src/styles.css`、`ui/package.json`（+ lock）、
      `docs/architecture/client.md` 与 `.scratch/` 里的文档；
      `ui/src/components/assistant-ui/elements/` **零改动**。
