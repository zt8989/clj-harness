# 06 — `/goal`：输入框指令，不是一条消息

**What to build:** 人打 `/goal …` 就能立、改、看、暂停、恢复、清除目标，**这句话不进对话**。

`ui/src/lib/goal-command.ts`：一个纯函数 `parseGoalCommand(text)` → `{:action .. :text ..}` 或 `nil`，
作用域与拒绝写在这里（一条测试就能钉死）：

```
/goal <文字>         → {:action "set"    :text <文字>}
/goal edit <文字>    → {:action "edit"   :text <文字>}
/goal pause|resume|clear → {:action "pause"|"resume"|"clear"}   （后面多余的字是错，不是参数）
/goal                → {:action "show"}
```

- **触发形状与技能斜杠同一形状**：`/` 在消息**开头**、名字后有空白或行尾——
  照 `harness.cap.skills/slash-pattern` 的客户端对应写法，不新造一套。
- 第一个词是「保留动词」当且仅当它**恰好**是 `edit` / `pause` / `resume` / `clear`；
  否则整串都是新目标的文字。`/goal edit` 后面没字 → 拒绝（不是把 `edit` 当目标文字）。
- `parseGoalCommand` 只解析，**不发文**。它调 `applyGoal`（票 05 的 `lib/goal.ts`），
  于是 `/goal pause` 与目标条那颗「暂停」是同一条实现的两次入口。

**接发的那一缝**：composer 在**发送之前**认出 `/goal …`，执行动作、清空输入、**不发 run**。
composer 是**本地编辑过的**复制件（`thread.aui.tsx` / `composer-chrome.tsx` 里那些 `LOCAL:` 标记），
所以这一票的第一步是**钉住那一缝**并把它写进实现里：

- 首选：在复制的 composer 上挂一个 `LOCAL:` 的提交拦截（上游 `ComposerPrimitive` 的
  submit / 或 runtime 的发送缝），发出前问一次 `parseGoalCommand`；
- 若上游没给出一处能干净拦下的缝：退到 `ui/src/lib/agent.ts` 的 `run`（每一次发送都过的那一缝），
  在 `appendOf` 之前问一次，命中就执行动作、**不发请求**。

无论走哪一条，**必须在这一票里写明走的是哪一条、为什么**（一段注释或一段 spec 追记）；
不写清楚就是「凭感觉挑了一处」。

**`/goal` 是保留名**：一个叫 `goal` 的技能不能用 `/goal` 触发——它在 `/` 菜单里照旧列出、
模型照旧能用 `skill` 工具加载。这是写下来的代价（spec 决定 8），不是漏洞。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `parseGoalCommand` 的用例覆盖：六个形状、`edit` 空参、保留词后带多余字、普通消息返回 nil、
      行中间的 `/goal` 不是命令（`see /goal x` 是句子）、`/goalx` 不是命令。
- [ ] `/goal 重构登录模块` → 一次 `set`；输入框清空；**没有发出任何 run**。
- [ ] `/goal` → 打开/聚焦目标条（没有目标就画空态）；**没有发出任何 run**。
- [ ] `/goal pause|resume|clear` → 对应动作；**没有发出任何 run**。
- [ ] 一个**普通**消息（不以 `/goal` 开头）照常发送，一个字不改——这条是回归保证。
- [ ] `/goal …` **不进对话**：跑完之后会话里没有这条 user 消息（与服务端 `append!` 那条「客户端带来的
      条目」分开——它根本没被带来）。
- [ ] 服务端拒绝（例如 `/goal` 立时已有目标）**画给人看**：输入框旁边一句错误，用的是服务端那句原文
      （`lib/goal.ts` 的 `reasonFrom` 形状），不是静默吞掉。
- [ ] 端到端可见：`node scripts/dev.mjs --scripted`，打开地址、打一句话确认 provider 回放仍工作，
      然后走 `/goal` 一条命令、看目标条长出来；再走 `/goal clear`、看它消失。
- [ ] 这一段接的是一处**写下来**的缝（票面要求的那句说明在代码里）。

**本票的界线**：不碰目标条自身的渲染（票 05）、不碰存储（票 01）。
