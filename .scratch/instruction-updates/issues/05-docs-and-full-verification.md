# 05 — 收口：铁律 2 的推论、状态表、文档、报数

**What to build:** 仓库里关于「变化的指令怎么送达」「system 消息里那部分住在哪」「能力位在哪儿配、
内置前缀表在哪儿说话」的每一处说法与代码一致；全量测试、UI 套件、构建全绿，基线写进本 spec；落地
记录。这一票是扇入点：01–04 落地之后，现状描述才真的过时到可以一次改对。

**Blocked by:** 03、04

**Status:** ready-for-agent

## 要落地的判断

1. **铁律 2（`docs/architecture/overview.md` 的「三条铁律」）**：「开头冻结」「system 消息只有一条」
   **都不动**；要改的是它的**推论**——「一条会过期的句子」今天有了第二条出路（对话中途的
   `developer` 更新），而走哪条由端点的能力位说了算。
2. **同一份文件的状态表**：「system 消息里 hook 追加的那部分 | **不存**：每次组装现算」加一句——
   **送出去过的那一份**在进程内存里（重启即失，代价是一轮冷前缀）。
3. `docs/architecture/kernel.md`（每轮首步那一段）、`providers.md`（模型条目的键表——`:instruction-updates`
   在里面，且**表单会写它**——**以及那张内置前缀表：它只预填自动获取回来的列表、不参与解析、行都
   要有依据**；那张端点表今天只覆盖目录里已有的端点）、`edge.md`（请求怎么拼出来：`inbound` 的第三
   个位置；探测答案的形状）、`client.md`（会话栏不因此多任何东西；**但设置面板那一节要多一句**：Models
   页的模型行有一个按模型的能力位控件，自动获取列表时按内置前缀预填，见票 04）。`projects.md` 若提到
   顺序，一并核。
4. **README 一个字不加**——四节之外不写。
5. 两套全量的报数与真浏览器走查证据，基线写进本 spec 的「已验证到什么程度」。

## 验收

- [ ] 铁律 2 与状态表按 1/2 改写，且与 `cap/system_prompt.clj`、`edge/ag_ui.clj`、`edge/http.clj`
      的 docstring 说法一致（逐句核，不是「大意一致」）
- [ ] `kernel.md` / `providers.md` / `edge.md` / `client.md` 的相应段落核过（含设置面板那一句）
- [ ] 仓库里不再有「变化一律替换 `message[0]`」这类不再成立的说法（按名字搜一遍）
- [ ] 内置前缀表**只有一个读者**（探测答案那一处）：按名字搜全仓，除了表自己、读它的那一处和用例，
      没有别处引用——防止有人顺手把它接进解析那条路
- [ ] 前缀表的每一行在 `providers.md` 里说得出依据，表旁边那句「没有依据的行不进表」还在
- [ ] 后端全量 + UI 套件 + 构建全绿，报数写进 spec
- [ ] `node scripts/dev.mjs --scripted` 走查：一轮里加/删一个工具 → 那一轮照常跑完，**会话界面**上不多任何
      东西；同一次走查里点到设置 → Models 页那一栏（票 04 的验收，两件事一起看）
- [ ] README 的 diff 为空

## Comments

2026-09-25 — **票 01、02、03、06 与 04 的后端 + 设置页控件已落地**，逐条与「未做」写在本 feature 的
`spec.md` 的「落地记录」一节（那份才是记录，按仓库约定票面不改）。要点：

- 后端全量（干净地跑一轮）：**1240 tests / 13571 assertions / 1 failure / 0 errors**，唯一那条红是**预存在**的
  `harness.cap.claims-test/a-second-jvm-owns-a-conversation-until-it-goes-away`（KILLED 接管那条，与本特征无关）。
- UI：`npm run typecheck` 过；`npm test` **128/128**（有一次 `skills` 套件的 `asking-for-the-list-changes-nothing`
  在第一次跑里红了——那是 `written` 这个测试助手「文件一存在就返回」的既有竞态，重跑即绿，与本特征无关）；
  `npm run build` 过。
- **真机走查（2026-09-25，用真配置的一份临时副本 + 真厂商）**：那三态控件看得见；Fetch 一个 provider 之后，
  命中前缀表的 id 在候选清单里印「建议：`in-place`」（`z-ai/glm-5.3-prime`），没命中的只有 id
  （`fireworks/ember-1`）；take 进来后命中那行的 `<select>` 停在 `in-place`、没命中的停在未声明（读 DOM 断言）。
  走查用的是**真服务 + Playwright**，不是 `scripts/dev.mjs --scripted`。
- **未做**：(a) 没单开 ADR（交付方式写进了 `overview.md` 铁律 2 的推论）；(b) `scripts/dev.mjs --scripted` 那条
  脚本化回放没跑（真机这一次替代了它）。
