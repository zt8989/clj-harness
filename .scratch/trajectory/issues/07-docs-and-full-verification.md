# 07 — 收口：现状文档与全量验证

**What to build:** 仓库里「今天的现状」那几处跟上代码，然后把两套全量、前端构建、真机证据跑一遍并记账。

从用户视角：下一个读文档的人（或 agent）在 `docs/architecture/` 里读到的轨迹，与代码里的是同一件事。

**Blocked by:** 04, 05, 06（三张都落地才有完整的现状可写）

**Status:** ready-for-agent

## 验收

- [ ] `docs/architecture.md`：模块地图里**日志的读侧**那一行加上 `trajectory`（与 `frames` / `replay` 并排）；
      快照点按落地当次的提交更新；「在办」那一节按它自己的规则处理（本特征落地后不在其中）。
      章节列表**不新增页**——读侧那条缝还没有大到值得单独一页。
- [ ] `docs/architecture/edge.md`：行种类清单加 `model/start` / `model/end`（就写在那三条工具审计行旁边，
      连同「它们是审计行、不是帧」这句话）；并改掉那句**今天就已经不准确**的话——
      「读日志的代码只认 `input` / `event` 两种行；其余是审计轨迹，不是对话的一部分」：
      现在读侧多认了几种行，这句要按现状重写（谁认哪几种，各自的用途）。
- [ ] `docs/architecture/kernel.md`：事件的种数与「哪些事件没有帧」跟着改（两种新的属于没有帧的那一类），
      并写明它们的 `:ts` 对就是一次模型调用的跨度。
- [ ] `docs/architecture/client.md`：`对话` / `轨迹` 切换、轨迹视图、取数时机（打开一次 + 每次 run 结束一次）、
      新 lib 模块，以及**它的数据不是客户端持有的对话**这件事（正是 `client.md` 讲「客户端持有会话」那一节的反面，
      值得一句话说清两者为什么不是重复的）。
- [ ] `README.md` 加一节（读者视角）：轨迹回答什么问题、为什么它只读**记录**、与对话页签的关系。
      照 README 的现状只做**叙述**，不搬实现细节（README 已经在 2026-09-15 被削到「介绍/前置/配置/启动 + 链接」）。
- [ ] 本目录 `spec.md` 的 `## 状态` 落成「已落地」，并**追加**一段带日期的落地记录：
      实际交付与计划不一致的地方（若有）如实写，**不改写**已经写下的决策与票面——`.scratch` 是历史，
      加一笔新的、不粉饰旧的。
- [ ] 跨特征对照逐条核一遍：特别是 `system-prompt-blocks` 若已落地，
      「system 字节变了那一轮要再出现一次」的显示是否真的按 02 定的规矩工作（真机看一眼，别只看代码）。
- [ ] 别的特性的 `.scratch` 历史文件**一个字都不改**（只有本目录可以追加）。
- [ ] 全量：`clojure -M:test -m harness.test-runner`（退出码 0 即全绿，含「没碰真实家目录」那条断言）、
      `cd ui && npm test`、`cd ui && npm run build`。**报数带上分支与提交**，两套都贴进落地记录。
- [ ] 真机证据齐：至少 4 张（轨迹有内容 / 对话页签没变 / `工具` 页签的表 / 时间轴两种看法），
      都在 `.scratch/trajectory/evidence/`，文件名能自解释。
- [ ] **逐行核 spec 的「记录清单」**：左边每一格都指得到记录里的一行；「补」的那四行确实落地了
      （`model/start` 的参数与表、`model/end` 的用量/结束原因/回声）。有格子指不到来源的，
      要么补记录、要么从视图上拿掉——**不许留着靠猜**。这一条要在落地记录里逐行记账，不是打个勾。
- [ ] 端到端自检一遍**验收主线**（spec 里那六条），在落地记录里逐条写「看到的是什么」——
      尤其是第 5 条（帧与折出来的对话逐字节不变），它是本特性唯一可能伤到别人的地方。

## 复议（2026-09-17，落地中）

**半票已经做完**（现状文档不能等）：`docs/architecture/kernel.md` 的事件表、`docs/architecture/edge.md`
的行种类表与读侧那一段，都跟着「`model/start` 多了 `:tools`」「多了一个 `trajectory` 读侧」改掉了——
留着不改就是让「现状」那一页说一件不成立的事。

**全量对照（本机实测，2026-09-17，同一天的先后四次运行）**：

| 跑的是什么 | 结果 |
|---|---|
| `main` @ `f7f4d31`（基线） | 第一次 787 / 10953，**2 failures**；第二次**同样的两个名字**（那次汇总行没抓到——它与另一套全量并跑，900s 的 `timeout` 先到了：这里只能作数「失败名是同一对」） |
| 本分支（`trajectory`，含新票的测试） | 801 / 11018，**4 failures**（两次运行都是这四条） |
| 本分支**把新测试注销掉**（源码改动仍在） | 787 / 10959，**2 failures** |

