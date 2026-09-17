# 02 — 数字、量词与日期：四份写法归成一族，按语言说

**What to build:** 从用户视角：切成中文之后，侧栏每一行的日志大小与时间、输入框下面那串会话数字、
工具卡与轨迹上的耗时、附件规则的「这张图 3 MB，上限是 2 MB」都跟着说中文——
`3 轮` / `3 步` / `1.2M tok` / `2 分 15 秒` / `3 次工具调用`，而不是一半中文一半英文。

这一票先做的理由：**词汇是所有面共用的**，它不进目录，后面每一面都得自己拼一遍量词。

**Blocked by:** 01 — 判定的那条链与目录的形状。

**Status:** ready-for-agent

## 验收

- [ ] 尺寸与时长今天有**四份**写法，并成**一族**，家仍在 `lib/format.ts`：
      `formatBytes`（`B`/`KB`/`MB`、零字节与「还没有日志」都保持原样说出口）、`formatTime`、
      `formatTokens`、`formatMillis`、`statsCells`，以及工具卡里的 `formatToolDuration`、
      `file.tsx` 里的 `formatFileSize`、`attachment-rules` 里的 `megabytes`。
      同一件事往后只有一个地方说；并掉的三处各自的调用点改为用这一族。
- [ ] 量词与复数交给 i18next 的 `count`（中文只有一种形式，英文两种），手写的 `plural()` 退休；
      词进各自的 namespace，`format.ts` 里不再留任何一句给人读的英文。
- [ ] **纯模块仍然运行时零 import**（`import type { TFunction }` 是类型上的，不算），
      `vitest.config.ts` 那段「按相对路径 import 纯模块」的例外照旧成立。语言通过参数进来，
      **不许**有任何模块级可变的语言状态。
- [ ] 日期仍按读者本机时区与标点（`toLocaleString`），并把这条写进 `docs/architecture/client.md`：
      时区取本机、标点取界面语言。
- [ ] 套件把**两种语言的输出都钉住**。今天 `stats` 套件只钉英文、`turns` 套件只钉中文
      （`72 次工具调用 · 25 条消息`），两张都改成两种语言各一条；`EXPECTED_CASES` 一起改。
      「`0 次工具调用` 那半句要丢掉」这条既有规则照旧成立，两种语言都要有用例。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
