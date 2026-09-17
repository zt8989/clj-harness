# 04 — 输入框一圈

**What to build:** 从用户视角：切成中文后，输入框上方那条目录与分支的事实（含「detached」、
「N 个未提交的改动 — 切换可能被拒」）、模型与思考档两处浮层的标题与说明、技能面板里 `System` /
`Project` 两个 chip 与「正在读本会话的技能…」、以及输入框下面那串会话数字都跟着说中文。

**Blocked by:** 01, 02 — 那串数字的格子由 02 定。

**Status:** ready-for-agent

## 验收

- [ ] `composer-chrome`（约 14 条）与 `composer-stats` 自己的那几条进目录（`composer` namespace）。
      带数字的句子（未提交改动数）走 `count`。
- [ ] 技能的**名字与描述来自 `/api/skills`**，原样穿过（spec 决策 3）；本票只翻面板自己的词
      ——那两张 keyword → 话的闭表（技能读不出来的原因、层的名字 `System`/`Project`）进目录。
- [ ] **`composer-stats` 自己一条文案都没有**（实测：整份文件里唯一的字面量是那个分隔符 `·`，
      而且是 `aria-hidden` 的）。那 5 个格子的词全在 02 那一族里，所以本票对它只做一件事：
      把调 `statsCells` 的地方改成把 `t` 递进去（02 定的签名），**一个字不加**。
- [ ] 真机：中文下开一次会话、点开模型与技能两处浮层、看目录与分支那一行（含一个 detached 的
      工作目录与一个脏工作目录）。截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
