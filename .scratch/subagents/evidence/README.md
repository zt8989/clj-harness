# 走查记录：subagents（2026-09-19）

`node scripts/dev.mjs --scripted .scratch/subagents/walkthrough-script.json --ui-port 5311`
真浏览器（Chrome，playwright）走过的一遍。截图在同目录；这里记的是**只有真浏览器说得清**的那几条。

截图来自两个临时家（第一次被清空后重开了一次），除 `t04-01` 外都是第二个
（`clj-harness-dev-TwL6OT`，这一份上再委派了一次、记录在案）。`t04-01` 是「还没有委派过」的那一格，
与哪个家无关。

> **注记（2026-09-30，`.scratch/subagent-view` 票 06）：`t04-*` 四张截图已被取代，但保留不删。**
> 它们记的是**侧边栏那块「子agent」面板**——本 feature 把那个入口退役了：现在打开某个 subagent
> 对话的门是**正文里那次 `agent` 调用**（票 04，卡片上是可点的 subject），开出来的是**并排的镜像面板**
> （票 05），而不是把主栏会话换掉。下面 04 一节里「运行记录点开的是子agent 自己那条会话」
> 那句描述的行为已不存在；`t04-01` / `t04-02` 里那种「展开侧栏看委派记录」的读法也不再能复现。
> 留下的理由：那四张同时是**当时那个家的记录**（哪一次委派、父会话 id、时间），删掉就只剩文字；
> 新的一遍走查记录在 `.scratch/subagent-view/` 下，两边各自说自己那一天看到的东西。

## 脚本的三轮是怎么被吃掉的

`dev/harness/e2e_server.clj` 的脚本按 **model call** 计数，而且 provider 是**父会话的**
（`harness.edge.http/run-subagent!` 用 `(providers/current-provider parent-thread-id)`），
所以一次委派的三轮是：主 agent 第 1 轮拿到 turn 1（委派调用）→ 子agent 第 1 轮拿到 turn 2
（它的结论，无工具调用 ⇒ 它这一轮结束）→ 主 agent 第 2 轮拿到 turn 3（收尾）。
脚本只有两轮时，主 agent 的第二轮会拿到空轮（`script-provider` 对耗尽的脚本回一个空回合）。

## 04 侧边栏面板

- 展开后两组都在：「这里定义了什么」列 general / explore（各带 `内置` 徽章与范围一句），
  「委派记录」在第一次委派前是「还没有委派过。」
- **运行记录点开的是子agent 自己那条会话**：面板换成它的任务原文、它的结论、它自己的
  「0 轮 · 1 次模型调用」；而项目列表里的那条主会话只是从 `当前` 变回普通行 —— 子会话不在
  项目列表里，它在委派记录里，这是设计的那半。
- **记录那一行第二个字段是"谁委派的"**（`subagent-run-parent`，截断处 `title` 给出全 id），
  不是这次运行自己的 id —— 子agent 的 thread id 侧边栏不显示，它的会话是点开才在面前的那条。
- **面板的列表是"展开那一刻的一份读数"**（`subagent-panel.tsx` 头部注释写着），所以委派发生在
  面板开着的时候，要收起再展开才看得到。走查里确认了这个行为，不是漏刷新。
- **只有浏览器能给的三个数**（panel 展开、三条定义 + 一条记录时实测）：
  - `max-height: 288px` 且 `overflow-y: auto`，`scrollHeight 392 > clientHeight 288` ——
    **当场就在滚**，而不是把项目列表顶出屏幕；
  - 第一行的左沿距触发按钮 **34px**（`ps-[2.125rem]`），触发按钮的箭头左沿 11px、宽 16px
    （右沿 27px），标签"子agent"从 ~43px 起 —— **行对齐的是那个词，不是那个箭头**。

## 05 设置页

| 做了什么 | 看到什么 |
|---|---|
| 打开「子agent」页 | 名册两条内置，都带 `内置` 徽章；**没有删除按钮**（删不掉） |
| 点 general 进表单 | 名字**禁用**（改名=新建+删旧的）；描述、范围下拉、排除三个可改；底部写明保存会往 `<root>/harness.edn` 写一条同名覆盖、整个文件重写、旧内容留在旁边 |
| 排除里填 `not_a_tool` 保存 | HTTP 400，表单里是**按名字拒绝**并列出全部工具名，末尾一句「什么都没写——harness.edn 与改之前一模一样」；事后确认根目录里没有 `harness.edn`、也没有 `.bak` |
| 排除改成 `bash, web_search` 保存 | `harness.edn` 落盘（`:exclude ["bash" "web_search"]`，其余键原样带过），名册那一行立刻变成「…— 再排除 bash, web_search」 |
| 新建 `auditor`（`:read-only`） | 落盘正确，名册出现第三条（`自建` 徽章）；`harness.edn.bak` 出现 |
| 收起再展开侧边栏 | 侧边栏读到的就是同一份：general 带那条排除、explore、auditor —— 两个屏一份来源 |
| 打开 auditor → 删除 | 名册回到两条内置，`harness.edn` 里那条也没了 |

## 一处是走查脚本自己的错，不是应用的

第一次新建 `auditor` 时把描述文本写进了名字里（名字 = 描述 + "auditor"，描述空）。
原因：四个字段的 `data-slot` 挂在 `<label>` 上而不是控件上，直接对 label 做 fill 打歪了。
改用 `[data-slot=…] input` / `textarea` 精确指向控件后，逐字段回读确认，再存就是对的。
应用这一侧没问题：DOM 映射（label→input/textarea/select）正确，存下来的就是表单里那份。

## 环境上碰到的（与本特性无关）

- `--ui-port 5211` 被另一个工作树（`main-e0b4cd9a`）的 vite 占着，改用 5311。
- `dev.mjs` 的临时家在被外部信号打断时，`fs.rmSync` 会撞上 JVM 占着 `logs/harness.infra.log`
  （Windows 独占），清理半途抛错、临时目录留下（今天留下了 `H43soP`、`at9i37` 两个）。
- 走查中途那个临时家被清空过一次（只剩 `logs/`）。没能定位到是谁清的；为避免再丢证据，
  改成每一步马上回读文件。上面的结论都在清理之前取到了。

## 同目录的其它文件

- `regression-analysis.md` —— 与固定点 `a3847ea` 的干净检出**对跑**、按失败名字做差的判定过程；
  `mine2.names` / `baseline.names` 是两份名字集合，`extract-failures.mjs` 是提取脚本。
- `t04-*.png` / `t05-*.png` —— 走查截图（见上）。

