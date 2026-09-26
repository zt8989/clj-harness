# 走查记录：删掉 `session-configure` 之后

改动动过 `ui/src/components/message-parts.tsx`（去掉一个图标表项与 `subjectOf` 的一个分支），所以按下
AGENTS.md 的规矩跑一次真浏览器走查——机器门挡不住「渲染看不到」那一格。

## 怎么跑的

```
node scripts/dev.mjs --scripted .scratch/remove-session-configure/walkthrough.json --ui-port 5314
```

- 脚本两轮：一次 `read deps.edn` 的工具调用，然后一句收尾（`.scratch/remove-session-configure/walkthrough.json`）。
- 服务端报：harness `http://127.0.0.1:63129`、UI `http://localhost:5314`，配置根与 OS home 都是它自己的
  临时目录（跑完即删）。
- 浏览器用 Playwright 驱动这个 UI，另外用 curl 直接对 harness 的端口验 API。

## 看到了什么

1. **转写没变形**（`evidence/v01-transcript-after-removal.png`、`v02-tool-card-expanded.png`）：
   截断成「1 次工具调用 · 2 条消息」的一步展开后是 `思考` 一行 + `read · deps.edn` 一行，状态「完成」，
   左边的图标还在——`TOOL_ICONS` / `subjectOf` 那条路对一个**活着**的工具仍然画得对。
   DOM 探针：`tool-call-trigger-label` = `read · deps.edn`、`tool-call-trigger-subject` = `· deps.edn`、
   `tool-call-trigger-status` = `完成`。
2. **页面上没有任何地方还认得这个名字**：全页叶子节点里只有我自己那条消息的文本含
   `session-configure`，没有任何 UI 元素渲染它。
3. **活下来的那条路真的能动会话档**（这是删掉工具的前提）：
   - 从 UI 按：composer 的「思考档」选 `high` → 按钮变成 `high`（`evidence/v01-*.png` 右下角）。
   - 落盘：`projects/.unbound/<thread>.jsonl` 多了一行
     `provider/session-changed`，`via = "http"`、`before = null`、`after = {"reasoning-effort":"high"}`、
     `resolved.model = "seeded"`（`resolved.protocol = "fake"`，因为这是 scripted 的那个假 provider）。
   - 直接打 API 也一样：`GET /api/model?threadId=walkthrough-tier` → `POST` 同一路径
     `{"reasoning-effort":"high"}` → 200 且回来的档位就是新的；`GET` 再读一次仍是 `high`。
     `POST {"context-window":200000}` 被**指名**拒绝：
     `does not understand ["context-window"]; it takes provider, model, reasoning-effort and clear`。
     两条路（UI 按的和 curl 打的）在各自的 jsonl 里落的是同一种行，`via` 都是 `http`。
4. 后端一侧：工具的删除由用例兜着（名册列表、`providers_test` 的 outbox 契约、`http_test` 的排空
   端到端）；`:requires-approval` 这道缝仍由 `test/harness/approval_test.clj` 的探针工具盖着。

## 没验的

- 模型**看不到**这个工具：这条不在浏览器里验，它由后端名册决定（`harness.kernel.tools/specs` 直接从
  `register!` 加出来），用例已经钉住名册的两份列表。
- `POST /api/model` 写自己那行 `provider/session-changed` 的字段形状：这个**本来就没有用例**，
  这次走查顺带看见了它（上面第 3 条），但没有为它补用例。
