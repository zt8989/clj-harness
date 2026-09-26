# 06 — 收口：词汇、ADR、文档、`prompt.md`、全量

**What to build:** 把 01–05 改出来的东西写进本仓的三处"当前状态"，并把总账跑一遍。

1. **`CONTEXT.md`** 加词条、改词条：
   - 新词：**agent 档（agent kind）**（`:main` 或一个子 agent 名；是**线程的一个事实**，由
     `sessions.subagent` 读出）、**配置层（config layer）**（基础层 / 增强层 / 复合层）、
     **基础层 / 增强层 / 复合层**各自一条、**能力目录**、**不在服务**（与**被关掉**并列的第三态，
     补进**工具表**那条）。
   - 改词：**编辑模式**（从 `harness.edn :editing {:mode}`、按会话解析，改成"增强层里的一组一选"，
     并按 agent 档解析；项目级仍能覆盖，但走 `harness.edn :agents`）、**工具表**（补三态那张表）、
     **注入**（技能正文的自动注入多一条来源；**指令文件的注入（AGENTS.md）归基础 hooks 层**，
     也就是每个 agent 可配，而"出生时折一次"的时序不变；**所有注入走同一个对 agent 不可见的内建口**）、**技能层**（**必须**与本特性的"层"分开写，
     这是这次最容易糊的一处）。
   - 每条按本表的老规矩写 **\*别叫成\*** 那一行。
2. **ADR**：写一条，把本特性最硬的那个决定钉下来——建议
   **"一个 agent 的能力是配置出来的，不是从父会话推出来的"**（`baseline`/`exclude` 为什么退休、
   两个维度＝agent 档 × 层、声明与选择为什么分开）。放在 `docs/adr/`，编号接在 `0010` 之后。
   若你更愿意把"层"单独立一条（基础/增强/复合的装载顺序与覆盖语义），就立两条。
3. **`docs/architecture.md`**（及它索引的页）：模块地图里加新 ns（01 的目录/解析、02 的读写），
   把"一个请求的路径"里工具表这一段从"编辑模式 ＋ 会话 overlay"改成"agent 档的解析 ＋ 会话 overlay"。
4. **`prompt.md`**（模型的 system 提示词）：今天的 Self-extension / Session tools 段讲的是
   `effective-tools` 与会话开关（`tool-toggles/03` 定的）。补上"你的表是按 agent 档与三层算出来的"
   这一段，让模型知道**没勾的**与**被关的**是两回事、以及各怎么查。
   注意 `tool-switchboard/04`（eval 相关描述清理）若先落，本票与它对齐措辞。
5. **`README.md`**：**四节之内**——`config.edn` 的配置说明那节补一句"第四节 `:agents`：主/子 agent 的
   工具与 hooks 按层配置"。其余（设计理由、模块地图）**不进 README**（AGENTS.md 的规矩）。
6. **总账**：跑全量并记数。

**要点：**

- **`.scratch/tool-switchboard/` 与本 spec 的关系要留一句**：02 / 03 被本特性取代、01 未做且不等它
  （见 `spec.md` 的**状态**）。**更新 `tool-switchboard` 那三张票的面**（标明被取代），
  还是只在本 spec 里说清，由你定；本仓的先例是"后出特性在 spec 里记明推翻，前一份不改"
  （`tool-toggles/spec.md` 的"推翻 tools-lifecycle"就是这么写的），所以**只在本 spec 里说清是合规的**。
- **不要动 `.scratch/` 里别的历史目录**：它们是**历史**，不是当前状态（`docs/agents/domain.md`）。
- **本票不改代码**，改的是文档与词表；发现代码与文档对不上，回报给对应的那张票，不在文档里圆。

**Blocked by:** 03、04、05（三处行为都落地了才有话可写）

**Status:** ready-for-agent

## 现场

- `CONTEXT.md` 今天是**当前状态**（与代码对齐），所以词条必须在 03/04/05 之后写。
  它今天的相关位置：**会话与项目**（第一节）、**编辑**（`137` 附近的**编辑模式**与**工具表**）、
  **技能**（`295` 附近的**技能层**）、**注入**（`240` 附近）。
- `docs/adr/` 有 10 条，命名 `NNNN-<slug>.md`；`docs/agents/domain.md` 说 ADR 冲突要显式指出，
  不许悄悄覆盖。
- `docs/rules/testing.md`：跑测试的铁律（隔离、超时、进程隔离的判据）；
  `AGENTS.md` 的"测试"一节是它的入口。
- 跑法：后端 `clojure -M:test -m harness.test-runner`；前端 `cd ui && npm test` /
  `npm run typecheck` / `npm run build`；`node scripts/dev.mjs --scripted` 是真浏览器走查的入口。

## 验收

- [ ] `CONTEXT.md` 有了**agent 档**、**配置层**（三层各一条）与**不在服务**；**编辑模式**与**工具表**
      两条按新行为改写；**技能层**那条与本特性的"层"**分得开**（读一遍不会以为是一回事）。
- [ ] `docs/adr/` 多一条（或两条），把"能力是配置出来的、不是从父会话推出来的"记下来，
      并**显式**写出它取代了 `subagents` 的 `baseline`/`exclude` 与 `tool-switchboard` 的那格决定。
- [ ] `docs/architecture.md` 的工具表那一段与代码对得上（新 ns 在里面，旧说法删掉）。
- [ ] `prompt.md` 讲了"没勾＝不在服务、关掉＝可见但被拒"这一对，并指了 `config.edn :agents` 怎么查。
- [ ] `README.md` 只有**四节**，配置说明那节多一句 `:agents`，别处没长出新章节。
- [ ] 后端全量：`clojure -M:test -m harness.test-runner` 全绿，数与基线一起写进本票。
- [ ] 前端：`cd ui && npm run typecheck && npm test && npm run build` 全绿。
- [ ] 真浏览器走查一次 `node scripts/dev.mjs --scripted`：配一个"主 agent 有 `bash`、`explore` 没有"，
      委派一次，两侧的工具与拒绝话都对。
- [ ] 本票**没有**改 `.scratch/` 里别的目录；`git status` 里只有文档与本特性自己的文件。
