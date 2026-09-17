# composer 附件的证据：三条路、两句拒话、记录里那两行

本目录的东西分三类：**三条路的截图**（票 01 / 04）、**两句拒话的截图**（票 02 / 03）、
**记录里那两行的摘录**（票 01）。下面是每一份是什么、怎么产生的、以及**它没有证明什么**。

## 真机怎么起的（票 01 / 02 / 03 / 04 共用这一套）

三件事，家目录与 OS 家一律临时（`AGENTS.md` 的家目录纪律）：

```
EV=$(mktemp -d)                                   # 下面每个文件都落在这里
$EV/home/config.edn   一个**具名 provider**，两个 model：sees-images 声明收图、text-only 只声明收文字
$EV/userhome          空的 OS 家
$EV/script.json       harness.fake 的脚本替身（模型回什么）
$EV/workspace         加进项目列表的那个目录

CLJ_HARNESS_HOME=$EV/home clojure -M:dev -m harness.e2e-server \
    --script-file $EV/script.json --port 8099 --user-home $EV/userhome
cd ui && npx vite --port 5174 --strictPort        # 真的 vite dev server
```

浏览器是 Playwright 驱动的**真 Chromium**（不是 jsdom）。两处与默认走法不同，都写在这里：

1. **端口不是 5173/8080**：`ui/src/lib/threads.ts` 把 `AGENT_URL` 写死成 `http://localhost:8080/`，
   而**后端只放行 `http://localhost:5173`**（`harness.edge.http/ui-origin`，`vite.config.js` 把它钉死）。
   这台机器上 5173/8080 已被**别人正在跑的另一个 rig**占着（另一个 worktree 的开发服务器），
   所以本次把后端放到 :8099、vite 放到 :5174，并让后端把放行来源也认成 :5174；页里再挂一段
   `addInitScript` 把 `localhost:8080` 的 fetch 改写到 :8099。**三处都是起 dev server 的方式，
   仓库里一个字节都没改**（票 01 的验收要求 `src/` 与 `ui/src/` 在这一点上不受影响）。
2. **图不是从操作系统剪贴板来的**：真机上的「截图工具复制 / 从 Finder 复制图片」这一半需要一台有桌面
   会话的机器。本次用**浏览器自己的剪贴板**（`navigator.clipboard.write` + `ClipboardItem`，画布画出来的
   真 PNG 字节）写进去，再发一次**真的 ⌘V 按键**，走的是页面的 `paste` 事件与
   `clipboardData.files`——与人在浏览器里粘贴进的是同一条路。拖放用的是 Playwright 的
   「从页面外拖进来」那一个动作（等价于把一个文件从 Finder 拖进窗口），`+` 走的是真文件框
   （`chooser.setFiles`）。**没有证明的是**：这台机器上那两种真实剪贴板来源本身。

## 三条路（票 01 的 t01-*，票 04 的 t04-*）

| 文件 | 走了哪条路 | 看到什么 |
|---|---|---|
| `t01-01-pasted-chip.png` | ⌘V 粘贴（剪贴板里一张 PNG） | 输入框上方一个缩略图 + 删除按钮；输入框**空的**，发送键**可点** |
| `t04-01-paste.png` | 同上，收口时重走一次 | 同上（这一张是最终代码拍的） |
| `t04-02-drop.png` | 拖放：把一个真 PNG 从页面外拖进 composer | 同一行里多一个缩略图（粘贴那张还在） |
| `t04-03-picker.png` | `+` → 真文件框 → 选一张图 | 第三个缩略图；`+` 那一侧的文件框 `accept` 读出来是 `image/*` |
| `t01-02-in-transcript.png` | 三个缩略图一起发出去之后 | 这条用户消息旁三个缩略图，助手回了话 |

同一轮里还量过、但没有单独留图的几件：

- **只有图、没有字也能发**：三个缩略图、输入框空，发送键是 enabled（`t01-01` 就是那一刻）。
- **缩略图可删**：逐个删完，那一行 `innerHTML` 回到 `""`、高度为 0（`empty:hidden` 生效），发送键回到 disabled。
- **点开放大、Esc 关闭**：点缩略图出现 `role="dialog"` 的浮层（里面是那张图的 `Attachment preview`），
  Esc 之后 `[role="dialog"]` 计数回到 0。