四条里的两条是 `harness.cap.project-test/a-binding-survives-a-real-restart`（fork 子进程那条，
**基线也红**）；多出来的两条是 `harness.edge.http-test/the-projects-listing-joins-the-store-with-the-disk`
（`(= (.length f) (:bytes session))` 与 `(= (.lastModified f) (:lastActivity session))`）。

**结论与证据**：那两条是**既有的抖动**，由「多了一整个测试命名空间」带来的时序变化推到红，
**不是源码改动造成的**——三条证据：单独跑它绿、单独跑整个 `harness.edge.http-test` 绿（53 tests / 631 assertions / 0 failures）、
把新测试注销掉之后整套又回到基线的两条。它比的是「列表接口报的字节数/时间」与「随后直接从磁盘读的」，
中间只要有任何东西往同一份日志里落一行就必然不等——**它本来就是一个 racy 断言，与本特征无关**，
本票不修它（修别人的测试不是收口的一部分，但这条结论记在这里备查）。

## 落地记录（2026-09-17，收口）

**文档**：`README.md` 加了「会话旁边还有一个『轨迹』」（读记录、不数不算不补、两个取数时机、没有轮询），
并把 `### 验证` 里的基线改成当次的数；`docs/architecture/client.md` 加了「轨迹」一节
（为什么它读记录不读运行时、切换为什么整列换掉、取数触发为什么必须在 runtime provider 之内、
两种看法是同一批标记的两种排法）；`docs/architecture/edge.md` 与 `architecture.md` 在 02 那次已改
（行种类表、读侧那一段、模块地图）。**没有新增架构页**——读侧那条缝还不够大。

**全量（分支 `trajectory`，从 `main` @ `f7f4d31` 切出）**：

| 套件 | 结果 |
|---|---|
| `clojure -M:test -m harness.test-runner` | **804 tests / 11028 assertions / 2 failures**，两条都是基线那对 `project_test/a-binding-survives-a-real-restart`（fork 子进程，main 上同样红）。这一次 `http_test` 那条**没有**撞上——它是时序竞赛，见上一段 |
| `cd ui && npm test` | **24 tests 全过**（`EXPECTED_CASES` 现在是 24，比立票时的 11 多的是别人那些票加的） |
| `cd ui && npm run build` | 过（tsc --noEmit + vite build） |

**真机证据（`.scratch/trajectory/evidence/`，4 张，真 Chromium 打真后端）**。
`t03`–`t05` 是**牛总改了交互口径之后重拍的**（右侧改成点哪条展哪条、默认不显示）：
旧的三张（固定页签那一版）已删掉，留着会让证据说一件已经不成立的事。
`turns` 看法那一次没有图，它的证据是**量出来的几何**：三条 `input` 记号落在 0% / 33.33% / 66.67%（见 06 复议）。

| 文件 | 证明什么 |
|---|---|
| `t03-trajectory-default-no-pane.png` | 默认态：三条 lane + 搜索 + 逐轮条列，**右侧什么都没有**（`[data-slot='trajectory-detail']` 不存在），列表满宽。工具那一段现在**画得出来**：`left 15.5% · width 77.9%`，正好填上两次模型调用之间的空白 |
| `t04-trajectory-tool-item-open.png` | 点开一条 `tool` 条：`executed: yes · outcome: pass · ran: 1.0s · id: call-1`，外加参数与结果两块（`waited` 只在被 park 过时才出现） |
| `t05-trajectory-system-tools-list.png` | 点开 `system` 条：提示词 + **折叠的工具表**（`CALLS 0, 1, 2 SENT · 14 TOOLS`，14 行，默认都折着） |
| `t07-trajectory-tool-expanded.png` | 展开一个工具：完整描述（764 字符）+ 照发出的定义 JSON（2145 字符） |
| `t08-trajectory-rows-one-line.png` | 行的最终形状：每一类都**一行**（33px），工具行是 `bash {"command":…} → 结果`（参数与结果各占一半、各自省略号），右侧**没有**耗时/用量 |
| `t06-conversation-unchanged.png` | 对话页签与切换前后没变：消息、输入框、composer 工具、状态条都在 |

**过程里值得留下的一条**：`harness.e2e-server` 只把脚本**钉在 provider 解析的那条缝上**，
而 `<provider>` 这个内建 hook 是**另按活配置解析**的（`active-provider` → `effective-provider`），
所以一个**空** `config.edn` 的证据家目录会让每次 run 都死在「hook could not be run: no provider」。
`ui/test/support/harness.ts` 一直在写那份种子配置（内联形态的 `{:default {:protocol :fake …}}`），
所以套件不受影响——**手工起 e2e server 时要照它写一份**，否则会误以为是自己的改动坏了。
