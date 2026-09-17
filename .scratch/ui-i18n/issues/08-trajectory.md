# 08 — 轨迹视图

**What to build:** 从用户视角：切成中文后，轨迹那两种排法都说中文——右边那棵事实树（注入的 / 执行的
/ 等的、`rows that do not match are hidden.`、`no record for this session yet.`）、每一条目的标签、
时间轴上三根 lane 的名字与「点一下打开它」的悬停说明、以及那些如实说「这份记录里没有」的话。

**Blocked by:** 01, 02 — 耗时与 token 的写法由 02 定。

**Status:** ready-for-agent

## 验收

- [ ] `trajectory-view`（约 42 条）与 `trajectory-timeline`（约 10 条）进目录
      （`trajectory` namespace）。**这是第二大的一个文件组**，所以单独一张票。
- [ ] **记录里的内容原样穿过**（spec 决策 3）：`prompt.md` 的正文、注入块的文本、工具描述、
      参数、结果、`finishReason`、模型名。本票只翻这张视图自己的标签与说明句。
- [ ] 「一格没有的都不编」这条既有纪律在两种语言里都成立：老记录缺 `calls` 时那两句如实说明
      的话各自有译文，不许把「没有」写成「0」。
- [ ] `data-slot` 的名字一个不动（真机走查是照它们量的）。
- [ ] 真机：中文下切到轨迹、两种排法各点一次、右侧面板开与合、点一个没有配对条目的记号
      （它照旧不是按钮）。中英各一张截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