- **拖放悬停时边框变**：Playwright 举不住一次原生拖拽，所以 `dragenter` 是手工派发的
  （真 `DataTransfer`、`types` 里有 `Files`），读回来 `data-slot="aui_composer-shell"` 上是
  `data-dragging="true"`、borderColor 变成 `oklch(0.708 0 0)`；drop 之后这个属性自己消失。
- **`+` 选一个 `.txt`**：不出缩略图、composer 里没有 `role="alert"`、也没有异常——被上游的
  `fileMatchesAccept` 在**到适配器之前**挡掉了（`accept` 是 `image/*`）。

## 两句拒话（票 02 / 03）

| 文件 | 场景 | 量到的 |
|---|---|---|
| `t02-01-refused.png` | 模型切到 `text-only`（**不重载页面**）之后粘一张图 | `+` 变灰、`title` = `model "text-only" does not take images; it declares ["text"] — change the model, or attach only what it declares`；composer 下面红字同一句（`role="alert"`、`data-slot="composer-attachment-refusal"`）；**已挂的那张缩略图与输入框里的字都还在** |
| `t03-01-too-big.png` | 一张 2.96 MB 的 PNG 粘进来 | 不出缩略图、红字 `this image is 3 MB; the limit is 2 MB`；已挂的两张缩略图与输入框里的字都还在 |

同一轮里还量过的边界：

- **判据随模型当次刷新**：`sees-images` → `text-only` 立刻拒（上一张），`text-only` → `sees-images`
  立刻又收（粘一张就出缩略图），全程没重载页面；被拒的那句话在模型换回来时自己消失。
- **同一张图压到 2 MB 以内再粘**：2.96 MB 那张噪声图画出 300×300 的同一张图（0.29 MB），
  粘进去照常出缩略图。
- **`+` disabled 之后点不动**：手工 `click()` 之后页面上没有多出 `input[type=file]`。
- **服务端零 diff**：`src/harness/edge/ag_ui.clj`、`src/harness/edge/http.clj`、
  `src/harness/cap/providers.clj` 一个字没动（`git diff --stat` 只列 `ui/` 与文档）。

## 记录里那两行（票 01）

`t01-03-log-lines.txt` —— 一次带图 run 的两条行，base64 只留头尾（整张图的字节不进证据文件）：

- `kind="input"`：客户端发来的原样，一条 user 消息里**三个** image part，
  形状是 `{:type "image" :source {:type "data" :value <b64> :mimeType "image/png"}}`。
- 同一个 `runId` 的 `kind="message"`：翻给厂商的形状，
  `{:type "image_url" :image_url {:url "data:image/png;base64,…"}}`，三条，顺序与长度一一对上。

两条都看，是因为它们各证明一半：前者证明**客户端确实把它发出去了**，后者证明**服务端翻对了**。
同一份 run 的行序也留在文件头上（`input` → `hook/SystemPrompt` → `message` → … → `model/end` → `message`）。

## 它没有证明什么（如实写在这里）

- **没有跑活厂商。** 后端是 `harness.fake` 的脚本替身，家目录与 OS 家都是临时目录——这台机器上跑真请求
  要动真实的 `~/.clj-harness` 与那把 api-key，本会话不做。所以「模型**真的看到了**那张图」这一层由
  `test/harness/edge/http_test.clj` 的
  `an-image-part-reaches-the-model-translated-and-the-log-says-so` 盖着（它断言的是送到厂商手里的
  那条 message）。
- **两种真实剪贴板来源没试**（截图工具 / Finder）：见上面「真机怎么起的」第 2 条，本次用的是浏览器自己的
  剪贴板 + 真的 ⌘V。
- **`GET /api/model` 失败时判据回落成「什么都没声明」** 只在代码里（`ComposerTools`），没有造一个
  「/api/model 挂掉但别的都能用」的现场——那种状态在本特征里不好造，也说不清它意味着什么。
- **屏幕阅读器只量到属性**：`role="alert"`、`aria-label`、disabled 的 `title` 都读出来了，
  但没有用一个真的读屏软件听一遍。
