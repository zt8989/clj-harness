# 08 — `/goal`：输入框指令，不是一条消息

**What to build:** 人打 `/goal …` 就能看、建、改、暂停、恢复、清掉。

`ui/src/lib/goal-command.ts`：一个纯函数 `parseGoalCommand(text)` → `{:action .. :objective ..}` 或 `nil`：

```
/goal                  → {:action "show"}
/goal <目标>           → {:action "create" :objective <目标>}
/goal edit <目标>      → {:action "edit"   :objective <目标>}
/goal pause|resume|clear → {:action "pause"|"resume"|"clear"}
```

- 触发形状与技能斜杠**同一形状**（`/` 在消息开头、名字后有空白或行尾），复用那条规矩的客户端写法。
- 第一个词是保留动词，当且仅当**恰好**是 `edit`/`pause`/`resume`/`clear`；`/goal edit` 后面没字 → 拒绝
  （不是把 `edit` 当目标文字）；保留词后带多余字也拒绝。
- `parseGoalCommand` 只解析、**不发文**；它调票 07 的 `applyGoal`，于是 `/goal pause` 与目标条那颗
  「暂停」是同一条实现。
- 人的门走**:by :human**（票 03 的 `POST`），所以 `/goal resume` 能恢复人暂停的目标。

**接发的那一缝**：composer 在**发送之前**认出 `/goal …`、执行、清输入、**不发 run**。
composer 是本地编辑过的复制件（`thread.aui.tsx` / `composer-chrome.tsx` 的 `LOCAL:` 标记），
这一票第一步是**钉住那一缝**并写进代码：首选在复制的 composer 上挂一个 `LOCAL:` 的提交拦截；
若上游没给出干净的一处，退到 `ui/src/lib/agent.ts` 的 `run`（每次发送都过的那一缝），在 `appendOf`
之前认一次、命中就不发请求。**必须写明走的是哪一条、为什么。**

`goal` 是**保留名**：一个叫 `goal` 的技能不能用 `/goal` 触发（在 `/` 菜单里照旧列出、模型照旧能用
`skill` 加载）。写下来的代价。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] `parseGoalCommand` 覆盖：六个形状、`edit` 空参、保留词后带多余字、普通消息 nil、
      行中间的 `/goal` 不是命令、`/goalx` 不是命令。
- [ ] `/goal 重构登录模块` → 一次 `create`；输入框清空；**没有任何 run 发出**。
- [ ] `/goal` → 打开/聚焦目标条（没有目标画空态）；`/goal pause|resume|clear` → 对应动作；都不发 run。
- [ ] `/goal edit <新文字>` 走人的 `edit`，**不动相位**（paused 的目标 edit 完还是 paused）。
- [ ] 普通消息照常发送，一个字不改（回归）。
- [ ] `/goal …` **不进对话**：跑完会话里没有这条 user 消息。
- [ ] 服务端拒绝（如 create 时已有未完成目标）**画给人看**，用服务端那句原文（`lib/goal.ts` 的
      `reasonFrom` 形状），不静默吞。
- [ ] `node scripts/dev.mjs --scripted`：打开地址、打一句话确认 provider 回放仍工作，
      再走 `/goal` 建、看目标条长出来、`/goal clear` 看它消失。
- [ ] 代码里写明了走的是哪一缝（票面要求的那句）。

**本票的界线**：不碰目标条渲染（07）、不碰存储（01/02）。
