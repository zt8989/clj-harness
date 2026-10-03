# bash 超时的硬上限（bash-timeout-cap）

**状态**：已实现，已合进 `main`（合并提交 `7adc525`）。

## 要解决什么

`bash` 工具的 `timeout` 参数现在是**模型说了算**：`harness.cap.tools/t-bash` 只做「正整数」校验，
一个 `{"command": "…", "timeout": 3600000}` 的调用就把整轮 run 按在那里一小时。

等待一条慢命令是这个工具的本职（描述里那句「a very large `timeout` means this call really does wait
that long」是有意写的），但**没有天花板的等待**意味着一次误判能把整场 run 冻住——而且它冻的是会话，
不是那条命令。

**目标**：给 `bash` 的等待加一条硬上限，默认 **600000ms（10 分钟）**，并且**可配**——落在
`config.edn` 的 `:session` 里，与其它会话块同构（可被 `:groups` 逐键覆盖，设置面板「会话行为」页能改）。

## 参考

- `:session` 里按块配置的先例：`.scratch/todo-reminder` 的 `:session :todo {:max-rounds N}`
  （`harness.cap.todos/todo-block` + `knob`：消费方自己把块读出来、按名字校验未知键）。
- 配置的读与写：`harness.cap.providers` 的 `session-config` / `session-config-for-panel` /
  `set-session-config!`，以及设置面板的「会话行为」页（`ui/src/components/session-groups.tsx`）。

## 决定

### 1. 天花板是 harness 的常数，配置只能把它**调小**

`harness.cap.tools/bash-timeout-ceiling-ms = 600000`。`config.edn` 的
`:session :tools {:bash-max-timeout-ms N}` 只在 `1 ≤ N ≤ 600000` 时成立；`N > 600000`、`0`、`1.5`、
`"soon"` 一律**按名字拒绝**（句子报出上限、配置键与实际值）。

- 「硬性限制在 600 秒以内」只有在配置**不能**把它抬高时才是一句真话；能抬高的上限不是上限。
- 默认值就是天花板本身：不写这个键的 home 与写 `600000` 的 home 是同一个 home，所以
  `config.edn.example` 把它写成注释里的说明而不是一行必须存在的配置。

### 2. 超上限的调用**直接拒绝**，不夹

模型传的 `timeout > 本会话上限` ⇒ 这次调用抛一个按名字的失败（`ex-info`），句子报出上限、配置键名，
以及「不传 `timeout` 时的默认值」。**不**悄悄夹到上限继续跑。

- 夹一下会让模型以为它要的是另一个数：它按 900000ms 规划（比如「这条构建要五分钟」），拿到的却是
  600000ms 的结果，而它没有任何途径知道这件事。
- 「先跑着，回头再说」在这里没有意义：一条命令要么在它自己的时间里跑完，要么被上限停掉。

不传 `timeout` 时照旧等 `bash-default-timeout-ms`（120000ms），但**不超过上限**：
`min(120000, 上限)`——一个把上限设成 30000ms 的 home，默认等待也应该是 30000ms。

### 3. 描述与 schema 把上限说出来

工具描述与 `timeout` 属性的 `:description` 都点明上限（数字从常数插值，不手写），属性另加
`:maximum 600000`。描述里那句「a very large `timeout` means this call really does wait that long」
跟着改成「不传由默认值决定、传了由调用决定、但**不超过天花板**」。

**为什么先说后拒**：模型看得到的话（描述、schema）与它踩得到的线（拒绝）是同一件事的两面——
只看描述会以为 900000 可以，只看拒绝会不知道为什么。两处都给。

### 4. 形状：`:session` 里一个新的 `:tools` 块，与其它块同构

```
:session {:tools {:bash-max-timeout-ms 600000}}
```

- 它是**可被 `:groups` 覆盖**的第七个块：`providers/session-keys`、`default-group-keys`、
  `session-group-keys` 三处都要加 `:tools`，合并走 `merge-session-blocks`（逐键）。
- 块的**内容**由消费方（`harness.cap.tools`）校验，与 `:editing` / `:compaction` 的规矩一致：
  `check-config` 只管「`:session` 里有没有这个块名」「`:tools` 里有没有不认识的键」。
- **为什么叫 `:tools` 而不是 `:bash`**：这个块说的是「这次会话被发给的那些工具怎么跑」，今天只有
  一个键；将来多一个工具旋钮时，不需要再多一个顶层块。

