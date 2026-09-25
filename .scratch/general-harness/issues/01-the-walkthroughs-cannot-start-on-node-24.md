Status: ready-for-agent

# 走查在 Node 24 下启动不了：`npm root -g` 里没有 playwright，而且绝对路径不能直接 `import`

`ui/src` 的改动只有一层门是套件够不着的 —— 真浏览器（AGENTS.md：「自己开浏览器走一趟」）。
这台机器上那份门**十份走查里十份都启动不了**，两个毛病叠在一起：

1. **`npm root -g` 里没有 playwright。** 唯一一份是 `npx @playwright/mcp` 装进 npm 的 `_npx`
   缓存的（声明在 `~/.clj-harness/mcp.edn`）—— `path.join(npmRoot, "playwright", "index.mjs")`
   指向一个不存在的文件。
2. **Windows 下 `import` 绝对路径必须是 `file://` URL。** Node 24 直接抛
   `ERR_UNSUPPORTED_ESM_URL_SCHEME: Only URLs with a scheme in: file, data, and node … Received
   protocol 'c:'` —— 于是脚本连**第一行**都跑不到。
3. （顺带）**缓存里有两份、只有一份装着浏览器**：另一份报
   `browserType.launch: Executable doesn't exist at …ms-playwright/chromium_headless_shell-1246`。
   所以「哪份能起得来」不能预先挑路径，只能挨个试。

**已经修好的两份**（2026-09-25）：`.scratch/lib/playwright.mjs`（公共模块：
`import { launchBrowser } from "../lib/playwright.mjs"`）＋ 接上它的
`thinking-row-tail/walkthrough.mjs`、`events-mux-and-host/walkthrough-fold.mjs`。两份都跑出
ALL GREEN / GREEN。

**还没改的九份**（全仓 `grep -rn 'npm root -g' .scratch`）：

| 目录 | 文件 |
|---|---|
| `events-mux-and-host` | `walkthrough-follow.mjs`、`walkthrough-host.mjs` |
| `model-picker-row-identity` | `walkthrough.mjs` |
| `provider-availability` | `walkthrough.mjs` |
| `reasoning-order` | `walkthrough.mjs`、`scratch-timeline.mjs` |
| `session-after-refresh` | `walkthrough.mjs`、`park-walkthrough.mjs` |
| `session-as-kernel` | `walkthrough-view.mjs` |

**做法**：每份把自己那两行（`npm root -g` ＋ 绝对路径 `import`）和自己的启动循环换成

```js
import { launchBrowser } from "../lib/playwright.mjs";
const browser = await launchBrowser();
```

（相对路径按各自目录深度调整。）**这是个载体改动，不是行为改动** —— 判据里不许出现「顺便改好了」。

**判据**

- [ ] 九份都能**启动**（不再抛 `ERR_UNSUPPORTED_ESM_URL_SCHEME`，也不再找不到模块）。
- [ ] 每一份在它自己的夹具上跑出与**改动前一致**的判据结果；改动前就跑不起来的那几份，
      以各自 spec / README 里记过的结果为准（跑不出 GREEN 的那几条要单独说清是环境还是代码）。
- [ ] 全仓 `grep -rn 'npm root -g' .scratch` 只剩 `.scratch/lib/playwright.mjs` 里那一处。
