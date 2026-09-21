# 04 — 收口：铁律 2 的推论、状态表、文档、报数

**What to build:** 仓库里关于「变化的指令怎么送达」「system 消息里那部分住在哪」的每一处说法与代码
一致；全量测试、UI 套件、构建全绿，基线写进本 spec；落地记录。这一票是扇入点：01–03 落地之后，
现状描述才真的过时到可以一次改对。

**Blocked by:** 03

**Status:** ready-for-agent

## 要落地的判断

1. **铁律 2（`docs/architecture/overview.md` 的「三条铁律」）**：「开头冻结」「system 消息只有一条」
   **都不动**；要改的是它的**推论**——「一条会过期的句子」今天有了第二条出路（对话中途的
   `developer` 更新），而走哪条由端点的能力位说了算。
2. **同一份文件的状态表**：「system 消息里 hook 追加的那部分 | **不存**：每次组装现算」加一句——
   **送出去过的那一份**在进程内存里（重启即失，代价是一轮冷前缀）。
3. `docs/architecture/kernel.md`（每轮首步那一段）、`providers.md`（模型条目的键表，以及那张端点表
   今天只覆盖目录里已有的端点）、`edge.md`（请求怎么拼出来：`inbound` 的第三个位置）、`client.md`
   （会话栏不因此多任何东西）。`projects.md` 若提到顺序，一并核。
4. **README 一个字不加**——四节之外不写。
5. 两套全量的报数与真浏览器走查证据，基线写进本 spec 的「已验证到什么程度」。

## 验收

- [ ] 铁律 2 与状态表按 1/2 改写，且与 `cap/system_prompt.clj`、`edge/ag_ui.clj`、`edge/http.clj`
      的 docstring 说法一致（逐句核，不是「大意一致」）
- [ ] `kernel.md` / `providers.md` / `edge.md` / `client.md` 的相应段落核过
- [ ] 仓库里不再有「变化一律替换 `message[0]`」这类不再成立的说法（按名字搜一遍）
- [ ] 后端全量 + UI 套件 + 构建全绿，报数写进 spec
- [ ] `node scripts/dev.mjs --scripted` 走查：一轮里改一次绑定 → 那一轮照常跑完，界面上不多任何东西
- [ ] README 的 diff 为空
