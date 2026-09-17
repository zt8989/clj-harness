# 01 — 图片进得来、发得出去、对话里看得见

**What to build:** 在 composer 里 ⌘V 粘贴一张剪贴板里的图（或把图拖进来，或用 `+` 选一张），输入框上方
出现一个可删的缩略图；按发送，这条用户消息带着图走完整条路——AG-UI 的 image part 进服务端、翻成 provider 的
`image_url` data URL、模型收到——并且这条消息在对话里带着那张图显示出来（点一下放大）。

**今天这三条路都是哑的，而且哑得没有声音。** 运行时没接附件适配器，`capabilities.attachments` 于是为
false，而那是上游唯一的能力位：粘贴在 `ComposerInput` 的第一句就被放掉，拖放同理，`+` 会弹出文件框再
什么也不发生。

**服务端零改动，这是本票的硬边界。** 图片早就进得来：`harness.edge.ag_ui/provider-image-url` 会把
AG-UI 的 image part 翻成 provider 的 `image_url`（url 与 data 两种来源都认），模态守卫与
`GET /api/model` 的 `:input` 也都在。缺的只有客户端把它接上。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 验收

- [ ] 运行时拿到一个只收 `image/*` 的附件适配器（`useAgUiRuntime` 的 `adapters.attachments`）——
      它是 `capabilities.attachments` 的唯一来源，装上之后粘贴 / 拖放 / `+` 三条路一起活过来。
- [ ] ⌘V 粘贴剪贴板里的图（真机两种来源各试一次：截图工具复制、从 Finder 复制图片文件）→ 输入框上方
      出现一个缩略图，带删除按钮。截图 `evidence/t01-01-pasted-chip.png`。
- [ ] **只有图、没有字也能发**：有图、输入框空，发送可用；发出去的就是那张图。
- [ ] 一次粘两三张 → 三个缩略图；发送后是**同一条**用户消息带三张图。
- [ ] 缩略图可删：删掉后那一行不再占位（`empty:hidden` 回到空），发送重新变回不可用。
- [ ] **对话里看得见**：发送后用户消息旁显示缩略图，点击放大、Esc 关闭。截图
      `evidence/t01-02-in-transcript.png`。
- [ ] 拖一张图进 composer → 同一个缩略图（拖放悬停时 composer 的边框会按 `data-dragging` 变）。
- [ ] `+` 的文件框 accept 是 `image/*`：选一张图 → 出缩略图；选一个 `.txt` → 不出（也不报错）。
- [ ] **记录说得出它收下了**：真机跑一 run，日志里那条 `input` 行的 messages 里是
      `{:type "image" :source {:type "data" :value "<base64>" :mimeType "image/png"}}`；**同一个 run 的
      `message` 行**里它已经翻成 `{:type "image_url" :image_url {:url "data:image/png;base64,…"}}`。
      两条都要看（前者证明客户端发了，后者证明服务端翻对了）。**只把 base64 的头尾几行摘进
      `evidence/t01-03-log-lines.txt`——不要把整张图的字节抄进证据文件。**
- [ ] 一个 `:input #{:text :image}` 的模型下整条跑通（`GET /api/model` 的 `:input` 里看得见 `image`）。
- [ ] **服务端一个字不动**：`src/harness/edge/ag_ui.clj`、`src/harness/edge/http.clj`、
      `src/harness/cap/providers.clj` 零 diff；本票只动 `ui/`（外加为真机验收起的 dev server）。
- [ ] `cd ui && npm run typecheck` 0 error、`npm run build` 全绿、`npm test` 全绿且 `EXPECTED_CASES`
      不改——本票不加用例，线那一侧已被 `http-test` 的
      `an-image-part-reaches-the-model-translated-and-the-log-says-so` 盖住。

## Comments

### 回执（2026-09-17）：三条路都活了，服务端零 diff

代码只有两处，都在 `ui/`：`lib/attachments.ts`（适配器，一行挂到 `app.tsx` 的
`adapters.attachments`）与它为说明而写的那段头注释。**`src/` 零 diff**，`http-test` 那条
`an-image-part-reaches-the-model-translated-and-the-log-says-so` 本来就盖着线那一侧。

真机（真 Chromium + 真 vite + 真 e2e 后端，家目录与 OS 家都是临时目录）走完三条路，证据在
`evidence/`：粘贴 / 拖放的悬停边框 / `+`（accept 读到 `image/*`）各一张截图，
三个缩略图发出去之后这条消息在对话里带着三张图（`t01-02`），点开放大、Esc 关闭；
只有图没有字时发送键可点；逐个删完那一行 `empty:hidden` 回到零高、发送键回到 disabled；
选一个 `.txt` 不出缩略图也不报错。

记录里那两行在 `evidence/t01-03-log-lines.txt`：`input` 里三个
`{:type "image" :source {:type "data" …}}`，同一个 runId 的 `message` 里是三个
`{:type "image_url" :image_url {:url "data:image/png;base64,…"}}`（base64 只留头尾）。

`typecheck` / `build` / `npm test` 全绿，`EXPECTED_CASES` 本票没动（24）。

**一条没做到位、如实记下**：真机那两种剪贴板来源（截图工具复制、从 Finder 复制图片文件）需要一台有桌面
会话的机器；本次用的是浏览器自己的剪贴板 + 一次真的 ⌘V，走同一条 `paste` / `clipboardData.files` 的路。
理由与替代写法写在 `evidence/README.md`。
