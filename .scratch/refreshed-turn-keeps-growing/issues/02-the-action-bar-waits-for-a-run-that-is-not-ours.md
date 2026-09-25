# 02 — 正在被回答的那一轮不摆「复制 / 刷新」

**做什么**

一场服务端仍在回答的会话，刷新回来之后，那一轮回答底部的**「复制 / 刷新 / 更多」不该摆出来**。

今天它摆着，因为那个动作条只读**本页自己**的 `isRunning`——刷新之后这一页没有在驱动 run，于是它
认定这一轮已经完了，把「重新生成这一轮」「复制」摆在一条**还在被写**、而且（修好票 01 之后）还会
继续变长的消息下面。读者据此以为可以把它当成品处理，而它还不是。

要做的是让那个动作条和 composer 用**同一个判据**：本页自己的 run **或**窗口说的服务端 `running`，
两者任一为真就不摆。跑完（窗口说 `settled`）之后它自己回来、跑的若是本页自己起的 run 也照旧藏。

**Blocked by:** 01（票 01 没落地之前，这一轮根本不长，藏不藏按钮都不是重点）。

**Status:** 已落地（2026-09-25）。

**落地时学到的两件事。**

1. upstream 的 `hideWhenRunning` **不能**用来表达这条判据——它是
   `hideWhenRunning && s.thread.isRunning`，而「本页只是看着」正是 runtime 说 false 的那一格，所以传
   `true` 什么也没藏（浏览器实测，见票面下方）。现在是**选画哪一个**：`AssistantMessage` 在 turn 末尾这一格
   二选一。
2. **光把动作条藏掉还不够，那一格会留下一个空位**——而空位和动作条犯的是同一个错：都读作「写完了」。主人
   一眼就看出来了：那里应该是**那颗「在写」的小圆点**（upstream 在 assistant 消息还没有 part 时画的那颗
   `●`：同一个字形、同一个脉冲、同一个 `aria-label`）。所以落地的形状是 `WorkingDot` ⇄ `AssistantActionBar`
   二选一，两态都是 24px，动作条出现时不跳。

- [x] 服务端说这一场 `running`、而本页没有自己的 run 时，正在写的那一轮**画一颗 `●`**、不画动作条。走查
      实测：刷新落进一场还在跑的会话后，sidebar 那行 `running: 1` 的整段时间里 `.aui-assistant-action-bar-root`
      不在、`[data-slot="aui_assistant-message-indicator"]` 在（`●`、`aria-label` = 助手正在工作、高 24px），
      `evidence/01-…png`、`02-…png`。本页自己驱动 run 的整段也一样（走查实测）。
- [x] run 结束（窗口说 `settled`）之后圆点换成动作条：同一次走查 settle 后圆点不在、动作条 24px 高
      （`evidence/03-…png`）。
- [x] 客户端用例：`ui/test/suites/running.tsx` 的
      `a-turn-the-server-is-writing-is-not-a-finished-turn-and-wears-no-action-bar`（本页的读数与服务端的
      词取或，和 composer 的门同源：都走 `lib/session-status.ts`）。
- [x] 真浏览器走查：刷新回到一场正在跑的会话，断言刷新后这段时间里圆点在、动作条不在、答案在长；跑完反过来
