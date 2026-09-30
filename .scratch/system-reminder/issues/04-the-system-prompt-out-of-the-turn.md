# 票 04：系统提示词出轮

Blocked by: 无。**一轮 = 用户 + llm。**

## 现状（先回答牛总那句「不知道是事件顺序错误还是渲染错误」）

都不是。`harness.edge.trajectory/one-run` 先用第一条新 user 消息 `open-turn`，紧接着
`append-last [(system-item …)]`，所以 `:turns[0].items[0]` 就是 system。这是折法自己的选择——
`trajectory.clj` 的 docstring：「THE SYSTEM MESSAGE IS SHOWN ONCE, and again whenever its bytes change」。
要满足「一轮就是用户+llm」，改的是这条折法，不是事件顺序，也不是渲染。

## 改哪里

1. `harness.edge.trajectory`：
   - `folding` 状态里 system 不再进 `:turns`，收进 `:system` 列表：一条
     `{:turn <轮号> :text <字节> :initial bool :tools <该 run 的提示词行信封 :tools>}`。
     每次字节变了就 push 一条；`:turn` 是「它出现在哪一轮」的轮号（第一轮为 1）。
   - `one-run` 两个分支里那两处 `append-last [(system-item …)]` 改成写 `:system`。
   - `trajectory-answer` 的 payload 变 `{:turns … :system … :incomplete bool}`；`finish-turn` 不变。
   - `system-item` 拆分/改名，`records->trajectory` 与两个 docstring（payload 形状、system 那条）跟着改。
2. `ui/src/lib/trajectory.ts`：`TrajectoryPayload` 多 `system`；`TrajectoryItem` 的 `"system"` 分支若只此一处用，
   收进顶层类型。
3. `ui/src/components/trajectory-view.tsx` / `trajectory-timeline.tsx`：
   - 轮列表**上方**画系统提示词（通常一条），点开仍是「提示词 / 工具表」两个 tab；
   - 轮内不再有 system 行；`KIND_LABEL` / `KIND_HUE` 里 system 的去留随类型走；
   - 时间线条带若按 item 分道，系统提示词成为条带外的一条或一条独立道。

## 判据

- `trajectory-test`：跑一次真 run 的记录，payload 的 `turns[*].items` 里 `kind == "system"` 的数目为 0；
  `system` 至少一条、`:turn` 为 1、`:initial true`；把提示词哈希换掉再折（夹具造两条 `system-prompt` 行），
  多一条 `:system` 且 `:turn` 是第二条所在的轮号。
- `ui`：`trajectory-view` 的套件（若有无）或 `ui/test/suites/` 里加一条纯函数断言：轮内无 system。
- `ui && npm run typecheck && npm test && npm run build`。
- `node scripts/dev.mjs --scripted`：切到轨迹那一栏，系统提示词在轮**外面**，第一轮只剩用户与模型。

## 不做

- 不给轨迹加游标（`.worktrees/trajectory-on-the-downlink` 那条线自己管载体）。
- 不动 `:incomplete` 的判据。
- 不动注入物在轮里的位置（`context` 项照旧按记录顺序留在轮内——这正好是票 05 的前提）。