### 5. 不去动的东西（写清楚，免得后来者以为漏了）

- **`job_output` 的 `:wait` 超时不动**。它是「等一个已经在跑的后台作业」，与「起一条命令并等它」
  不是同一件事；本票只收 `bash` 这一格。
- **`harness.cap.jobs` 的作业没有超时**，那句「A JOB HAS NO TIMEOUT」是特性不是欠账，不碰。
- **不新增工具、不改 `bash` 的其它参数**。

## 判据

1. `bash {:command "true" :timeout 900000}` 被拒：`error` 为真，句子含 `600000`、配置键名
   `:bash-max-timeout-ms`，且**没有进程被起过**（`shell/run` 的替身一次都没被调用）。
2. `bash {:command "true" :timeout 600000}` 照跑，`shell/run` 收到 `:timeout-ms 600000`——上限本身可用。
3. `config.edn` 写 `{:session {:tools {:bash-max-timeout-ms 30000}}}` 之后：
   `timeout 60000` 被拒；`timeout 30000` 通过（`shell/run` 收到 30000）；不传 `timeout` 时
   `shell/run` 收到 `min(120000, 30000) = 30000`。
4. `:bash-max-timeout-ms` 为 `900000` / `0` / `"soon"` 时，`bash` 调用按名字拒绝（句子区分
   「不是正整数」与「超过天花板」两种）；`:tools` 里写不认识的键按名字拒绝，句子列出已知键。
5. `check-config`：`:session {:tools {..}}` 合法；`:session {:toolz {..}}` 仍按名字拒绝。
6. `GET /api/session` 的 `:default` 带上 `:tools`，`POST /api/session` 能写回并读回同一个块
   （设置面板那半的来回）。
7. 前端：`Groups` 页的默认组与分组表单里都有「Bash 最长等待（毫秒）」一栏；`npm run typecheck`
   与 `npm run build` 绿；en/zh 两个 settings.json 的键保持成对（i18n parity 用例绿）。

## 代价（写清楚，不藏）

- **设置面板写得进去，`bash` 调用才拒。** `:tools` 的值属于「块的语义」，与 `:editing` / `:compaction` /
  `:llm` 一样由**消费方**校验（`check-config` 只管块名与未知键）。所以一个人可以在面板里把上限填成
  900000，保存会成功，而这一家的**每一次 `bash` 调用**都按名字被拒——句子报出上限与配置键，去面板改回来
  即可。换来的是「配置检查只在一处」这条纪律不破；面板那栏的提示文字已经把 600000 说在前面。
- **描述里的数字是进程级常数，不是本会话生效的上限。** 工具描述与 schema 插值的是 harness 自己的
  600000（描述是进程级、不随会话或分组变的），所以它说的是「至多 600000，某个家可以调低」；真正生效的
  上限由调用时那句拒绝说清楚。
- **只有 `bash` 这一格。** `job_output` 的 `:wait` 超时、`job` 的无时限都是另一回事，本票不动（决定 5）。
- **`ui/dist` 是构建产物**：走查前 `npm run build`（`--scripted` 会自己再构建一次）。

## 验收（2026-10-03）

- 后端：`clojure -M:test -m harness.test-runner` 整轮 1467 用例 / 14805 断言，**4 条红，逐条在 `main` 上复现过，与本票无关**：
  - `harness.cap.hashline.store-test` 两条（表清单里少了 `model_calls`，`main` 上同样红）；
  - `harness.edge.delegation-test/a-subagent-can-be-read-back-by-its-own-id`（`main` 上同样红）；
  - `harness.edge.http-test` 一条（settings 只读那条断言）：单跑在 worktree 上绿、在 `main` 上绿、worktree 再跑一次又绿，而红的那条每次还不一样——判为这台机器上的 flaky，不是本票引入。
  - 本票动的三个命名空间（`harness.kernel.tools-test` / `harness.cap.providers-test` / `harness.layers-test`）单跑与整轮都全绿。
- 前端：`npm run typecheck` / `npm test`（230 条）/ `npm run build` 全绿。
- 走查：`node scripts/dev.mjs --scripted`（隔离家、OS 分配端口、后端自己发 `ui/dist`）——设置 →「会话行为」，默认组与分组表单里都出现「工具 / Bash 最长等待（毫秒）」；改成 30000 保存后，隔离家的 `config.edn` 里写进`:tools {:bash-max-timeout-ms 30000}`，表单读回来也是 30000。
